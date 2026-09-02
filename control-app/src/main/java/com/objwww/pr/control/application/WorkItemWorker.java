package com.objwww.pr.control.application;

import com.objwww.pr.control.domain.ai.ModelBudgetExceededException;
import com.objwww.pr.control.domain.model.RunStep;
import com.objwww.pr.control.domain.model.StepAttempt;
import com.objwww.pr.control.domain.model.WorkItem;
import com.objwww.pr.control.domain.repository.RunStepRepository;
import com.objwww.pr.control.domain.repository.StepAttemptRepository;
import com.objwww.pr.control.domain.repository.WorkItemRepository;
import com.objwww.pr.control.domain.review.ModelOutputParseException;
import com.objwww.pr.shared.snapshot.SecurityRejectionException;
import com.objwww.pr.shared.AttemptStatus;
import com.objwww.pr.shared.StepState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * WorkItem Worker（评审修正 #2：持久化执行的真正执行者，§3.1）。
 *
 * <p>生命周期（每轮 {@link #runOnce()}）：
 * <ol>
 *   <li><b>恢复扫描</b>：过期 LEASED → {@link ReviewOrchestrator#reclaimExpiredLease}
 *       （预算未尽回 READY 重领；耗尽 DEAD + Step/Run FAILED——CT-02/ST-08 的机制）；</li>
 *   <li><b>领取</b>：{@link WorkItemRepository#claimNext}（SKIP LOCKED + 单语句原子租约，
 *       lease_until = now + min(step.timeout_seconds, 上限)，epoch+1，attempt+1）；</li>
 *   <li><b>开工</b>：记 StepAttempt(STARTED)（attempt start 不进账本，E10）+ Step → RUNNING；</li>
 *   <li><b>执行</b>：按 work_type 派发 {@link StepExecutor}，执行期间虚拟线程周期心跳续租，
 *       心跳 0 行 = 已被判死/重领 → 执行器停手；</li>
 *   <li><b>收尾</b>：无论成败都调 {@link ReviewOrchestrator#completeStep}（T2）；
 *       晚到/过期结果由 T2 的 lease_epoch 栅栏记 STALE 不推进（I11，B-2 窗口合法）。</li>
 * </ol>
 *
 * <p>崩溃恢复不依赖优雅停机：进程 kill 后租约自然过期，由存活 worker（或重启后的自己）
 * 的恢复扫描接管。风格与 publisher 侧 OutboxClaimer 一致：零 Spring 注解，虚拟线程循环，
 * start/stop 由 config 的 {@code initMethod/destroyMethod} 驱动，仅 docker profile 装配。
 */
public class WorkItemWorker {

    private static final Logger log = LoggerFactory.getLogger(WorkItemWorker.class);

    private final WorkItemRepository workItems;
    private final RunStepRepository steps;
    private final StepAttemptRepository attempts;
    private final Map<String, StepExecutor> executorsByType;
    private final ReviewOrchestrator orchestrator;
    private final String workerId;
    private final int maxLeaseSeconds;
    private final long heartbeatIntervalMs;
    private final long idleSleepMs;
    private final long errorSleepMs;
    private final int recoveryScanLimit;

    private final AtomicBoolean running = new AtomicBoolean(false);
    private Thread thread;

    public WorkItemWorker(WorkItemRepository workItems, RunStepRepository steps,
                          StepAttemptRepository attempts, List<StepExecutor> executors,
                          ReviewOrchestrator orchestrator, String workerId,
                          int maxLeaseSeconds, long heartbeatIntervalMs,
                          long idleSleepMs, long errorSleepMs, int recoveryScanLimit) {
        this.workItems = Objects.requireNonNull(workItems);
        this.steps = Objects.requireNonNull(steps);
        this.attempts = Objects.requireNonNull(attempts);
        Objects.requireNonNull(executors, "executors");
        this.executorsByType = new HashMap<>();
        for (StepExecutor executor : executors) {
            this.executorsByType.put(executor.workType(), executor);
        }
        this.orchestrator = Objects.requireNonNull(orchestrator);
        this.workerId = Objects.requireNonNull(workerId);
        this.maxLeaseSeconds = maxLeaseSeconds;
        this.heartbeatIntervalMs = heartbeatIntervalMs;
        this.idleSleepMs = idleSleepMs;
        this.errorSleepMs = errorSleepMs;
        this.recoveryScanLimit = recoveryScanLimit;
    }

    /** 单轮：恢复扫描 + 领取执行一个（测试与循环共用入口）；返回处理条数（回收 + 执行） */
    public int runOnce() {
        int recovered = recoverExpiredLeases();
        Optional<WorkItem> claimed = workItems.claimNext(workerId, maxLeaseSeconds);
        claimed.ifPresent(this::executeClaimed);
        return recovered + (claimed.isPresent() ? 1 : 0);
    }

    /** 恢复扫描（独立可测）：过期 LEASED 逐条交 T2 侧回收收尾，单条失败不阻塞整批 */
    public int recoverExpiredLeases() {
        int recovered = 0;
        for (WorkItem expired : workItems.findExpiredLeases(recoveryScanLimit)) {
            try {
                if (orchestrator.reclaimExpiredLease(expired.getId())) {
                    recovered++;
                }
            } catch (Exception e) {
                log.warn("回收过期租约失败: {}", expired.getId(), e);
            }
        }
        return recovered;
    }

    // ------------------------------------------------------------------ 执行

    private void executeClaimed(WorkItem item) {
        Instant now = Instant.now();
        RunStep step = steps.findById(item.getStepId())
                .orElseThrow(() -> new IllegalStateException("run_step 不存在: " + item.getStepId()));

        // 记物理尝试（E10：不进账本，本行即计数载体）+ Step 推进 RUNNING
        StepAttempt attempt = new StepAttempt(UUID.randomUUID(), step.getId(), item.getId(),
                item.getAttemptCount(), item.getLeaseEpoch(), workerId, AttemptStatus.STARTED,
                null, null, step.getInputArtifactDigest(), null,
                null, null, null, now, null);
        attempts.save(attempt);
        if (step.getState() == StepState.WAITING) {
            step.transitionTo(StepState.READY, now); // RETRY_WAIT 到期回流（§3 状态机）
        }
        if (step.getState() == StepState.READY) {
            step.transitionTo(StepState.RUNNING, now);
        }
        steps.save(step);

        StepExecutor executor = executorsByType.get(item.getWorkType());
        StepOutcome outcome;
        if (executor == null) {
            outcome = new StepOutcome.Failed("UnknownWorkType", "UNKNOWN_WORK_TYPE",
                    "无执行器: " + item.getWorkType(), false, null);
        } else {
            HeartbeatRunner heartbeat = new HeartbeatRunner(item);
            try {
                outcome = executor.execute(new StepExecutionContext(item, step, attempt.getId()), heartbeat);
            } catch (Exception e) {
                outcome = classify(e);
            } finally {
                heartbeat.close();
            }
        }

        // 收尾一律走 T2：租约仍有效则推进；已易主则 T2 栅栏记 STALE（I11）
        T2Outcome t2 = orchestrator.completeStep(new StepCompletion(
                item.getId(), step.getId(), attempt.getId(), workerId, item.getLeaseEpoch(), outcome));
        log.debug("work_item {} attempt#{} -> {} (T2: {})",
                item.getId(), attempt.getAttemptNo(), outcome.getClass().getSimpleName(), t2);
    }

    /** 异常 → Failed 归类（errorClass 列 varchar(32)：用语义码而非类全名）
     *  M3：捕获 ModelRetryDeferredException，传递 notBefore 实现 durable defer */
    private static StepOutcome.Failed classify(Exception e) {
        if (e instanceof LeaseLostException) {
            return new StepOutcome.Failed("LeaseLost", "LEASE_LOST", e.getMessage(), false, null);
        }
        if (e instanceof SecurityRejectionException) {
            return new StepOutcome.Failed("SecurityRejection", "SECURITY_REJECTION", e.getMessage(), false, null);
        }
        if (e instanceof com.objwww.pr.control.domain.ai.ModelRetryDeferredException defer) {
            // M3：Retry-After 长延迟 → notBefore → T2 写 available_at
            return new StepOutcome.Failed("ModelRetryDeferred", "MODEL_RETRY_DEFERRED",
                    defer.getMessage(), true, defer.notBefore());
        }
        if (e instanceof ModelBudgetExceededException) {
            return new StepOutcome.Failed("ModelBudgetExceeded", "MODEL_BUDGET_EXCEEDED", e.getMessage(), false, null);
        }
        if (e instanceof ModelOutputParseException) {
            return new StepOutcome.Failed("ModelOutputParse", "MODEL_OUTPUT_PARSE", e.getMessage(), false, null);
        }
        if (e instanceof com.objwww.pr.control.domain.ai.ModelCallFailedException mcf) {
            // M3 修正：使用异常自带的stepRetryable，不硬编码（阻断项3）
            return new StepOutcome.Failed("ModelCallFailed", mcf.errorCode() != null ? mcf.errorCode() : "MODEL_CALL_FAILED",
                    e.getMessage(), mcf.stepRetryable(), null);
        }
        // 未预期异常按可重试处理；确定性缺陷靠 attempt 预算耗尽兜底（不无限打转）
        return new StepOutcome.Failed("Unexpected", "UNEXPECTED",
                e.getClass().getSimpleName() + ": " + e.getMessage(), true, null);
    }

    // ------------------------------------------------------------------ 循环

    public void start() {
        if (running.compareAndSet(false, true)) {
            thread = Thread.ofVirtual().name("work-item-worker").start(this::loop);
            log.info("WorkItemWorker 启动: worker={}", workerId);
        }
    }

    public void stop() {
        running.set(false);
        if (thread != null) {
            thread.interrupt();
        }
    }

    private void loop() {
        while (running.get()) {
            try {
                if (runOnce() == 0) {
                    Thread.sleep(idleSleepMs);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (Exception e) {
                log.error("WorkItemWorker 轮询异常", e);
                sleepQuietly(errorSleepMs);
            }
        }
    }

    private void sleepQuietly(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    // ------------------------------------------------------------------ 心跳

    /**
     * 执行期心跳：虚拟线程周期续租（lease_until 顺延一个租约窗口）。
     * UPDATE 0 行 = 租约已易主 → 置死，执行器下一检查点停手；瞬时异常保持存活靠下轮重试。
     */
    private final class HeartbeatRunner implements LeaseHeartbeat, AutoCloseable {

        private final WorkItem item;
        private final AtomicBoolean alive = new AtomicBoolean(true);
        private final AtomicBoolean stopped = new AtomicBoolean(false);
        private final Thread heartbeatThread;

        HeartbeatRunner(WorkItem item) {
            this.item = item;
            this.heartbeatThread = Thread.ofVirtual().name("lease-heartbeat").start(this::tickLoop);
        }

        @Override
        public boolean isAlive() {
            return alive.get();
        }

        @Override
        public void close() {
            stopped.set(true);
            heartbeatThread.interrupt();
        }

        private void tickLoop() {
            while (!stopped.get()) {
                try {
                    Thread.sleep(heartbeatIntervalMs);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
                if (stopped.get()) {
                    return;
                }
                try {
                    boolean renewed = workItems.heartbeat(item.getId(), workerId, item.getLeaseEpoch(),
                            maxLeaseSeconds);
                    if (!renewed) {
                        alive.set(false); // 0 行：已被判死/重领
                        log.warn("租约心跳失效，停止执行: work_item={}", item.getId());
                        return;
                    }
                } catch (Exception e) {
                    log.warn("租约心跳异常（下轮重试）: work_item={}", item.getId(), e);
                }
            }
        }
    }
}
