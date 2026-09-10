package com.objwww.pr.control.alert.application;

import com.objwww.pr.control.alert.domain.model.ExternalInvocation;
import com.objwww.pr.control.alert.domain.model.ExternalInvocationState;
import com.objwww.pr.control.alert.domain.model.ExecutionStatus;
import com.objwww.pr.control.alert.domain.model.Incident;
import com.objwww.pr.control.alert.domain.model.InvestigationResult;
import com.objwww.pr.control.alert.domain.model.RcaAttempt;
import com.objwww.pr.control.alert.domain.model.RcaAttemptStatus;
import com.objwww.pr.control.alert.domain.model.RcaEngine;
import com.objwww.pr.control.alert.domain.model.RcaRun;
import com.objwww.pr.control.alert.domain.model.RcaTask;
import com.objwww.pr.control.alert.domain.model.RcaTaskState;
import com.objwww.pr.control.alert.domain.model.ValidationStatus;
import com.objwww.pr.control.alert.domain.repository.ExternalInvocationRepository;
import com.objwww.pr.control.alert.domain.repository.IncidentRepository;
import com.objwww.pr.control.alert.domain.repository.InvestigationResultRepository;
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
import java.util.Map;
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

    /**
     * 单轮循环结果（观测/测试断言）
     */
    public enum CycleOutcome {EXECUTED, IDLE, SLOTS_BUSY}

    /**
     * @param revision 领取事务内读得的 run 修订锚（EX-A2 F12：markRunRunning CAS 输入，
     *                 领取→开跑之间取消落地时 CAS 败，run 不复活）
     */
    public record ClaimedWork(int slotNo, long slotEpoch, RcaTask task, RcaRun run,
                              Incident incident, RcaEngine engine, long revision) {
    }

    private final RcaTaskRepository tasks;
    private final RcaRunRepository runs;
    private final RcaAttemptRepository attempts;
    private final InvestigationResultRepository investigationResults;
    private final IncidentRepository incidents;
    private final SchedulerSlotRepository slots;
    private final ExternalInvocationRepository invocations;
    /** EX-A4a（F16）：第一方工具账本 PENDING 悬挂回收（BA-13② 同律第三账本） */
    private final com.objwww.pr.control.alert.domain.tool.RcaToolInvocationLedger toolLedger;
    /** 引擎执行器映射表（M6-01）：分派面唯一权威，无默认回退——缺绑定即 fail-closed */
    private final Map<RcaEngine, RcaTaskExecutor> executors;
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
    /** STARTED 调查记录随 attempt 铸造的请求 schema 版本（M3-04；与 executor RESPONSE_FORMAT 同值） */
    private final int investigationSchemaVersion;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private volatile Thread workerThread;

    /** 单引擎构造（M6-01 前形态；等价仅 HOLMES 绑定的映射表） */
    public RcaWorker(RcaTaskRepository tasks,
                     RcaRunRepository runs,
                     RcaAttemptRepository attempts,
                     InvestigationResultRepository investigationResults,
                     IncidentRepository incidents,
                     SchedulerSlotRepository slots,
                     ExternalInvocationRepository invocations,
                     com.objwww.pr.control.alert.domain.tool.RcaToolInvocationLedger toolLedger,
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
                     Duration hangingGrace,
                     int investigationSchemaVersion) {
        this(tasks, runs, attempts, investigationResults, incidents, slots, invocations,
                toolLedger,
                Map.of(RcaEngine.HOLMES, Objects.requireNonNull(executor, "executor 不得为 null")),
                orchestrator, tx, clock, owner, slotScope, taskLease, heartbeatInterval,
                pollInterval, retryBackoff, hangingGrace, investigationSchemaVersion);
    }

    /** 引擎映射表构造（M6-01）：Map 分派面唯一权威，未知引擎 fail-closed 不回退 */
    public RcaWorker(RcaTaskRepository tasks,
                     RcaRunRepository runs,
                     RcaAttemptRepository attempts,
                     InvestigationResultRepository investigationResults,
                     IncidentRepository incidents,
                     SchedulerSlotRepository slots,
                     ExternalInvocationRepository invocations,
                     com.objwww.pr.control.alert.domain.tool.RcaToolInvocationLedger toolLedger,
                     Map<RcaEngine, RcaTaskExecutor> executors,
                     RcaRunOrchestrator orchestrator,
                     TransactionOperations tx,
                     AlertClock clock,
                     String owner,
                     String slotScope,
                     Duration taskLease,
                     Duration heartbeatInterval,
                     Duration pollInterval,
                     Duration retryBackoff,
                     Duration hangingGrace,
                     int investigationSchemaVersion) {
        this.tasks = Objects.requireNonNull(tasks);
        this.runs = Objects.requireNonNull(runs);
        this.attempts = Objects.requireNonNull(attempts);
        this.investigationResults = Objects.requireNonNull(investigationResults);
        this.incidents = Objects.requireNonNull(incidents);
        this.slots = Objects.requireNonNull(slots);
        this.invocations = Objects.requireNonNull(invocations);
        this.toolLedger = Objects.requireNonNull(toolLedger, "toolLedger");
        this.executors = Objects.requireNonNull(executors, "executors 不得为 null");
        if (executors.isEmpty()) {
            throw new IllegalArgumentException("executors 映射表不得为空");
        }
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
        if (investigationSchemaVersion < 1) {
            throw new IllegalArgumentException("investigationSchemaVersion 从 1 起");
        }
        this.investigationSchemaVersion = investigationSchemaVersion;
    }

    // ------------------------------------------------------------------ 恢复扫描（崩溃双回收 + 悬挂账本）

    /**
     * 过期租约 task → RETRY_WAIT（退避；epoch 不动，重领时 +1 拒旧提交）；
     * run 已出活跃集 → STALE（M4-07 generation fence：死 run 的工作不重排不复活）；
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
            boolean runActive = runs.findById(task.runId())
                    .map(run -> run.state().isActive())
                    .orElse(false);
            RcaTaskState target = runActive ? RcaTaskState.RETRY_WAIT : RcaTaskState.STALE;
            // 崩溃回收也是一次状态迁移（LEASED→RETRY_WAIT/STALE），过状态机（BA-11①/G0-07）
            RcaTaskStateMachine.requireTransition(task.state(), target);
            // EX-A2（F10）：四条件原子回收（含 lease_until<now 复核）——读后心跳已续/
            // 已他人重领/他回收者已收敛 = 0 行竞态失败，不计数零补救
            if (tasks.reclaimExpired(task.id(), task.leaseEpoch(), now, target,
                    now.plus(retryBackoff))) {
                reclaimed++;
                log.warn("task {} 租约过期回收 owner={} → {}", task.id(), task.leaseOwner(), target);
            }
        }
        markHangingInvocationsUnknown(now);
        return reclaimed;
    }

    private void markHangingInvocationsUnknown(Instant now) {
        Instant grace = now.minus(hangingGrace);        for (ExternalInvocation invocation : invocations.findHangingStarted(grace)) {
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
        // M3-04：悬挂调查记录（STARTED 已落但终态未达）同样诚实标 UNKNOWN
        for (InvestigationResult hanging : investigationResults.findHangingStarted(grace)) {
            investigationResults.finishTerminal(hanging.withTerminal(
                    ExecutionStatus.UNKNOWN, ValidationStatus.NOT_VALIDATED, null,
                    null, null, null, null, null, now));
            log.warn("悬挂调查记录 {} STARTED→UNKNOWN（崩溃回收）", hanging.id());
        }
        // EX-A4a（F16）：第一方工具账本 PENDING 悬挂回收（进程死后的孤儿回执永不达；
        // 单语句条件写，阈值与上两账本同源 hangingGrace——必须长于单次在途调用）
        int pendingSwept = toolLedger.reclaimPendingOlderThan(grace);
        if (pendingSwept > 0) {
            log.warn("工具调用账本 {} 行 PENDING→UNKNOWN（崩溃回收）", pendingSwept);
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
            // M6-01：路由四列读视图定引擎（存量行/无路由语义环境列默认 HOLMES）
            RcaEngine engine = runs.findRoutingById(task.runId())
                    .map(RcaRunRepository.RoutingView::engine)
                    .orElse(RcaEngine.HOLMES);
            // EX-A2（F12）：run 行已在本事务 FOR UPDATE 锁下——修订锚随工作快照携带，
            // 供 markRunRunning CAS（领取→开跑缝窗的取消栅栏）
            long revision = runs.currentRevision(task.runId()).orElse(0);
            return Optional.of(new ClaimedWork(acquired.slotNo(), acquired.leaseEpoch(),
                    task, run, incident, engine, revision));
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

        // EX-A2（F12）：开跑 CAS 化——取消在领取与开跑之间落地时修订号已推进，
        // CAS 败 = run 保持 CANCELLED 不复活（晚到结果由 finishTask fence 收敛 STALE）
        orchestrator.markRunRunning(work.run(), work.revision(), now);
        RcaAttempt attempt = new RcaAttempt(UUID.randomUUID(), work.task().id(),
                work.task().attemptCount(), work.task().leaseEpoch(), owner,
                RcaAttemptStatus.STARTED, null, null, null, now, null, null);
        // M3-04 全程落档：attempt 铸造同事事务落 InvestigationResult(STARTED)——
        // 进程在外调后落库前被杀也留悬挂 STARTED 可查（回收标 UNKNOWN）。
        // id 复用 attempt.id()（一 attempt 一记录的 1:1 锚，executor 铸 tool_call 归属）
        tx.executeWithoutResult(status -> {
            attempts.insert(attempt);
            investigationResults.insertStartedIfAbsent(InvestigationResult.started(
                    attempt.id(), attempt.id(), work.run().id(), work.run().generation(),
                    investigationSchemaVersion, null, now));
        });

        RcaTaskExecutor.ExecutionResult result;
        try {
            Runnable heartbeat = () -> {
                Instant hb = clock.now();
                tasks.heartbeat(work.task().id(), owner, work.task().leaseEpoch(), hb, taskLease);
                slots.heartbeat(slotScope, work.slotNo(), owner, work.slotEpoch(), hb, taskLease);
            };
            // M6-01 引擎分派：无绑定执行器 = fail-closed（终态失败，不回退主路径——
            // 回退会污染 Canary 证据面，INV-AM6-2 语义分歧不触发回退）
            RcaTaskExecutor bound = executors.get(work.engine());
            result = bound != null
                    ? bound.execute(work.task(), work.run(), work.incident(), attempt, heartbeat)
                    : RcaTaskExecutor.ExecutionResult.terminal("EXECUTOR_MISSING",
                            "engine 无执行器绑定: " + work.engine());
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
