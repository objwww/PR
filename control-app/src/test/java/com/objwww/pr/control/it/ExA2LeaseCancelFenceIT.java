package com.objwww.pr.control.it;

import com.objwww.pr.control.alert.application.CommandService;
import com.objwww.pr.control.alert.application.RcaRunOrchestrator;
import com.objwww.pr.control.alert.application.RcaTaskExecutor;
import com.objwww.pr.control.alert.domain.event.RcaEventAppender;
import com.objwww.pr.control.alert.domain.model.Incident;
import com.objwww.pr.control.alert.domain.model.IncidentStatus;
import com.objwww.pr.control.alert.domain.model.OperatorCommand;
import com.objwww.pr.control.alert.domain.model.RcaAttempt;
import com.objwww.pr.control.alert.domain.model.RcaAttemptStatus;
import com.objwww.pr.control.alert.domain.model.RcaRun;
import com.objwww.pr.control.alert.domain.model.RcaRunState;
import com.objwww.pr.control.alert.domain.model.RcaTask;
import com.objwww.pr.control.alert.domain.model.RcaTaskState;
import com.objwww.pr.control.alert.domain.model.RunTrigger;
import com.objwww.pr.control.alert.domain.repository.RcaRunRepository;
import com.objwww.pr.control.alert.domain.repository.RcaTaskRepository;
import com.objwww.pr.control.alert.domain.service.SlaPolicy;
import com.objwww.pr.control.alert.support.AlertInMemoryStores;
import com.objwww.pr.control.infrastructure.persistence.PostgresIncidentRepository;
import com.objwww.pr.control.infrastructure.persistence.PostgresRcaAttemptRepository;
import com.objwww.pr.control.infrastructure.persistence.PostgresRcaEventAppender;
import com.objwww.pr.control.infrastructure.persistence.PostgresRcaReportRepository;
import com.objwww.pr.control.infrastructure.persistence.PostgresRcaRunRepository;
import com.objwww.pr.control.infrastructure.persistence.PostgresRcaTaskRepository;
import com.objwww.pr.control.infrastructure.persistence.PostgresReportWinnerRepository;
import com.objwww.pr.control.infrastructure.persistence.PostgresInvestigationResultRepository;
import com.objwww.pr.control.infrastructure.persistence.PostgresRcaToolCallRepository;
import com.objwww.pr.control.infrastructure.persistence.PostgresReportPublicationRepository;
import com.objwww.pr.control.infrastructure.persistence.PostgresNotifyOutboxRepository;
import com.objwww.pr.control.infrastructure.persistence.PostgresOperatorCommandRepository;
import com.objwww.pr.control.infrastructure.persistence.PostgresSchedulerSlotRepository;
import com.objwww.pr.control.release.application.CanaryRouter;
import com.objwww.pr.control.infrastructure.observability.AlertMetrics;
import com.objwww.pr.shared.Digest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;

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
 * EX-A2 验收（L1，真 PG）：F10/F11/F12 租约·心跳·取消 + 提交栅栏
 * （docs/告警-EXA2-租约与提交栅栏.md §4）。
 *
 * <p>钉五件事（行级/竞态级可核）：
 * <ul>
 *   <li>F10 四条件原子回收：胜出（RETRY_WAIT+清租约+epoch 不动）/epoch 已进 0 行/
 *       心跳已续 0 行/已收敛 0 行；</li>
 *   <li>F11 两连接序：A 回收→B 重领→A 提交 = LEASE_REJECTED 一行不写
 *       （报告/调查终态零行）；</li>
 *   <li>F12 Cancel vs finishTask 真并发（双线程栅栏）：结果一致——
 *       run FAILED+取消拒 ∨ run CANCELLED+STALE_GENERATION，无中间态；</li>
 *   <li>取消后零新动作资格（claimNext 空）+ markRunRunning CAS 败不复活；</li>
 *   <li>双取消同修订号：一真一拒，事件恰一条。</li>
 * </ul>
 */
class ExA2LeaseCancelFenceIT extends PostgresITBase {

    private static final Instant NOW = Instant.parse("2026-09-10T02:00:00Z");

    private RcaTaskRepository tasks;
    private RcaRunRepository runs;
    private PostgresRcaAttemptRepository attempts;
    private PostgresIncidentRepository incidents;
    private RcaRunOrchestrator orchestrator;
    private CommandService commands;

    @BeforeEach
    void setUp() {
        JdbcClient jdbc = JdbcClient.create(controlDataSource());
        tasks = new PostgresRcaTaskRepository(jdbc);
        runs = new PostgresRcaRunRepository(jdbc);
        attempts = new PostgresRcaAttemptRepository(jdbc);
        incidents = new PostgresIncidentRepository(jdbc);
        TransactionTemplate requiresNew = new TransactionTemplate(
                new org.springframework.jdbc.datasource.DataSourceTransactionManager(
                        controlDataSource()));
        requiresNew.setPropagationBehavior(
                org.springframework.transaction.TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        RcaEventAppender appender = new PostgresRcaEventAppender(jdbc, controlTx, requiresNew);
        commands = new CommandService(new PostgresOperatorCommandRepository(jdbc), runs,
                appender, Instant::now);
        orchestrator = new RcaRunOrchestrator(tasks, runs, attempts,
                new PostgresRcaReportRepository(jdbc), incidents,
                new PostgresSchedulerSlotRepository(jdbc),
                new PostgresInvestigationResultRepository(jdbc),
                new PostgresRcaToolCallRepository(jdbc),
                new com.objwww.pr.control.alert.application.ReportCompletedNotifier(
                        new PostgresReportPublicationRepository(jdbc),
                        new PostgresNotifyOutboxRepository(jdbc),
                        List.of("test"), "exa2-it", 280),
                new AlertInMemoryStores.Cas(), SlaPolicy.defaults(),
                com.objwww.pr.control.alert.application.AlertClock.system(), "rca",
                AlertMetrics.NOOP, CanaryRouter.holmesOnly(),
                new PostgresReportWinnerRepository(jdbc));
    }

    // ------------------------------------------------ F10：四条件原子回收

    @Test
    @DisplayName("F10 回收四条件：epoch 不符 0 行 / 胜出清租约 epoch 不动 / 已收敛 0 行 / 心跳已续 0 行")
    void reclaimExpiredConditionalSemantics() {
        Seed seed = seedLeasedTask("exa2-f10", "worker-a", 3,
                NOW.minusSeconds(60), RcaRunState.RUNNING);
        Instant readyAt = NOW.plusSeconds(60);

        // epoch 不符（已他人重领镜像）→ 0 行
        assertThat(tasks.reclaimExpired(seed.taskId(), 99, NOW,
                RcaTaskState.RETRY_WAIT, readyAt)).isFalse();
        assertThat(tasks.findById(seed.taskId()).orElseThrow().state())
                .isEqualTo(RcaTaskState.LEASED);

        // 胜出：RETRY_WAIT + 租约列清空 + epoch/attempt 不动 + 退避 available_at
        assertThat(tasks.reclaimExpired(seed.taskId(), 3, NOW,
                RcaTaskState.RETRY_WAIT, readyAt)).isTrue();
        RcaTask reclaimed = tasks.findById(seed.taskId()).orElseThrow();
        assertThat(reclaimed.state()).isEqualTo(RcaTaskState.RETRY_WAIT);
        assertThat(reclaimed.leaseOwner()).isNull();
        assertThat(reclaimed.leaseUntil()).isNull();
        assertThat(reclaimed.leaseEpoch()).isEqualTo(3);
        assertThat(reclaimed.availableAt()).isEqualTo(readyAt);

        // 已收敛（state≠LEASED）再回收 → 0 行（他回收者已胜镜像）
        assertThat(tasks.reclaimExpired(seed.taskId(), 3, NOW,
                RcaTaskState.RETRY_WAIT, readyAt)).isFalse();

        // 心跳已续 → 0 行（lease_until<now 复核在条件写内）
        Seed live = seedLeasedTask("exa2-f10-live", "worker-a", 1,
                NOW.plusSeconds(300), RcaRunState.RUNNING);
        assertThat(tasks.reclaimExpired(live.taskId(), 1, NOW,
                RcaTaskState.RETRY_WAIT, readyAt)).isFalse();
        assertThat(tasks.findById(live.taskId()).orElseThrow().state())
                .isEqualTo(RcaTaskState.LEASED);
    }

    // ------------------------------------------------ F11：A 回收/B 领取/A 提交=0 行

    @Test
    @DisplayName("F11 两连接序：A 失租被 B 回收重领后晚到提交 → LEASE_REJECTED，报告/调查终态零行")
    void reclaimedTaskRejectsStaleWorkerCommit() {
        Seed seed = seedLeasedTask("exa2-f11", "worker-a", 2,
                NOW.minusSeconds(60), RcaRunState.RUNNING);
        // worker-a 的提交材料 = 其领取时的任务快照（owner/epoch 均为旧值）
        RcaTask staleSnapshot = tasks.findById(seed.taskId()).orElseThrow();

        // worker-b 原子回收 + 重领（epoch+1、易主）
        assertThat(tasks.reclaimExpired(seed.taskId(), 2, NOW,
                RcaTaskState.RETRY_WAIT, NOW)).isTrue();
        tasks.claimNext("worker-b", NOW.plusSeconds(60), Duration.ofMinutes(5));
        RcaTask heldByB = tasks.findById(seed.taskId()).orElseThrow();
        assertThat(heldByB.state()).isEqualTo(RcaTaskState.LEASED);
        assertThat(heldByB.leaseOwner()).isEqualTo("worker-b");
        assertThat(heldByB.leaseEpoch()).isEqualTo(3);

        // worker-a 携旧快照（epoch=2）晚到提交：栅栏 0 行，一行不写
        RcaAttempt staleAttempt = new RcaAttempt(UUID.randomUUID(), seed.taskId(), 1, 2,
                "worker-a", RcaAttemptStatus.STARTED, null, null, null, NOW, null, null);
        RcaRunOrchestrator.FinishOutcome outcome = orchestrator.finishTask(
                staleSnapshot, "worker-a", -1, -1,
                RcaTaskExecutor.ExecutionResult.terminal("HTTP_5XX", "late response"),
                staleAttempt);

        assertThat(outcome).isEqualTo(RcaRunOrchestrator.FinishOutcome.LEASE_REJECTED);
        assertThat(count("rca_report")).as("晚到结果零报告").isZero();
        assertThat(count("rca_investigation_result")).as("晚到结果不进有效证据面").isZero();
        assertThat(tasks.findById(seed.taskId()).orElseThrow().leaseOwner())
                .isEqualTo("worker-b");
    }

    // ------------------------------------------------ F12：Cancel vs finish 真并发

    @Test
    @DisplayName("F12 竞态一致性：取消与 finishTask 双线程并发 → FAILED+取消拒 ∨ CANCELLED+STALE，无中间态")
    void cancelVsFinishRaceIsConsistent() throws Exception {
        Seed seed = seedLeasedTask("exa2-race", "worker-a", 1, NOW.plusSeconds(300),
                RcaRunState.RUNNING);
        attempts.insert(new RcaAttempt(UUID.randomUUID(), seed.taskId(), 1, 1, "worker-a",
                RcaAttemptStatus.STARTED, null, null, null, NOW, null, null));
        RcaTask task = tasks.findById(seed.taskId()).orElseThrow();
        RcaRun run = runs.findById(seed.runId()).orElseThrow();
        RcaAttempt attempt = attempts.findByTaskId(seed.taskId()).get(0);

        CyclicBarrier barrier = new CyclicBarrier(2);
        AtomicReference<Object> finishOutcome = new AtomicReference<>();
        AtomicReference<Object> cancelOutcome = new AtomicReference<>();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread finisher = Thread.ofPlatform().name("finisher").unstarted(() -> {
            try {
                barrier.await(10, TimeUnit.SECONDS);
                controlTx.executeWithoutResult(status -> finishOutcome.set(
                        orchestrator.finishTask(task, "worker-a", -1, -1,
                                RcaTaskExecutor.ExecutionResult.terminal("HTTP_5XX", "boom"),
                                attempt)));
            } catch (Throwable e) {
                failure.set(e);
            }
        });
        Thread canceller = Thread.ofPlatform().name("canceller").unstarted(() -> {
            try {
                barrier.await(10, TimeUnit.SECONDS);
                cancelOutcome.set(commands.submit(seed.runId(), OperatorCommand.Type.CANCEL,
                        "op-1", 0, Map.of(), "operator"));
            } catch (Throwable e) {
                failure.set(e);
            }
        });
        finisher.start();
        canceller.start();
        finisher.join(30_000);
        canceller.join(30_000);
        assertThat(failure.get()).as("竞态双方零异常").isNull();

        RcaRunState finalState = runs.findById(seed.runId()).orElseThrow().state();
        if (finalState == RcaRunState.FAILED) {
            // finish 先胜：取消必须被拒（修订/活跃态栅栏），不得覆盖终态
            assertThat(finishOutcome.get()).isEqualTo(RcaRunOrchestrator.FinishOutcome.DEAD);
            CommandService.Result cancel = (CommandService.Result) cancelOutcome.get();
            assertThat(cancel.state()).isIn(OperatorCommand.State.REJECTED_STALE,
                    OperatorCommand.State.REJECTED_FORBIDDEN);
        } else if (finalState == RcaRunState.CANCELLED) {
            // 取消先胜：晚到 finish 由 generation fence 收敛 STALE（零报告落档）
            assertThat(cancelOutcome.get())
                    .satisfies(r -> assertThat(((CommandService.Result) r).state())
                            .isEqualTo(OperatorCommand.State.APPLIED));
            assertThat(finishOutcome.get())
                    .isEqualTo(RcaRunOrchestrator.FinishOutcome.STALE_GENERATION);
        } else {
            throw new AssertionError("竞态终态不在一致集内: " + finalState);
        }
        // 两分支共同不变量：attempt 已诚实落终态（费用/账面对账锚）
        assertThat(attempts.findByTaskId(seed.taskId()).get(0).finishedAt()).isNotNull();
    }

    // ------------------------------------------------ F12：取消后零新动作资格

    @Test
    @DisplayName("取消生效后：claimNext 零资格、markRunRunning CAS 败不复活、RUN_CANCELLED 事件恰一条")
    void cancelledRunGrantsNoNewActionEligibility() {
        Seed seed = seedLeasedTask("exa2-cancel", "worker-a", 1, NOW.plusSeconds(300),
                RcaRunState.QUEUED);
        // 一条可领取的 RETRY_WAIT 任务（取消后不得再取得资格）
        RcaTask retryable = new RcaTask(UUID.randomUUID(), seed.runId(),
                RcaTask.HOLMES_INVESTIGATE, RcaTaskState.RETRY_WAIT, 5, NOW, NOW,
                NOW.plusSeconds(3600), null, null, 0, 1, 3, NOW, NOW);
        tasks.insert(retryable);

        // 资格在场对照：run 活跃（QUEUED）时可领任务确实可领取（排除任务自身不可领的假阳性）
        assertThat(tasks.claimNext("worker-pre", NOW, Duration.ofMinutes(5)))
                .as("取消前资格在场").isPresent();

        CommandService.Result cancel = commands.submit(seed.runId(),
                OperatorCommand.Type.CANCEL, "op-1", 0, Map.of(), "operator");
        assertThat(cancel.state()).isEqualTo(OperatorCommand.State.APPLIED);
        assertThat(runs.findById(seed.runId()).orElseThrow().state())
                .isEqualTo(RcaRunState.CANCELLED);

        // 取消事务成功后不得取得新动作发送资格：可领任务被 run 活跃谓词挡住
        assertThat(tasks.claimNext("worker-b", NOW, Duration.ofMinutes(5)))
                .as("取消后零新领取资格").isEmpty();
        // worker 迟到开跑：CAS 锚取消前修订号（0）→ 0 行，run 不复活
        assertThat(orchestrator.markRunRunning(
                runs.findById(seed.runId()).orElseThrow(), 0, NOW)).isFalse();
        assertThat(runs.findById(seed.runId()).orElseThrow().state())
                .isEqualTo(RcaRunState.CANCELLED);

        assertThat(count("rca_event")).as("RUN_CANCELLED 事件恰一条").isEqualTo(1);
    }

    // ------------------------------------------------ F12：双取消一真一拒

    @Test
    @DisplayName("双取消同修订号（异幂等键）：第一张 APPLIED 推进修订，第二张 REJECTED_STALE")
    void doubleCancelSecondIsStale() {
        Seed seed = seedLeasedTask("exa2-double-cancel", "worker-a", 1,
                NOW.plusSeconds(300), RcaRunState.RUNNING);

        CommandService.Result first = commands.submit(seed.runId(),
                OperatorCommand.Type.CANCEL, "op-1", 0, Map.of(), "operator-1");
        CommandService.Result second = commands.submit(seed.runId(),
                OperatorCommand.Type.CANCEL, "op-2", 0, Map.of(), "operator-2");

        assertThat(first.state()).isEqualTo(OperatorCommand.State.APPLIED);
        assertThat(second.state()).isEqualTo(OperatorCommand.State.REJECTED_STALE);
        // 修订锚推进 = CAS +1 与 RUN_CANCELLED 事件追加 +1（M4-10 计数器同事务推进）
        assertThat(runs.currentRevision(seed.runId()).orElseThrow()).isGreaterThan(0L);
        assertThat(count("rca_event")).isEqualTo(1);
        assertThat(runs.findById(seed.runId()).orElseThrow().state())
                .isEqualTo(RcaRunState.CANCELLED);
    }

    // ------------------------------------------------------------------ 种子

    private record Seed(UUID incidentId, UUID runId, UUID taskId) {}

    private Seed seedLeasedTask(String tag, String owner, long epoch, Instant leaseUntil,
                                RcaRunState runState) {
        UUID incidentId = UUID.randomUUID();
        Instant now = Instant.now();
        incidents.insert(new Incident(incidentId, "alertname=HighErrorRate|service=" + tag,
                IncidentStatus.FIRING, 0, now.minus(Duration.ofMinutes(5)),
                now.minus(Duration.ofMinutes(5)), null, null, null, 0, 0, 0, null,
                now.minus(Duration.ofMinutes(5)), now.minus(Duration.ofMinutes(5)), now, now));
        RcaRun run = new RcaRun(UUID.randomUUID(), incidentId, 0, RunTrigger.INITIAL,
                RcaRunState.QUEUED, Digest.sha256Of("run-" + tag),
                now.minus(Duration.ofMinutes(4)), now, null, null, null);
        runs.insert(run);
        if (runState != RcaRunState.QUEUED) {
            runs.update(new RcaRun(run.id(), run.incidentId(), run.generation(), run.trigger(),
                    runState, run.investigationHash(), run.createdAt(), now, now, null, null));
        }
        RcaTask task = new RcaTask(UUID.randomUUID(), run.id(), RcaTask.NATIVE_INVESTIGATE,
                RcaTaskState.LEASED, 5, NOW.minusSeconds(120), NOW.minusSeconds(120),
                now.plusSeconds(3600), owner, leaseUntil, epoch, 1, 3, now, now);
        tasks.insert(task);
        return new Seed(incidentId, run.id(), task.id());
    }
}
