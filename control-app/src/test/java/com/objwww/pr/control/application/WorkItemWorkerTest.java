package com.objwww.pr.control.application;

import com.objwww.pr.control.domain.ai.TokenUsage;
import com.objwww.pr.control.domain.model.PrSubjectState;
import com.objwww.pr.control.domain.model.ReviewRun;
import com.objwww.pr.control.domain.model.RunStep;
import com.objwww.pr.control.domain.model.StepAttempt;
import com.objwww.pr.control.domain.model.WorkItem;
import com.objwww.pr.control.domain.review.ReviewFindingDraft;
import com.objwww.pr.control.domain.review.ReviewOutcome;
import com.objwww.pr.control.support.InMemoryStores;
import com.objwww.pr.control.support.OrchestratorFixture;
import com.objwww.pr.shared.AttemptStatus;
import com.objwww.pr.shared.Digest;
import com.objwww.pr.shared.ExecutionEventType;
import com.objwww.pr.shared.RunState;
import com.objwww.pr.shared.StepState;
import com.objwww.pr.shared.WorkItemState;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.function.BiFunction;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * WorkItemWorker（T10）：领取决策 / 执行收尾 / 退避 / 耗尽 / 恢复扫描 / 晚到结果与心跳失效。
 * 全链路用 OrchestratorFixture 的真实 T2（内存假端口模拟约束），
 * Step 执行器用 stub（ReviewAgentLoop 本身由 ReviewAgentLoopTest 覆盖）。
 */
class WorkItemWorkerTest {

    private OrchestratorFixture fx;

    @BeforeEach
    void setUp() {
        fx = new OrchestratorFixture();
    }

    // ------------------------------------------------------------------ 夹具

    /** T1 建 Run，返回 (run, step, workItem[READY]) */
    private WorkItem intakeReadyItem(String headSha, String deliveryId) {
        ReviewRun run = fx.orchestrator.runIntake(new IntakeCommand(987L, 12345L, "org/repo", 7,
                PrSubjectState.OPEN, false, false,
                headSha, "main", "basesha456", null,
                Digest.sha256Of("diff-" + headSha), Digest.sha256Of("snap-" + headSha),
                "m0-policy-v1", "m0-prompt-v1", "m0-toolset-v1", deliveryId, null));
        RunStep step = fx.steps.findByRunId(run.getId()).get(0);
        return fx.workItems.findByStepId(step.getId()).orElseThrow();
    }

    private static StepOutcome successOutcome() {
        return new StepOutcome.Succeeded(Digest.sha256Of("output"),
                new ReviewOutcome(List.of(new ReviewFindingDraft("a/Foo.java", 3, 3,
                        "rule-1", "MAJOR", "msg", Digest.sha256Of("fp-1"))),
                        0, 0, 1, 1, 0, new TokenUsage(10, 20, 30), "[]"));
    }

    /** 可编程执行器 stub：workType 固定 REVIEW，逻辑由函数注入 */
    private static StepExecutor stubExecutor(
            BiFunction<StepExecutionContext, LeaseHeartbeat, StepOutcome> logic) {
        return new StepExecutor() {
            @Override
            public String workType() {
                return ReviewOrchestrator.WORK_TYPE_REVIEW;
            }

            @Override
            public StepOutcome execute(StepExecutionContext context, LeaseHeartbeat heartbeat) {
                return logic.apply(context, heartbeat);
            }
        };
    }

    private WorkItemWorker newWorker(StepExecutor executor) {
        return newWorker(executor, fx.workItems, 60_000);
    }

    private WorkItemWorker newWorker(StepExecutor executor,
                                     com.objwww.pr.control.domain.repository.WorkItemRepository workItems,
                                     long heartbeatIntervalMs) {
        return new WorkItemWorker(workItems, fx.steps, fx.attempts, List.of(executor),
                fx.orchestrator, "worker-test", 600, heartbeatIntervalMs, 10, 10, 50);
    }

    // ------------------------------------------------------------------ 领取 + 执行 + 收尾

    @Test
    void claimsExecutesAndCompletesThroughT2() {
        WorkItem item = intakeReadyItem("head1", "d-1");
        WorkItemWorker worker = newWorker(stubExecutor((ctx, hb) -> successOutcome()));

        assertThat(worker.runOnce()).isEqualTo(1);

        WorkItem after = fx.workItems.findById(item.getId()).orElseThrow();
        assertThat(after.getState()).isEqualTo(WorkItemState.DONE);
        RunStep step = fx.steps.findById(item.getStepId()).orElseThrow();
        assertThat(step.getState()).isEqualTo(StepState.SUCCEEDED);
        StepAttempt attempt = fx.attempts.findByStepId(step.getId()).get(0);
        assertThat(attempt.getStatus()).isEqualTo(AttemptStatus.SUCCEEDED);
        assertThat(attempt.getWorkerId()).isEqualTo("worker-test");
        // T2 收尾产物：findings + 两条 outbox 命令 + Run 推进
        assertThat(fx.findings.findByRunId(step.getReviewRunId())).hasSize(1);
        assertThat(fx.outbox.all()).hasSize(2);
        assertThat(fx.runs.findById(step.getReviewRunId()).orElseThrow().getState())
                .isEqualTo(RunState.REVIEW_COMPLETE);
    }

    @Test
    void idleWhenNothingClaimable() {
        WorkItemWorker worker = newWorker(stubExecutor((ctx, hb) -> successOutcome()));
        assertThat(worker.runOnce()).isEqualTo(0);
    }

    @Test
    void claimDecisionOrdersByPriorityThenAvailability() {
        // 领取决策在端口层：直接测假端口的排序/过滤语义（SKIP LOCKED 单并发下退化为 min）
        WorkItem low = intakeReadyItem("head1", "d-1");
        Instant now = Instant.now();
        WorkItem highPriority = new WorkItem(UUID.randomUUID(), low.getReviewRunId(), UUID.randomUUID(),
                "REVIEW", WorkItemState.READY, 10, now, null, null, 0, 0, 3, now, now);
        WorkItem futureAvailable = new WorkItem(UUID.randomUUID(), low.getReviewRunId(), UUID.randomUUID(),
                "REVIEW", WorkItemState.READY, 99, now.plusSeconds(3600), null, null, 0, 0, 3, now, now);
        fx.workItems.save(highPriority);
        fx.workItems.save(futureAvailable);
        fx.workItems.setClock(now); // 时间旅行：操纵 fake 时钟而非传应用侧 now（I17）

        // 高优先级先领；available_at 未到期的即使优先级更高也不领
        var first = fx.workItems.claimNext("w", 600).orElseThrow();
        assertThat(first.getId()).isEqualTo(highPriority.getId());
        assertThat(first.getState()).isEqualTo(WorkItemState.LEASED);
        assertThat(first.getLeaseOwner()).isEqualTo("w");
        assertThat(first.getLeaseEpoch()).isEqualTo(1);
        assertThat(first.getAttemptCount()).isEqualTo(1);
        assertThat(first.getLeaseUntil()).isAfter(now); // lease = min(step.timeout, 上限)

        var second = fx.workItems.claimNext("w", 600).orElseThrow();
        assertThat(second.getId()).isEqualTo(low.getId()); // futureAvailable 跳过
    }

    // ------------------------------------------------------------------ 失败路径

    @Test
    void retryableFailureSchedulesBackoff() {
        WorkItem item = intakeReadyItem("head1", "d-1");
        WorkItemWorker worker = newWorker(stubExecutor(
                (ctx, hb) -> new StepOutcome.Failed("ModelTimeout", "MODEL_TIMEOUT", "t", true)));

        assertThat(worker.runOnce()).isEqualTo(1);

        WorkItem after = fx.workItems.findById(item.getId()).orElseThrow();
        assertThat(after.getState()).isEqualTo(WorkItemState.RETRY_WAIT);
        // T2 线性退避：available_at = now + 30s * attemptCount(1)
        assertThat(after.getAvailableAt()).isAfter(Instant.now().plusSeconds(20));
        RunStep step = fx.steps.findById(item.getStepId()).orElseThrow();
        assertThat(step.getState()).isEqualTo(StepState.WAITING);
        assertThat(fx.attempts.findByStepId(step.getId()).get(0).getStatus())
                .isEqualTo(AttemptStatus.FAILED_RETRYABLE);
    }

    @Test
    void terminalFailureGoesDead() {
        WorkItem item = intakeReadyItem("head1", "d-1");
        WorkItemWorker worker = newWorker(stubExecutor(
                (ctx, hb) -> new StepOutcome.Failed("ModelBudgetExceeded", "MODEL_BUDGET_EXCEEDED", "b", false)));

        worker.runOnce();

        WorkItem after = fx.workItems.findById(item.getId()).orElseThrow();
        assertThat(after.getState()).isEqualTo(WorkItemState.DEAD);
        RunStep step = fx.steps.findById(item.getStepId()).orElseThrow();
        assertThat(step.getState()).isEqualTo(StepState.FAILED);
        assertThat(fx.runs.findById(step.getReviewRunId()).orElseThrow().getState())
                .isEqualTo(RunState.FAILED);
        assertThat(fx.outbox.all()).isEmpty(); // 失败不产 outbox 命令
    }

    @Test
    void retriedItemFlowsBackToRunning() {
        // RETRY_WAIT 到期重领：WAITING → READY → RUNNING（§3 状态机回流）
        WorkItem item = intakeReadyItem("head1", "d-1");
        WorkItemWorker failing = newWorker(stubExecutor(
                (ctx, hb) -> new StepOutcome.Failed("ModelTimeout", "MODEL_TIMEOUT", "t", true)));
        failing.runOnce();
        WorkItem waiting = fx.workItems.findById(item.getId()).orElseThrow();
        waiting.retryLater(Instant.now().minusSeconds(1), Instant.now()); // 快进到退避到期
        fx.workItems.save(waiting);

        WorkItemWorker retrying = newWorker(stubExecutor((ctx, hb) -> successOutcome()));
        assertThat(retrying.runOnce()).isEqualTo(1);

        WorkItem after = fx.workItems.findById(item.getId()).orElseThrow();
        assertThat(after.getState()).isEqualTo(WorkItemState.DONE);
        assertThat(after.getAttemptCount()).isEqualTo(2);
        assertThat(fx.attempts.findByStepId(item.getStepId())).hasSize(2);
    }

    // ------------------------------------------------------------------ 恢复扫描

    @Test
    void recoversExpiredLeaseWithBudgetLeft() {
        WorkItem item = intakeReadyItem("head1", "d-1");
        // 模拟 worker 崩溃：LEASED 且租约已过期（attempt 1/3 未耗尽）
        item.leaseTo("dead-worker", Instant.now().minusSeconds(5), Instant.now().minusSeconds(700));
        fx.workItems.save(item);
        long epochBefore = item.getLeaseEpoch();
        WorkItemWorker worker = newWorker(stubExecutor((ctx, hb) -> successOutcome()));

        assertThat(worker.recoverExpiredLeases()).isEqualTo(1);

        WorkItem after = fx.workItems.findById(item.getId()).orElseThrow();
        assertThat(after.getState()).isEqualTo(WorkItemState.READY); // 立即可重领
        assertThat(after.getLeaseEpoch()).isEqualTo(epochBefore + 1); // epoch+1 使僵尸心跳失效
        assertThat(after.getLeaseOwner()).isNull();
        assertThat(after.getAttemptCount()).isEqualTo(1); // 崩溃那次在领取时已计，回收不重复计
    }

    @Test
    void exhaustedExpiredLeaseGoesDeadAndFailsStep() {
        WorkItem item = intakeReadyItem("head1", "d-1");
        RunStep step = fx.steps.findById(item.getStepId()).orElseThrow();
        // 模拟 worker 死于第 3 次（最后一次）尝试中途：LEASED 过期 + 僵尸 STARTED attempt + step RUNNING
        Instant past = Instant.now().minusSeconds(700);
        WorkItem zombie = new WorkItem(item.getId(), item.getReviewRunId(), item.getStepId(),
                "REVIEW", WorkItemState.LEASED, 0, past,
                "dead-worker", Instant.now().minusSeconds(5), 3, 3, 3, past, past);
        fx.workItems.save(zombie);
        StepAttempt zombieAttempt = new StepAttempt(UUID.randomUUID(), step.getId(), item.getId(),
                3, 3, "dead-worker", AttemptStatus.STARTED,
                null, null, null, null, null, null, null, past, null);
        fx.attempts.save(zombieAttempt);
        step.transitionTo(StepState.RUNNING, past);
        fx.steps.save(step);
        WorkItemWorker worker = newWorker(stubExecutor((ctx, hb) -> successOutcome()));

        assertThat(worker.recoverExpiredLeases()).isEqualTo(1);

        assertThat(fx.workItems.findById(item.getId()).orElseThrow().getState())
                .isEqualTo(WorkItemState.DEAD);
        assertThat(fx.attempts.findById(zombieAttempt.getId()).orElseThrow().getStatus())
                .isEqualTo(AttemptStatus.ABANDONED);
        assertThat(fx.steps.findById(step.getId()).orElseThrow().getState())
                .isEqualTo(StepState.FAILED);
        assertThat(fx.runs.findById(step.getReviewRunId()).orElseThrow().getState())
                .isEqualTo(RunState.FAILED);
        assertThat(fx.events.all())
                .anySatisfy(e -> {
                    assertThat(e.eventType()).isEqualTo(ExecutionEventType.STEP_RESULT);
                    assertThat(e.payload().get("reason")).isEqualTo("ATTEMPT_BUDGET_EXHAUSTED");
                });
    }

    @Test
    void recoveredItemIsReclaimedAndExecuted() {
        // 完整恢复闭环（ST-08 机制）：崩溃 → 回收 READY → 下一轮重领执行 → 成功收尾
        WorkItem item = intakeReadyItem("head1", "d-1");
        item.leaseTo("dead-worker", Instant.now().minusSeconds(5), Instant.now().minusSeconds(700));
        fx.workItems.save(item);
        WorkItemWorker worker = newWorker(stubExecutor((ctx, hb) -> successOutcome()));

        worker.recoverExpiredLeases();
        assertThat(worker.runOnce()).isEqualTo(1);

        assertThat(fx.workItems.findById(item.getId()).orElseThrow().getState())
                .isEqualTo(WorkItemState.DONE);
        assertThat(fx.runs.findById(item.getReviewRunId()).orElseThrow().getState())
                .isEqualTo(RunState.REVIEW_COMPLETE);
    }

    // ------------------------------------------------------------------ 晚到结果 / 心跳失效（I11）

    @Test
    void lateResultRecordedStaleWhenLeaseReclaimedMidFlight() {
        // B-2：僵尸 worker 在租约窗口内的完成上报是合法的；易主后的上报只能记 STALE
        WorkItem item = intakeReadyItem("head1", "d-1");
        WorkItemWorker worker = newWorker(stubExecutor((ctx, hb) -> {
            // 执行期间租约被他人重领（epoch+1）——模拟崩溃恢复抢先接管
            WorkItem current = fx.workItems.findById(ctx.workItem().getId()).orElseThrow();
            current.leaseTo("other-worker", Instant.now().plusSeconds(600), Instant.now());
            fx.workItems.save(current);
            return successOutcome();
        }));

        worker.runOnce();

        WorkItem after = fx.workItems.findById(item.getId()).orElseThrow();
        assertThat(after.getState()).isEqualTo(WorkItemState.LEASED); // 新租约不被晚到结果覆盖
        assertThat(after.getLeaseOwner()).isEqualTo("other-worker");
        RunStep step = fx.steps.findById(item.getStepId()).orElseThrow();
        assertThat(step.getState()).isEqualTo(StepState.RUNNING); // Step 不推进
        assertThat(fx.attempts.findByStepId(step.getId()).get(0).getStatus())
                .isEqualTo(AttemptStatus.STALE);
        assertThat(fx.outbox.all()).isEmpty();
    }

    @Test
    void heartbeatLossStopsExecutionAndReports() {
        // 心跳 0 行（已被判死/重领）→ 执行器在检查点停手（LeaseLost）→ 照常上报 T2 由栅栏裁决
        WorkItem item = intakeReadyItem("head1", "d-1");
        // 心跳必死的假端口：其余方法委托给夹具内存实现
        InMemoryStores.WorkItems inner = fx.workItems;
        com.objwww.pr.control.domain.repository.WorkItemRepository deadHeartbeat =
                new com.objwww.pr.control.domain.repository.WorkItemRepository() {
                    @Override
                    public void save(WorkItem workItem) {
                        inner.save(workItem);
                    }

                    @Override
                    public java.util.Optional<WorkItem> findById(UUID id) {
                        return inner.findById(id);
                    }

                    @Override
                    public java.util.Optional<WorkItem> findByStepId(UUID stepId) {
                        return inner.findByStepId(stepId);
                    }

                    @Override
                    public java.util.Optional<WorkItem> claimNext(String owner, int maxLeaseSeconds) {
                        return inner.claimNext(owner, maxLeaseSeconds);
                    }

                    @Override
                    public boolean heartbeat(UUID id, String owner, long epoch, int leaseSeconds) {
                        return false; // 模拟租约已易主
                    }

                    @Override
                    public List<WorkItem> findExpiredLeases(int limit) {
                        return inner.findExpiredLeases(limit);
                    }

                    @Override
                    public boolean reclaimExpiredLease(UUID id, long epoch, WorkItemState target) {
                        return inner.reclaimExpiredLease(id, epoch, target);
                    }

                    @Override
                    public boolean transitionIfLeaseCurrent(UUID id, String owner, long epoch,
                                                            WorkItemState to, Instant availableAt) {
                        return inner.transitionIfLeaseCurrent(id, owner, epoch, to, availableAt);
                    }

                    @Override
                    public int cancelActiveByRunId(UUID reviewRunId) {
                        return inner.cancelActiveByRunId(reviewRunId);
                    }
                };
        boolean[] executorStopped = {false};
        WorkItemWorker worker = newWorker(stubExecutor((ctx, hb) -> {
            long deadline = System.currentTimeMillis() + 5_000;
            while (hb.isAlive() && System.currentTimeMillis() < deadline) {
                try {
                    Thread.sleep(5);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
            executorStopped[0] = !hb.isAlive();
            if (executorStopped[0]) {
                throw new LeaseLostException("心跳失效，停手");
            }
            return successOutcome();
        }), deadHeartbeat, 5); // 心跳间隔 5ms：快速触发失效

        worker.runOnce();

        assertThat(executorStopped[0]).isTrue(); // 执行器观察到死亡并停手
        // 本例中无人真的重领（假端口"谎报"），T2 栅栏仍认当前租约 → 记 LEASE_LOST 终态失败
        StepAttempt attempt = fx.attempts.findByStepId(item.getStepId()).get(0);
        assertThat(attempt.getStatus()).isEqualTo(AttemptStatus.FAILED_TERMINAL);
        assertThat(attempt.getErrorCode()).isEqualTo("LEASE_LOST");
    }
}
