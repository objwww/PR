package com.objwww.pr.control.alert.application;

import com.objwww.pr.control.alert.domain.model.ExternalInvocation;
import com.objwww.pr.control.alert.domain.model.ExternalInvocationState;
import com.objwww.pr.control.alert.domain.model.Incident;
import com.objwww.pr.control.alert.domain.model.InboxState;
import com.objwww.pr.control.alert.domain.model.RcaAttempt;
import com.objwww.pr.control.alert.domain.model.RcaAttemptStatus;
import com.objwww.pr.control.alert.domain.model.RcaReport;
import com.objwww.pr.control.alert.domain.model.RcaRun;
import com.objwww.pr.control.alert.domain.model.RcaRunState;
import com.objwww.pr.control.alert.domain.model.RcaTask;
import com.objwww.pr.control.alert.domain.model.RcaTaskState;
import com.objwww.pr.control.alert.domain.model.RunTrigger;
import com.objwww.pr.control.alert.domain.model.ValidationStatus;
import com.objwww.pr.control.alert.domain.repository.SchedulerSlotRepository;
import com.objwww.pr.control.alert.domain.service.AlertIdentityFactory;
import com.objwww.pr.control.alert.domain.service.DeferredPolicy;
import com.objwww.pr.control.alert.domain.service.SlaPolicy;
import com.objwww.pr.control.alert.support.AlertInMemoryStores;
import com.objwww.pr.control.alert.support.TestFixtures;
import com.objwww.pr.shared.Digest;
import com.objwww.pr.shared.Digests;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionOperations;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Optional;
import java.util.Queue;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * T06 场景闭环：RcaWorker 领取/执行/收尾/恢复 + RcaRunOrchestrator.finishTask 四分支
 * （ST-A06）+ 旧 epoch 拒写（ST-A08）。ST 系列用 InMemory + withoutTransaction；
 * 并发/真 PG 语义归 CT-A02~A08（195）。
 */
class RcaWorkerTest {

    private static final class MutableClock implements AlertClock {
        volatile Instant now = Instant.parse("2026-09-03T10:00:00Z");

        @Override
        public Instant now() {
            return now;
        }
    }

    /** 剧本执行器：按序出结果，可注入报告；记录心跳调用 */
    private static final class ScriptedExecutor implements RcaTaskExecutor {
        final Queue<ExecutionResult> script = new ArrayDeque<>();
        int heartbeatCalls;

        @Override
        public ExecutionResult execute(RcaTask task, RcaRun run, Incident incident,
                                       RcaAttempt attempt, Runnable heartbeat) {
            heartbeat.run();
            heartbeatCalls++;
            ExecutionResult polled = script.poll();
            return polled != null ? polled
                    : RcaTaskExecutor.ExecutionResult.retryable("SCRIPT_EMPTY", "剧本耗尽");
        }

        void succeedNext() {
            script.add(RcaTaskExecutor.ExecutionResult.success(
                    new RcaTaskExecutor.ReportContent(1, ValidationStatus.STRUCTURE_VALIDATED,
                            List.of(), "{\"schema_version\":\"1\"}", "raw", "deepseek-v3",
                            null, null, null, true)));
        }

        void failRetryableNext() {
            script.add(RcaTaskExecutor.ExecutionResult.retryable("HTTP_5XX", "holmes 500"));
        }
    }

    private AlertInMemoryStores stores;
    private MutableClock clock;
    private ScriptedExecutor executor;
    private AlertInboxProcessor intake;
    private RcaWorker worker;
    private RcaRunOrchestrator orchestrator;

    @BeforeEach
    void setUp() {
        stores = new AlertInMemoryStores();
        clock = new MutableClock();
        executor = new ScriptedExecutor();
        IncidentProjector projector = new IncidentProjector(stores.events, stores.incidents,
                stores.runs, stores.tasks, new AlertIdentityFactory(),
                new DeferredPolicy(1000), SlaPolicy.defaults(), clock);
        intake = new AlertInboxProcessor(stores.inbox, projector,
                TransactionOperations.withoutTransaction(), clock, "intake-owner",
                Duration.ofMinutes(2), Duration.ofSeconds(30), Duration.ofSeconds(10),
                Duration.ofSeconds(1));
        orchestrator = new RcaRunOrchestrator(stores.tasks, stores.runs, stores.attempts,
                stores.reports, stores.incidents, stores.slots, SlaPolicy.defaults(),
                clock, "rca");
        worker = newWorker("worker-a");
    }

    private RcaWorker newWorker(String owner) {
        return new RcaWorker(stores.tasks, stores.runs, stores.attempts, stores.incidents,
                stores.slots, stores.invocations, executor, orchestrator,
                TransactionOperations.withoutTransaction(), clock, owner, "rca",
                Duration.ofMinutes(5), Duration.ofSeconds(30), Duration.ofSeconds(1),
                Duration.ofMinutes(1), Duration.ofMinutes(10));
    }

    /** 投一组告警（经真实投影链路铸 incident/run/task） */
    private void deliver(String service, String severity, String status,
                         String startsAt, String summary) {
        stores.inbox.insert(TestFixtures.inboxRowOf(UUID.randomUUID(), TestFixtures.amGroup(
                "g-" + service, 0,
                TestFixtures.alertJson("HighErrorRate", service, severity, status,
                        startsAt, summary))));
        assertThat(intake.processOnce()).isEqualTo(AlertInboxProcessor.Outcome.ACCEPTED);
    }

    private void deliverFiring(String service, String severity, String startsAt, String summary) {
        deliver(service, severity, "firing", startsAt, summary);
    }

    /** 六段式通过的报告内容样本（finishTask 手动路径复用） */
    private static RcaTaskExecutor.ReportContent staticReportContent() {
        return new RcaTaskExecutor.ReportContent(1, ValidationStatus.STRUCTURE_VALIDATED,
                List.of(), "{\"schema_version\":\"1\"}", "raw", "deepseek-v3",
                null, null, null, true);
    }

    private Incident soleIncidentOf(String service) {
        String key = "alertname=HighErrorRate|service=" + service;
        return stores.incidents.all().stream()
                .filter(i -> i.incidentKey().equals(key)).findFirst().orElseThrow();
    }

    // ------------------------------------------------------------------ ST-A06 分支 3：材料未变

    @Test
    @DisplayName("ST-A06 分支3 成功且材料未变：run SUCCEEDED + 材料锚定 + 槽释放")
    void stA06_branch3_materialUnchanged() {
        deliverFiring("checkout", "warning", "2026-09-03T09:00:00Z", "材料一");
        executor.succeedNext();

        assertThat(worker.runOneCycle()).isEqualTo(RcaWorker.CycleOutcome.EXECUTED);

        RcaRun run = stores.runs.all().get(0);
        assertThat(run.state()).isEqualTo(RcaRunState.SUCCEEDED);
        assertThat(run.startedAt()).isNotNull();
        assertThat(run.finishedAt()).isNotNull();

        RcaTask task = stores.tasks.all().get(0);
        assertThat(task.state()).isEqualTo(RcaTaskState.DONE);
        assertThat(stores.attempts.all()).hasSize(1);
        assertThat(stores.attempts.all().get(0).status()).isEqualTo(RcaAttemptStatus.SUCCEEDED);

        Incident incident = soleIncidentOf("checkout");
        assertThat(incident.currentRcaRunId()).isNull();
        assertThat(incident.pendingInvestigationHash()).isNull();
        assertThat(incident.lastInvestigationHash()).isEqualTo(run.investigationHash());
        assertThat(stores.slots.occupiedSlots("rca")).isEmpty();   // INV-AM1-7 槽归还
        assertThat(stores.reports.all()).hasSize(1);
    }

    // ------------------------------------------------------------------ ST-A06 分支 2：材料变化 → RERUN

    @Test
    @DisplayName("ST-A06 分支2 调查期间材料变化：run1 SUCCEEDED + 只派生一个 RERUN run")
    void stA06_branch2_materialChangedCastsSingleRerun() {
        deliverFiring("checkout", "warning", "2026-09-03T09:00:00Z", "材料一");
        // ST-A05 前半：调查中（QUEUED/RUNNING 即活跃）连续材料变化只写 pending
        deliverFiring("checkout", "warning", "2026-09-03T09:01:00Z", "材料二");
        deliverFiring("checkout", "warning", "2026-09-03T09:02:00Z", "材料三");
        assertThat(stores.runs.all()).hasSize(1);   // 活跃期间零新铸

        executor.succeedNext();
        assertThat(worker.runOneCycle()).isEqualTo(RcaWorker.CycleOutcome.EXECUTED);

        assertThat(stores.runs.all()).hasSize(2);
        RcaRun first = stores.runs.all().get(0);
        RcaRun rerun = stores.runs.all().get(1);
        assertThat(first.state()).isEqualTo(RcaRunState.SUCCEEDED);
        assertThat(rerun.trigger()).isEqualTo(RunTrigger.RERUN);
        assertThat(rerun.state()).isEqualTo(RcaRunState.QUEUED);
        assertThat(rerun.generation()).isEqualTo(first.generation());   // RERUN 同 episode 代
        assertThat(stores.tasks.all()).hasSize(2);
        assertThat(stores.tasks.all().get(1).state()).isEqualTo(RcaTaskState.READY);

        Incident incident = soleIncidentOf("checkout");
        assertThat(incident.currentRcaRunId()).isEqualTo(rerun.id());
        assertThat(incident.pendingInvestigationHash()).isEqualTo(rerun.investigationHash());

        // 后续 run2 照常可被领取执行（RERUN 材料成为新快照）
        executor.succeedNext();
        clock.now = clock.now.plusSeconds(1);
        assertThat(worker.runOneCycle()).isEqualTo(RcaWorker.CycleOutcome.EXECUTED);
        assertThat(stores.runs.findByIdForUpdate(rerun.id()).orElseThrow().state())
                .isEqualTo(RcaRunState.SUCCEEDED);
    }

    // ------------------------------------------------------------------ ST-A06 分支 1：已恢复 → 短路

    @Test
    @DisplayName("ST-A06 分支1 调查完成时 incident 已 RESOLVED：清 rerun 线索不新铸")
    void stA06_branch1_resolvedShortCircuit() {
        deliverFiring("checkout", "warning", "2026-09-03T09:00:00Z", "材料一");
        // worker 领取（调查中）
        Optional<RcaWorker.ClaimedWork> work = worker.claimWork();
        assertThat(work).isPresent();
        // 调查期间告警恢复（投影只翻 incident 状态，不动活跃 run）
        deliver("checkout", "warning", "resolved", "2026-09-03T09:00:00Z", "材料一");

        RcaAttempt attempt = new RcaAttempt(UUID.randomUUID(), work.get().task().id(),
                work.get().task().attemptCount(), work.get().task().leaseEpoch(), "worker-a",
                RcaAttemptStatus.STARTED, null, null, null, clock.now, null);
        stores.attempts.insert(attempt);

        RcaRunOrchestrator.FinishOutcome outcome = orchestrator.finishTask(
                work.get().task(), "worker-a", work.get().slotNo(), work.get().slotEpoch(),
                RcaTaskExecutor.ExecutionResult.success(staticReportContent()), attempt);

        assertThat(outcome).isEqualTo(RcaRunOrchestrator.FinishOutcome.RESOLVED_SHORT_CIRCUIT);
        assertThat(stores.runs.all()).hasSize(1);   // 不铸 RERUN
        assertThat(stores.runs.all().get(0).state()).isEqualTo(RcaRunState.SUCCEEDED);
        Incident incident = soleIncidentOf("checkout");
        assertThat(incident.status()).isEqualTo(com.objwww.pr.control.alert.domain.model.IncidentStatus.RESOLVED);
        assertThat(incident.pendingInvestigationHash()).isNull();   // clearRerun
        assertThat(incident.currentRcaRunId()).isNull();
        assertThat(stores.slots.occupiedSlots("rca")).isEmpty();
    }

    // ------------------------------------------------------------------ 失败路径

    @Test
    @DisplayName("可重试失败：task RETRY_WAIT 退避、run 保持活跃；耗尽后 DEAD + run FAILED")
    void failureRetriesThenDead() {
        deliverFiring("checkout", "critical", "2026-09-03T09:00:00Z", "材料一");

        // 三连失败（maxAttempts=3）
        executor.failRetryableNext();
        assertThat(worker.runOneCycle()).isEqualTo(RcaWorker.CycleOutcome.EXECUTED);
        RcaTask afterFirst = stores.tasks.all().get(0);
        assertThat(afterFirst.state()).isEqualTo(RcaTaskState.RETRY_WAIT);
        assertThat(stores.runs.all().get(0).state()).isEqualTo(RcaRunState.RUNNING);
        assertThat(stores.slots.occupiedSlots("rca")).isEmpty();

        clock.now = clock.now.plus(Duration.ofMinutes(2));   // 越过退避
        executor.failRetryableNext();
        assertThat(worker.runOneCycle()).isEqualTo(RcaWorker.CycleOutcome.EXECUTED);
        assertThat(stores.attempts.all()).hasSize(2);

        clock.now = clock.now.plus(Duration.ofMinutes(4));
        executor.failRetryableNext();
        assertThat(worker.runOneCycle()).isEqualTo(RcaWorker.CycleOutcome.EXECUTED);

        assertThat(stores.tasks.all().get(0).state()).isEqualTo(RcaTaskState.DEAD);
        assertThat(stores.runs.all().get(0).state()).isEqualTo(RcaRunState.FAILED);
        assertThat(soleIncidentOf("checkout").currentRcaRunId()).isNull();
        assertThat(stores.attempts.all()).hasSize(3);
        assertThat(stores.attempts.all().get(2).status()).isEqualTo(RcaAttemptStatus.FAILED_RETRYABLE);
    }

    @Test
    @DisplayName("重试后成功：最终 SUCCEEDED，报告留存")
    void retryThenSuccess() {
        deliverFiring("checkout", "info", "2026-09-03T09:00:00Z", "材料一");
        executor.failRetryableNext();
        assertThat(worker.runOneCycle()).isEqualTo(RcaWorker.CycleOutcome.EXECUTED);

        clock.now = clock.now.plus(Duration.ofMinutes(2));
        executor.succeedNext();
        assertThat(worker.runOneCycle()).isEqualTo(RcaWorker.CycleOutcome.EXECUTED);

        assertThat(stores.runs.all().get(0).state()).isEqualTo(RcaRunState.SUCCEEDED);
        assertThat(stores.tasks.all().get(0).state()).isEqualTo(RcaTaskState.DONE);
        assertThat(stores.attempts.all()).hasSize(2);
        assertThat(stores.reports.all()).hasSize(1);
    }

    // ------------------------------------------------------------------ ST-A08 旧 epoch 拒写

    @Test
    @DisplayName("ST-A08 旧 epoch worker 晚到 finishTask：栅栏拒写，零落库")
    void stA08_staleEpochRejected() {
        deliverFiring("checkout", "warning", "2026-09-03T09:00:00Z", "材料一");

        // worker-a 领取后崩溃（拿到租约快照）
        Optional<RcaWorker.ClaimedWork> crashed = worker.claimWork();
        assertThat(crashed).isPresent();
        RcaTask staleTask = crashed.get().task();

        // 租约过期 → 回收 → worker-b 重领（epoch+1）
        clock.now = clock.now.plus(Duration.ofMinutes(6));
        assertThat(worker.recoverExpired()).isEqualTo(1);
        assertThat(stores.tasks.all().get(0).state()).isEqualTo(RcaTaskState.RETRY_WAIT);
        clock.now = clock.now.plus(Duration.ofMinutes(2));

        executor.succeedNext();
        RcaWorker workerB = newWorker("worker-b");
        assertThat(workerB.runOneCycle()).isEqualTo(RcaWorker.CycleOutcome.EXECUTED);

        // worker-a 携旧 epoch 晚到提交：被拒，一行不写
        RcaRunOrchestrator staleOrchestrator = new RcaRunOrchestrator(stores.tasks,
                stores.runs, stores.attempts, stores.reports, stores.incidents, stores.slots,
                SlaPolicy.defaults(), clock, "rca");
        RcaAttempt staleAttempt = new RcaAttempt(UUID.randomUUID(), staleTask.id(),
                staleTask.attemptCount(), staleTask.leaseEpoch(), "worker-a",
                RcaAttemptStatus.STARTED, null, null, null, clock.now, null);
        RcaRunOrchestrator.FinishOutcome rejected = staleOrchestrator.finishTask(
                staleTask, "worker-a", crashed.get().slotNo(), crashed.get().slotEpoch(),
                RcaTaskExecutor.ExecutionResult.success(staticReportContent()), staleAttempt);

        assertThat(rejected).isEqualTo(RcaRunOrchestrator.FinishOutcome.LEASE_REJECTED);
        // worker-b 的结果原样保留（重新查行，勿用领取时的旧引用）
        assertThat(stores.runs.all().get(0).state()).isEqualTo(RcaRunState.SUCCEEDED);
        assertThat(stores.tasks.all().get(0).state()).isEqualTo(RcaTaskState.DONE);
        assertThat(stores.attempts.all()).hasSize(1);   // 旧 worker 的 attempt 未落
    }

    // ------------------------------------------------------------------ 恢复扫描（崩溃双回收 + 悬挂账本）

    @Test
    @DisplayName("恢复扫描：过期 task → RETRY_WAIT、slot 过期回收、悬挂账本 STARTED→UNKNOWN")
    void recoverExpiredReclaimsTaskSlotAndLedger() {
        deliverFiring("checkout", "warning", "2026-09-03T09:00:00Z", "材料一");
        Optional<RcaWorker.ClaimedWork> work = worker.claimWork();
        assertThat(work).isPresent();

        // 悬挂账本（worker 崩溃前来不及写终态；startedAt 早于宽限）
        Digest digest = new Digest(Digests.sha256Hex("req"));
        stores.invocations.insertStarted(new ExternalInvocation(UUID.randomUUID(),
                UUID.randomUUID(), 1, work.get().run().id(), work.get().task().id(),
                UUID.randomUUID(), work.get().task().leaseEpoch(),
                "http://holmes:8080/api/chat", digest, null,
                ExternalInvocationState.STARTED, null, null, null, null, null, false,
                null, null, null, null, null,
                clock.now.minus(Duration.ofMinutes(30)), null));

        clock.now = clock.now.plus(Duration.ofMinutes(6));   // 双租约都过期
        assertThat(worker.recoverExpired()).isEqualTo(1);

        assertThat(stores.tasks.all().get(0).state()).isEqualTo(RcaTaskState.RETRY_WAIT);
        assertThat(stores.slots.occupiedSlots("rca")).isEmpty();
        assertThat(stores.invocations.all()).hasSize(1);
        assertThat(stores.invocations.all().get(0).state())
                .isEqualTo(ExternalInvocationState.UNKNOWN);
        assertThat(stores.invocations.all().get(0).finishedAt()).isNotNull();
    }

    // ------------------------------------------------------------------ slot 并发语义

    @Test
    @DisplayName("槽满：SLOTS_BUSY 不领任务；无任务：IDLE 且不占槽")
    void slotSaturation() {
        // 无任务：IDLE 且槽即领即还
        assertThat(worker.runOneCycle()).isEqualTo(RcaWorker.CycleOutcome.IDLE);
        assertThat(stores.slots.occupiedSlots("rca")).isEmpty();

        // 手动占满 2 槽 + 有任务 → SLOTS_BUSY，任务不被领走
        assertThat(stores.slots.tryAcquire("rca", "alien", null, clock.now,
                Duration.ofMinutes(5))).isPresent();
        assertThat(stores.slots.tryAcquire("rca", "alien", null, clock.now,
                Duration.ofMinutes(5))).isPresent();
        deliverFiring("checkout", "warning", "2026-09-03T09:00:00Z", "材料一");
        assertThat(worker.runOneCycle()).isEqualTo(RcaWorker.CycleOutcome.SLOTS_BUSY);
        assertThat(stores.tasks.all().get(0).state()).isEqualTo(RcaTaskState.READY);

        // 槽租约过期回收后可继续执行
        clock.now = clock.now.plus(Duration.ofMinutes(6));
        stores.slots.reclaimExpired(clock.now);
        executor.succeedNext();
        assertThat(worker.runOneCycle()).isEqualTo(RcaWorker.CycleOutcome.EXECUTED);
    }

    @Test
    @DisplayName("SLA 排序：critical 与 info 同队，先领 critical")
    void claimOrderFollowsSla() {
        deliverFiring("cart", "info", "2026-09-03T09:00:00Z", "材料甲");
        deliverFiring("checkout", "critical", "2026-09-03T09:00:00Z", "材料乙");

        Optional<RcaWorker.ClaimedWork> work = worker.claimWork();
        assertThat(work).isPresent();
        assertThat(work.get().incident().incidentKey()).contains("checkout");
    }
}
