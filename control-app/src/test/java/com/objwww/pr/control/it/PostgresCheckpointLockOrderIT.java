package com.objwww.pr.control.it;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.objwww.pr.control.alert.application.AlertClock;
import com.objwww.pr.control.alert.application.CommandService;
import com.objwww.pr.control.alert.application.RcaRunOrchestrator;
import com.objwww.pr.control.alert.application.RcaTaskExecutor;
import com.objwww.pr.control.alert.application.RunReconciler;
import com.objwww.pr.control.alert.application.agent.PrimaryCheckpointCommitService;
import com.objwww.pr.control.alert.domain.agent.PrimaryCheckpoint;
import com.objwww.pr.control.alert.domain.event.RcaEventAppender;
import com.objwww.pr.control.alert.domain.model.Incident;
import com.objwww.pr.control.alert.domain.model.IncidentStatus;
import com.objwww.pr.control.alert.domain.model.OperatorCommand;
import com.objwww.pr.control.alert.domain.model.RcaAttempt;
import com.objwww.pr.control.alert.domain.model.RcaRun;
import com.objwww.pr.control.alert.domain.model.RcaRunState;
import com.objwww.pr.control.alert.domain.model.RcaTask;
import com.objwww.pr.control.alert.domain.model.RcaTaskState;
import com.objwww.pr.control.alert.domain.model.RunTrigger;
import com.objwww.pr.control.alert.domain.repository.IncidentRepository;
import com.objwww.pr.control.alert.domain.repository.InvestigationResultRepository;
import com.objwww.pr.control.alert.domain.repository.PrimaryCheckpointRepository;
import com.objwww.pr.control.alert.domain.repository.RcaReportRepository;
import com.objwww.pr.control.alert.domain.repository.RcaRunRepository;
import com.objwww.pr.control.alert.domain.repository.RcaTaskRepository;
import com.objwww.pr.control.alert.domain.repository.RunConfigEpochRepository;
import com.objwww.pr.control.alert.domain.service.SlaPolicy;
import com.objwww.pr.control.alert.support.AlertInMemoryStores;
import com.objwww.pr.control.infrastructure.observability.AlertMetrics;
import com.objwww.pr.control.infrastructure.persistence.PostgresIncidentRepository;
import com.objwww.pr.control.infrastructure.persistence.PostgresInvestigationResultRepository;
import com.objwww.pr.control.infrastructure.persistence.PostgresNotifyOutboxRepository;
import com.objwww.pr.control.infrastructure.persistence.PostgresOperatorCommandRepository;
import com.objwww.pr.control.infrastructure.persistence.PostgresPrimaryCheckpointRepository;
import com.objwww.pr.control.infrastructure.persistence.PostgresReportPublicationRepository;
import com.objwww.pr.control.infrastructure.persistence.PostgresReportWinnerRepository;
import com.objwww.pr.control.infrastructure.persistence.PostgresRcaAttemptRepository;
import com.objwww.pr.control.infrastructure.persistence.PostgresRcaEventAppender;
import com.objwww.pr.control.infrastructure.persistence.PostgresRcaReportRepository;
import com.objwww.pr.control.infrastructure.persistence.PostgresRcaRunRepository;
import com.objwww.pr.control.infrastructure.persistence.PostgresRcaTaskRepository;
import com.objwww.pr.control.infrastructure.persistence.PostgresRcaToolCallRepository;
import com.objwww.pr.control.infrastructure.persistence.PostgresRunConfigEpochRepository;
import com.objwww.pr.control.infrastructure.persistence.PostgresSchedulerSlotRepository;
import com.objwww.pr.control.release.application.CanaryRouter;
import com.objwww.pr.shared.Digest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * WC-T07/T08（方案 v2 §8）：提交围栏换锁序（task→run，WC-1）后的真 PG 行级竞态——
 * 与 finishTask / AUTO_EXPIRE 过期 / 人工取消并发，零死锁（无 PessimisticLocking
 * FailureException / deadlock victim），结果集只含合法组合。
 *
 * <p>多轮栅栏并发（ExA2 F12 同型）：每轮新种子，PG 调度自然覆盖两种交错；断言面是
 * "零锁失败异常 + 终态在合法集内"，不指定具体胜者（并发裁决面只断不变式）。
 */
class PostgresCheckpointLockOrderIT extends PostgresITBase {

    private JdbcClient jdbc;
    private RcaTaskRepository tasks;
    private RcaRunRepository runs;
    private PostgresRcaAttemptRepository attempts;
    private IncidentRepository incidents;
    private RcaRunOrchestrator orchestrator;
    private PrimaryCheckpointRepository checkpoints;
    private PrimaryCheckpointCommitService commits;
    private RunReconciler reconciler;
    private CommandService commands;
    private final AlertInMemoryStores.Cas cas = new AlertInMemoryStores.Cas();

    @BeforeEach
    void setUp() {
        jdbc = JdbcClient.create(controlDataSource());
        tasks = new PostgresRcaTaskRepository(jdbc);
        runs = new PostgresRcaRunRepository(jdbc);
        attempts = new PostgresRcaAttemptRepository(jdbc);
        incidents = new PostgresIncidentRepository(jdbc);
        checkpoints = new PostgresPrimaryCheckpointRepository(jdbc, new ObjectMapper());
        RcaEventAppender events = new PostgresRcaEventAppender(jdbc, controlTx,
                requiresNewTx());
        orchestrator = new RcaRunOrchestrator(tasks, runs, attempts,
                new PostgresRcaReportRepository(jdbc), incidents,
                new PostgresSchedulerSlotRepository(jdbc),
                new PostgresInvestigationResultRepository(jdbc),
                new PostgresRcaToolCallRepository(jdbc),
                new com.objwww.pr.control.alert.application.ReportCompletedNotifier(
                        new PostgresReportPublicationRepository(jdbc),
                        new PostgresNotifyOutboxRepository(jdbc), List.of("test"),
                        "wc-it", 280),
                cas, SlaPolicy.defaults(), AlertClock.system(), "rca",
                AlertMetrics.NOOP, CanaryRouter.holmesOnly(),
                new PostgresReportWinnerRepository(jdbc), null);
        commits = new PrimaryCheckpointCommitService(runs, tasks, checkpoints,
                new PostgresRunConfigEpochRepository(jdbc), AlertClock.system(), controlTx);
        reconciler = new RunReconciler(runs, tasks, new PostgresRcaReportRepository(jdbc),
                new PostgresInvestigationResultRepository(jdbc), incidents, events,
                controlTx, SlaPolicy.defaults(), AlertClock.system(),
                RunReconciler.Mode.AUTO_EXPIRE, Duration.ofSeconds(30), 50);
        commands = new CommandService(new PostgresOperatorCommandRepository(jdbc), runs,
                events, controlTx, Instant::now);
    }

    // ------------------------------------------------ WC-T07：checkpoint vs finishTask

    @Test
    @DisplayName("WC-T07 commit×finishTask 10 轮栅栏并发：零死锁，合法组合（APPLIED+DEAD ∨ 围栏拒绝）")
    void commitVsFinishTaskNoDeadlock() throws Exception {
        for (int round = 0; round < 10; round++) {
            final int r = round;
            Seed seed = seed("wc-t07-finish-" + round, true);
            CyclicBarrier barrier = new CyclicBarrier(2);
            AtomicReference<Throwable> commitFailure = new AtomicReference<>();
            AtomicReference<Object> finishOutcome = new AtomicReference<>();
            AtomicReference<Throwable> finishFailure = new AtomicReference<>();
            Thread committer = Thread.ofPlatform().name("wc-commit-" + round).unstarted(() -> {
                try {
                    barrier.await(10, TimeUnit.SECONDS);
                    controlTx.executeWithoutResult(status -> commits.commit(seed.fence(),
                            "primary:" + seed.taskId + ":step:0",
                            PrimaryCheckpointCommitService.CommitMutation.STEP_COMPLETED,
                            cp -> cp.withStepAdvanced("digest-" + r, null, null,
                                    null, Instant.now())));
                } catch (PrimaryCheckpointCommitService.CommitRejectedException legal) {
                    // 围栏拒绝 = 合法竞态结果（finish 先收敛任务/Run）
                } catch (Throwable e) {
                    commitFailure.set(e);
                }
            });
            Thread finisher = Thread.ofPlatform().name("wc-finish-" + round).unstarted(() -> {
                try {
                    barrier.await(10, TimeUnit.SECONDS);
                    controlTx.executeWithoutResult(status -> finishOutcome.set(
                            orchestrator.finishTask(seed.task(), "w1", -1, -1,
                                    RcaTaskExecutor.ExecutionResult.terminal(
                                            "HTTP_5XX", "race"),
                                    seed.attempt())));
                } catch (Throwable e) {
                    finishFailure.set(e);
                }
            });
            committer.start();
            finisher.start();
            committer.join(30_000);
            finisher.join(30_000);
            assertThat(commitFailure.get()).as("round %d 提交线程零锁失败", round).isNull();
            assertThat(finishFailure.get()).as("round %d 收尾线程零锁失败", round).isNull();
            assertThat(finishOutcome.get())
                    .as("round %d finishTask 合法结局", round)
                    .isIn(RcaRunOrchestrator.FinishOutcome.DEAD,
                            RcaRunOrchestrator.FinishOutcome.STALE_GENERATION,
                            RcaRunOrchestrator.FinishOutcome.LEASE_REJECTED);
            RcaRunState finalState = runs.findById(seed.runId).orElseThrow().state();
            assertThat(finalState).as("round %d run 终态合法", round)
                    .isIn(RcaRunState.FAILED, RcaRunState.EXPIRED, RcaRunState.CANCELLED);
        }
    }

    // ------------------------------------------------ WC-T07：checkpoint vs AUTO_EXPIRE

    @Test
    @DisplayName("WC-T07 commit×AUTO_EXPIRE 10 轮栅栏并发：零死锁，EXPIRED 时任务同事务 CANCELLED")
    void commitVsAutoExpireNoDeadlock() throws Exception {
        for (int round = 0; round < 10; round++) {
            final int r = round;
            Seed seed = seed("wc-t07-expire-" + round, false);
            CyclicBarrier barrier = new CyclicBarrier(2);
            AtomicReference<Throwable> commitFailure = new AtomicReference<>();
            AtomicReference<Throwable> expireFailure = new AtomicReference<>();
            Thread committer = Thread.ofPlatform().name("wc-cexp-" + round).unstarted(() -> {
                try {
                    barrier.await(10, TimeUnit.SECONDS);
                    controlTx.executeWithoutResult(status -> commits.commit(seed.fence(),
                            "primary:" + seed.taskId + ":step:0",
                            PrimaryCheckpointCommitService.CommitMutation.STEP_COMPLETED,
                            cp -> cp.withStepAdvanced("digest-" + r, null, null,
                                    null, Instant.now())));
                } catch (PrimaryCheckpointCommitService.CommitRejectedException legal) {
                    // 过期先胜：任务 CANCELLED/Run EXPIRED → STALE_OWNER/RUN_TERMINAL
                } catch (Throwable e) {
                    commitFailure.set(e);
                }
            });
            Thread expirer = Thread.ofPlatform().name("wc-exp-" + round).unstarted(() -> {
                try {
                    barrier.await(10, TimeUnit.SECONDS);
                    reconciler.scanOnce();
                } catch (Throwable e) {
                    expireFailure.set(e);
                }
            });
            committer.start();
            expirer.start();
            committer.join(30_000);
            expirer.join(30_000);
            assertThat(commitFailure.get()).as("round %d 提交线程零锁失败", round).isNull();
            assertThat(expireFailure.get()).as("round %d 过期线程零锁失败", round).isNull();
            RcaRun run = runs.findById(seed.runId).orElseThrow();
            assertThat(run.state()).as("round %d 过期收敛", round)
                    .isIn(RcaRunState.EXPIRED, RcaRunState.RUNNING);
            if (run.state() == RcaRunState.EXPIRED) {
                assertThat(tasks.findById(seed.taskId).orElseThrow().state())
                        .as("round %d 过期同事务取消子任务", round)
                        .isEqualTo(RcaTaskState.CANCELLED);
            }
        }
    }

    // ------------------------------------------------ WC-T08：外层委派形事务 vs 取消

    @Test
    @DisplayName("WC-T08 委派形外层事务(task→run→checkAndApply→建子任务)×CANCEL：零死锁；取消先胜=零子任务")
    void delegationShapeTxVsCancelNoDeadlock() throws Exception {
        for (int round = 0; round < 6; round++) {
            final int r = round;
            Seed seed = seed("wc-t08-" + r, true);
            CyclicBarrier barrier = new CyclicBarrier(2);
            AtomicReference<Throwable> txFailure = new AtomicReference<>();
            AtomicReference<Object> cancelOutcome = new AtomicReference<>();
            AtomicReference<Throwable> cancelFailure = new AtomicReference<>();
            Thread delegator = Thread.ofPlatform().name("wc-deleg-" + round).unstarted(() -> {
                try {
                    barrier.await(10, TimeUnit.SECONDS);
                    controlTx.executeWithoutResult(status -> {
                        // adjudicate 同形（WC-1 后锁序）：task → run → checkAndApply → 建子任务
                        tasks.findByIdForUpdate(seed.taskId).orElseThrow();
                        runs.findByIdForUpdate(seed.runId).orElseThrow();
                        commits.checkAndApply(seed.fence(),
                                "primary:" + seed.taskId + ":delegate:0:1",
                                PrimaryCheckpointCommitService.CommitMutation
                                        .DELEGATION_COMMITTED,
                                cp -> cp.withPhase(PrimaryCheckpoint.Phase.WAITING_CHILDREN,
                                        Instant.now()));
                        tasks.insert(new RcaTask(UUID.randomUUID(), seed.runId,
                                "DELEGATE-gap-" + r, RcaTaskState.READY,
                                5, Instant.now(), Instant.now(), Instant.MAX,
                                null, null, 0, 0, 2, Instant.now(), Instant.now(), 1));
                    });
                } catch (PrimaryCheckpointCommitService.CommitRejectedException legal) {
                    // 取消先胜：RUN_TERMINAL 拒绝 → 外层整体回滚（含子任务建行）
                } catch (Throwable e) {
                    txFailure.set(e);
                }
            });
            Thread canceller = Thread.ofPlatform().name("wc-cancel-" + round).unstarted(() -> {
                try {
                    barrier.await(10, TimeUnit.SECONDS);
                    cancelOutcome.set(commands.submit(seed.runId,
                            OperatorCommand.Type.CANCEL, "wc-t08-" + r, 0, Map.of(),
                            "operator"));
                } catch (Throwable e) {
                    cancelFailure.set(e);
                }
            });
            delegator.start();
            canceller.start();
            delegator.join(30_000);
            canceller.join(30_000);
            assertThat(txFailure.get()).as("round %d 委派线程零锁失败", round).isNull();
            assertThat(cancelFailure.get()).as("round %d 取消线程零锁失败", round).isNull();
            RcaRunState finalState = runs.findById(seed.runId).orElseThrow().state();
            assertThat(finalState).as("round %d 终态合法", round)
                    .isIn(RcaRunState.CANCELLED, RcaRunState.RUNNING);
            if (finalState == RcaRunState.CANCELLED) {
                // 两种合法交错：取消先胜 → 委派整体回滚零子任务；委派先提交、取消
                // 后落 CAS → 子任务已铸（取消前的在飞工作），由 WC-4 终态清理通道收敛
                long delegateCount = tasks.findByRunId(seed.runId).stream()
                        .filter(t -> t.taskKey().startsWith("DELEGATE-")).count();
                assertThat(delegateCount).as("round %d 委派子任务至多一批", round)
                        .isLessThanOrEqualTo(1);
                if (delegateCount > 0) {
                    reconciler.scanOnce();
                    assertThat(tasks.findByRunId(seed.runId).stream()
                            .filter(t -> t.taskKey().startsWith("DELEGATE-"))
                            .allMatch(t -> t.state() == RcaTaskState.CANCELLED))
                            .as("round %d 终态 run 名下子任务被清理通道收敛", round).isTrue();
                }
            }
        }
    }

    // ------------------------------------------------ 种子与辅助

    /** REQUIRES_NEW 短事务（appendIndependent 用；同 PostgresRunReconcilerIT） */
    private org.springframework.transaction.support.TransactionOperations requiresNewTx() {
        org.springframework.transaction.support.TransactionTemplate template =
                new org.springframework.transaction.support.TransactionTemplate(
                        new org.springframework.jdbc.datasource.DataSourceTransactionManager(
                                controlDataSource()));
        template.setPropagationBehavior(
                org.springframework.transaction.TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        return template;
    }

    private record Seed(UUID runId, UUID taskId, RcaTask task, RcaAttempt attempt) {
        PrimaryCheckpointCommitService.CommitFence fence() {
            return new PrimaryCheckpointCommitService.CommitFence(
                    task.runId(), taskId, "w1", 1, null, 0);
        }
    }

    /** RUNNING run + LEASED 主任务（w1/epoch1）+ 初始检查点 + attempt；expire=true 时置过期硬期限 */
    private Seed seed(String tag, boolean futureDeadline) {
        Instant now = Instant.now();
        UUID incidentId = UUID.randomUUID();
        incidents.insert(new Incident(incidentId, "alertname=HighErrorRate|service=" + tag,
                IncidentStatus.FIRING, 0, now.minus(Duration.ofMinutes(5)),
                now.minus(Duration.ofMinutes(5)), null, null, null, 0, 0, 0, null,
                now.minus(Duration.ofMinutes(5)), now.minus(Duration.ofMinutes(5)), now, now));
        UUID runId = UUID.randomUUID();
        RcaRun run = new RcaRun(runId, incidentId, 0, RunTrigger.INITIAL,
                RcaRunState.RUNNING, Digest.sha256Of("wc-" + tag),
                now.minus(Duration.ofMinutes(4)), now, now, null, null);
        runs.insert(run);
        jdbc.sql("update rca_run set reconcile_deadline_at = :d where id = :id")
                .param("d", java.sql.Timestamp.from(futureDeadline
                        ? now.plusSeconds(3600) : now.minusSeconds(60)))
                .param("id", runId).update();
        UUID taskId = UUID.randomUUID();
        RcaTask task = new RcaTask(taskId, runId, RcaTask.NATIVE_INVESTIGATE,
                RcaTaskState.LEASED, 5, now, now, now.plusSeconds(3600), "w1",
                now.plusSeconds(600), 1, 0, 3, now, now);
        tasks.insert(task);
        checkpoints.upsert(PrimaryCheckpoint.initial(taskId, runId, 0, now));
        RcaAttempt attempt = new RcaAttempt(UUID.randomUUID(), taskId, 1, 1, "w1",
                com.objwww.pr.control.alert.domain.model.RcaAttemptStatus.STARTED,
                null, null, null, now, null, null);
        attempts.insert(attempt);
        return new Seed(runId, taskId, task,
                attempts.findByTaskId(taskId).get(0));
    }
}
