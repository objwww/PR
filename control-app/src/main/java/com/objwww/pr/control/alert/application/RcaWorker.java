package com.objwww.pr.control.alert.application;

import com.objwww.pr.control.alert.domain.model.ExternalInvocation;
import com.objwww.pr.control.alert.domain.model.ExternalInvocationState;
import com.objwww.pr.control.alert.domain.model.Incident;
import com.objwww.pr.control.alert.domain.model.RcaAttempt;
import com.objwww.pr.control.alert.domain.model.RcaAttemptStatus;
import com.objwww.pr.control.alert.domain.model.RcaRun;
import com.objwww.pr.control.alert.domain.model.RcaTask;
import com.objwww.pr.control.alert.domain.model.RcaTaskState;
import com.objwww.pr.control.alert.domain.repository.ExternalInvocationRepository;
import com.objwww.pr.control.alert.domain.repository.IncidentRepository;
import com.objwww.pr.control.alert.domain.repository.RcaAttemptRepository;
import com.objwww.pr.control.alert.domain.repository.RcaRunRepository;
import com.objwww.pr.control.alert.domain.repository.RcaTaskRepository;
import com.objwww.pr.control.alert.domain.repository.SchedulerSlotRepository;
import com.objwww.pr.control.alert.domain.statemachine.RcaTaskStateMachine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.support.TransactionOperations;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * RCA worker（零注解虚拟线程，§3.2/§4.1）：
 * 恢复扫描（过期 task 租约回收 + slot 随租约过期回收 + 悬挂账本 STARTED→UNKNOWN）→
 * 短事务领取（slot 占用与 task claim 同一 TransactionOperations）→
 * markRunRunning + attempt STARTED → 执行（事务外，executor 心跳续租）→ finishTask 收尾单事务。
 *
 * <p>崩溃语义（INV-AM1-7）：领取后 SIGKILL，task 租约与 slot 租约各自过期回收（双回收，
 * CT-A05/DP-B05 实证）；悬挂账本 STARTED 由恢复扫描标 UNKNOWN（诚实对账，不猜测结局）。
 */
public class RcaWorker {

    private static final Logger log = LoggerFactory.getLogger(RcaWorker.class);

    /** 单轮循环结果（观测/测试断言） */
    public enum CycleOutcome {EXECUTED, IDLE, SLOTS_BUSY}

    public record ClaimedWork(int slotNo, long slotEpoch, RcaTask task, RcaRun run, Incident incident) {
    }

    private final RcaTaskRepository tasks;
    private final RcaRunRepository runs;
    private final RcaAttemptRepository attempts;
    private final IncidentRepository incidents;
    private final SchedulerSlotRepository slots;
    private final ExternalInvocationRepository invocations;
    private final RcaTaskExecutor executor;
    private final RcaRunOrchestrator orchestrator;
    private final TransactionOperations tx;
    private final AlertClock clock;
    private final String owner;
    private final String slotScope;
    private final Duration taskLease;
    private final Duration heartbeatInterval;
    private final Duration pollInterval;
    /** 回收后的重试退避（BA-13②：可配置，原硬编码 1min） */
    private final Duration retryBackoff;
    /** 悬挂账本宽限（BA-13②：由 holmes read-timeout 派生，必须长于单次在途调用） */
    private final Duration hangingGrace;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private volatile Thread workerThread;

    public RcaWorker(RcaTaskRepository tasks,
                     RcaRunRepository runs,
                     RcaAttemptRepository attempts,
                     IncidentRepository incidents,
                     SchedulerSlotRepository slots,
                     ExternalInvocationRepository invocations,
                     RcaTaskExecutor executor,
                     RcaRunOrchestrator orchestrator,
                     TransactionOperations tx,
                     AlertClock clock,
                     String owner,
                     String slotScope,
                     Duration taskLease,
                     Duration heartbeatInterval,
                     Duration pollInterval,
                     Duration retryBackoff,
                     Duration hangingGrace) {
        this.tasks = Objects.requireNonNull(tasks);
        this.runs = Objects.requireNonNull(runs);
        this.attempts = Objects.requireNonNull(attempts);
        this.incidents = Objects.requireNonNull(incidents);
        this.slots = Objects.requireNonNull(slots);
        this.invocations = Objects.requireNonNull(invocations);
        this.executor = Objects.requireNonNull(executor);
        this.orchestrator = Objects.requireNonNull(orchestrator);
        this.tx = Objects.requireNonNull(tx);
        this.clock = Objects.requireNonNull(clock);
        this.owner = Objects.requireNonNull(owner);
        this.slotScope = Objects.requireNonNull(slotScope);
        this.taskLease = Objects.requireNonNull(taskLease);
        this.heartbeatInterval = Objects.requireNonNull(heartbeatInterval);
        this.pollInterval = Objects.requireNonNull(pollInterval);
        if (retryBackoff.isNegative() || retryBackoff.isZero()) {
            throw new IllegalArgumentException("retryBackoff 必须为正");
        }
        this.retryBackoff = retryBackoff;
        if (hangingGrace.isNegative() || hangingGrace.isZero()) {
            throw new IllegalArgumentException("hangingGrace 必须为正");
        }
        this.hangingGrace = hangingGrace;
    }

    // ------------------------------------------------------------------ 恢复扫描（崩溃双回收 + 悬挂账本）

    /**
     * 过期租约 task → RETRY_WAIT（退避；epoch 不动，重领时 +1 拒旧提交）；
     * slot 由 {@code slots.reclaimExpired} 随自身租约过期回收；
     * 悬挂账本 STARTED 超过宽限 → UNKNOWN。
     *
     * @return 回收的 task 数
     */
    public long recoverExpired() {
        Instant now = clock.now();
        slots.reclaimExpired(now);
        long reclaimed = 0;
        for (RcaTask task : tasks.findExpiredLeased(now)) {
            // 崩溃回收也是一次状态迁移（LEASED→RETRY_WAIT），过状态机（BA-11①/G0-07）
            RcaTaskStateMachine.requireTransition(task.state(), RcaTaskState.RETRY_WAIT);
            Instant readyAt = now.plus(retryBackoff);
            RcaTask back = new RcaTask(task.id(), task.runId(), task.taskKey(),
                    RcaTaskState.RETRY_WAIT, task.priority(), readyAt, readyAt,
                    task.deadlineAt(), null, null, task.leaseEpoch(),
                    task.attemptCount(), task.maxAttempts(), task.createdAt(), now);
            if (tasks.update(back)) {
                reclaimed++;
                log.warn("task {} 租约过期回收（疑似 worker 崩溃）owner={}", task.id(), task.leaseOwner());
            }
        }
        markHangingInvocationsUnknown(now);
        return reclaimed;
    }

    private void markHangingInvocationsUnknown(Instant now) {
        Instant grace = now.minus(hangingGrace);
        for (ExternalInvocation invocation : invocations.findHangingStarted(grace)) {
            ExternalInvocation unknown = new ExternalInvocation(
                    invocation.id(), invocation.invocationId(), invocation.callSeq(),
                    invocation.runId(), invocation.taskId(), invocation.attemptId(),
                    invocation.leaseEpoch(), invocation.endpoint(), invocation.requestDigest(),
                    invocation.responseDigest(), ExternalInvocationState.UNKNOWN,
                    invocation.httpStatus(), invocation.latencyMs(),
                    invocation.promptTokens(), invocation.completionTokens(),
                    invocation.totalTokens(), invocation.usageMissing(),
                    invocation.holmesVersion(), invocation.model(), invocation.toolsetVersion(),
                    invocation.errorClass(), "worker-crash-recovered",
                    invocation.startedAt(), now);
            invocations.finish(unknown);
            log.warn("悬挂账本 {} STARTED→UNKNOWN（崩溃回收）", invocation.id());
        }
    }

    // ------------------------------------------------------------------ 领取（slot+task 同一事务）

    /**
     * 短事务：tryAcquire slot（epoch 随槽返回）→ claimNext task（SLA 排序）；
     * 任一不成立则归还槽/不翻转（slot+task 同事务语义，INV-AM1-7；崩溃缝隙由双租约回收兜底）。
     */
    public Optional<ClaimedWork> claimWork() {
        return tx.execute(status -> {
            Instant now = clock.now();
            Optional<SchedulerSlotRepository.AcquiredSlot> slot =
                    slots.tryAcquire(slotScope, owner, null, now, taskLease);
            if (slot.isEmpty()) {
                return Optional.<ClaimedWork>empty();
            }
            SchedulerSlotRepository.AcquiredSlot acquired = slot.get();
            Optional<RcaTask> claimed = tasks.claimNext(owner, now, taskLease);
            if (claimed.isEmpty()) {
                slots.release(slotScope, acquired.slotNo(), owner, acquired.leaseEpoch());
                return Optional.empty();
            }
            RcaTask task = claimed.get();
            RcaRun run = runs.findByIdForUpdate(task.runId()).orElseThrow();
            Incident incident = incidents.findById(run.incidentId()).orElseThrow();
            return Optional.of(new ClaimedWork(acquired.slotNo(), acquired.leaseEpoch(),
                    task, run, incident));
        });
    }

    // ------------------------------------------------------------------ 执行一轮

    /** 单轮：领取 → 执行 → 收尾；无可领工作返回 IDLE/SLOTS_BUSY */
    public CycleOutcome runOneCycle() {
        Optional<ClaimedWork> workOpt = claimWork();
        if (workOpt.isEmpty()) {
            return slots.occupiedSlots(slotScope).size() >= slots.totalSlots(slotScope)
                    ? CycleOutcome.SLOTS_BUSY : CycleOutcome.IDLE;
        }
        ClaimedWork work = workOpt.get();
        Instant now = clock.now();

        orchestrator.markRunRunning(work.run(), now);
        RcaAttempt attempt = new RcaAttempt(UUID.randomUUID(), work.task().id(),
                work.task().attemptCount(), work.task().leaseEpoch(), owner,
                RcaAttemptStatus.STARTED, null, null, null, now, null);
        attempts.insert(attempt);

        RcaTaskExecutor.ExecutionResult result;
        try {
            Runnable heartbeat = () -> {
                Instant hb = clock.now();
                tasks.heartbeat(work.task().id(), owner, work.task().leaseEpoch(), hb, taskLease);
                slots.heartbeat(slotScope, work.slotNo(), owner, work.slotEpoch(), hb, taskLease);
            };
            result = executor.execute(work.task(), work.run(), work.incident(), attempt, heartbeat);
        } catch (RuntimeException e) {
            log.error("task {} 执行异常", work.task().id(), e);
            result = RcaTaskExecutor.ExecutionResult.retryable("EXECUTOR_ERROR", e.getMessage());
        }

        FinishTx finishTx = new FinishTx(work, attempt, result);
        tx.executeWithoutResult(status -> orchestrator.finishTask(
                finishTx.work().task(), owner, finishTx.work().slotNo(),
                finishTx.work().slotEpoch(), finishTx.result(), finishTx.attempt()));
        return CycleOutcome.EXECUTED;
    }

    private record FinishTx(ClaimedWork work, RcaAttempt attempt,
                            RcaTaskExecutor.ExecutionResult result) {
    }

    // ------------------------------------------------------------------ 常驻循环

    public synchronized void start() {
        if (running.compareAndSet(false, true)) {
            workerThread = Thread.ofVirtual().name("rca-worker-" + owner).start(this::loop);
            log.info("RcaWorker 启动 owner={} scope={}", owner, slotScope);
        }
    }

    public synchronized void stop() {
        running.set(false);
        if (workerThread != null) {
            workerThread.interrupt();
            workerThread = null;
        }
    }

    private void loop() {
        while (running.get()) {
            try {
                recoverExpired();
                CycleOutcome outcome = runOneCycle();
                if (outcome != CycleOutcome.EXECUTED) {
                    Thread.sleep(pollInterval.toMillis());
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (RuntimeException e) {
                log.error("RcaWorker 循环异常，{} 后重试", pollInterval, e);
                try {
                    Thread.sleep(pollInterval.toMillis());
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }
    }
}
