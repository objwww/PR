package com.objwww.pr.control.alert.application;

import com.objwww.pr.control.alert.domain.model.ExternalInvocation;
import com.objwww.pr.control.alert.domain.model.ExternalInvocationState;
import com.objwww.pr.control.alert.domain.model.Incident;
import com.objwww.pr.control.alert.domain.model.InboxState;
import com.objwww.pr.control.alert.domain.model.RcaAttempt;
import com.objwww.pr.control.infrastructure.observability.AlertMetrics;
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
import com.objwww.pr.control.alert.domain.tool.RcaToolInvocationLedger;
import com.objwww.pr.control.alert.domain.tool.ToolInvocationState;
import com.objwww.pr.control.alert.domain.tool.ToolReasonCode;
import com.objwww.pr.control.alert.support.AlertInMemoryStores;
import com.objwww.pr.control.alert.support.TestFixtures;
import com.objwww.pr.control.release.application.CanaryRouter;
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
            script.add(RcaTaskExecutor.ExecutionResult.success(validatedArtifact()));
        }

        void failRetryableNext() {
            script.add(RcaTaskExecutor.ExecutionResult.retryable("HTTP_5XX", "holmes 500"));
        }

        /** REJECTED_* 同权落档路径：终态失败但携完整响应现场（INV-AM3-7） */
        void rejectWithArtifactNext() {
            script.add(RcaTaskExecutor.ExecutionResult.terminalWithArtifact("REJECTED_MALFORMED",
                    "缺 analysis", new RcaTaskExecutor.AttemptArtifact(2,
                            ValidationStatus.REJECTED_MALFORMED, List.of("缺 analysis"),
                            null, "plain text", null, List.of(),
                            Digest.sha256Of("plain text"), null, "deepseek-v3",
                            null, null, null, true, null)));
        }
    }

    private AlertInMemoryStores stores;
    private MutableClock clock;
    private ScriptedExecutor executor;
    private AlertInboxProcessor intake;
    private RcaWorker worker;
    private RcaRunOrchestrator orchestrator;
    private ReportCompletedNotifier notifier;
    private CanaryRouter nativeRouter;

    @BeforeEach
    void setUp() {
        stores = new AlertInMemoryStores();
        clock = new MutableClock();
        executor = new ScriptedExecutor();
        notifier = new ReportCompletedNotifier(stores.publications, stores.outboxes,
                List.of("test"), "am3-candidate-v1", 280);
        // M6-07：fixture 迁 Native 唯一引擎面（percent=100 全桶 NATIVE；原 holmesOnly
        // 路由下的 HOLMES 投影已不铸 run——C-77）
        NativeEngineWiringTest.WiringBundles bundles = new NativeEngineWiringTest.WiringBundles();
        bundles.publish(canaryBundle100());
        nativeRouter = new CanaryRouter(bundles,
                new NativeEngineWiringTest.WiringDecisions(), true, clock::now);
        IncidentProjector projector = new IncidentProjector(stores.events, stores.incidents,
                stores.runs, stores.tasks, new AlertIdentityFactory(),
                new DeferredPolicy(1000), SlaPolicy.defaults(), clock, nativeRouter,
                new com.objwww.pr.control.alert.domain.classification.IncidentClassifier(),
                stores.categories);
        intake = new AlertInboxProcessor(stores.inbox, projector,
                TransactionOperations.withoutTransaction(), clock, "intake-owner",
                Duration.ofMinutes(2), Duration.ofSeconds(30), Duration.ofSeconds(10),
                Duration.ofSeconds(1));
        orchestrator = newOrchestrator();
        worker = newWorker("worker-a");
    }

    private RcaRunOrchestrator newOrchestrator() {
        return new RcaRunOrchestrator(stores.tasks, stores.runs, stores.attempts,
                stores.reports, stores.incidents, stores.slots, stores.investigations,
                stores.toolCalls, notifier, stores.cas, SlaPolicy.defaults(), clock, "rca",
                AlertMetrics.NOOP, nativeRouter, stores.winners);
    }

    private RcaWorker newWorker(String owner) {
        return new RcaWorker(stores.tasks, stores.runs, stores.attempts, stores.investigations,
                stores.incidents, stores.slots, stores.invocations, stores.toolLedger,
                java.util.Map.of(com.objwww.pr.control.alert.domain.model.RcaEngine.NATIVE,
                        executor),
                orchestrator, TransactionOperations.withoutTransaction(), clock, owner, "rca",
                Duration.ofMinutes(5), Duration.ofSeconds(30), Duration.ofSeconds(1),
                Duration.ofMinutes(1), Duration.ofMinutes(10), 2);
    }

    /** percent=100 canary 段（全桶 NATIVE 意愿；NativeEngineWiringTest.canaryBundle 同构） */
    private static java.util.Map<String, Object> canaryBundle100() {
        java.util.Map<String, Object> canary = new java.util.LinkedHashMap<>();
        canary.put("percent", 100);
        canary.put("whitelist", List.of());
        canary.put("max_native_runs", 100);
        java.util.Map<String, Object> content = new java.util.LinkedHashMap<>();
        content.put("policy_version", "policy-2026-09");
        content.put("canary", canary);
        return content;
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

    /** 六段式通过的结构验证样本（finishTask 手动路径复用；工具调用为空、usage 缺失） */
    private static RcaTaskExecutor.AttemptArtifact staticArtifact() {
        return validatedArtifact();
    }

    private static RcaTaskExecutor.AttemptArtifact validatedArtifact() {
        return new RcaTaskExecutor.AttemptArtifact(1, ValidationStatus.STRUCTURE_VALIDATED,
                List.of(), "{\"schema_version\":\"1\"}", "raw", null, List.of(),
                null, null, "deepseek-v3", null, null, null, true, null);
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

        // M3-04~08 落档链：STARTED 先行 → 终态 CAS + 报告 + publication + outbox + CAS 原文
        assertThat(stores.investigations.all()).hasSize(1);
        var investigation = stores.investigations.all().get(0);
        assertThat(investigation.id()).isEqualTo(stores.attempts.all().get(0).id());   // 1:1 锚
        assertThat(investigation.executionStatus())
                .isEqualTo(com.objwww.pr.control.alert.domain.model.ExecutionStatus.SUCCEEDED);
        assertThat(investigation.validationStatus()).isEqualTo(ValidationStatus.STRUCTURE_VALIDATED);
        assertThat(investigation.observedGeneration()).isEqualTo(run.generation());
        assertThat(investigation.finishedAt()).isNotNull();
        assertThat(stores.publications.all()).hasSize(1);
        assertThat(stores.outboxes.all()).hasSize(1);   // 默认单渠道 test
        assertThat(stores.cas.size()).isEqualTo(1);     // 脱敏原文内容寻址落 CAS
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
                RcaAttemptStatus.STARTED, null, null, null, clock.now, null, null);
        stores.attempts.insert(attempt);

        RcaRunOrchestrator.FinishOutcome outcome = orchestrator.finishTask(
                work.get().task(), "worker-a", work.get().slotNo(), work.get().slotEpoch(),
                RcaTaskExecutor.ExecutionResult.success(staticArtifact()), attempt);

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
        RcaRunOrchestrator staleOrchestrator = newOrchestrator();
        RcaAttempt staleAttempt = new RcaAttempt(UUID.randomUUID(), staleTask.id(),
                staleTask.attemptCount(), staleTask.leaseEpoch(), "worker-a",
                RcaAttemptStatus.STARTED, null, null, null, clock.now, null, null);
        RcaRunOrchestrator.FinishOutcome rejected = staleOrchestrator.finishTask(
                staleTask, "worker-a", crashed.get().slotNo(), crashed.get().slotEpoch(),
                RcaTaskExecutor.ExecutionResult.success(staticArtifact()), staleAttempt);

        assertThat(rejected).isEqualTo(RcaRunOrchestrator.FinishOutcome.LEASE_REJECTED);
        // worker-b 的结果原样保留（重新查行，勿用领取时的旧引用）
        assertThat(stores.runs.all().get(0).state()).isEqualTo(RcaRunState.SUCCEEDED);
        assertThat(stores.tasks.all().get(0).state()).isEqualTo(RcaTaskState.DONE);
        assertThat(stores.attempts.all()).hasSize(1);   // 旧 worker 的 attempt 未落
    }

    // ------------------------------------------------------------------ M4-07 generation fence

    @Test
    @DisplayName("M4-07 generation fence：run SUPERSEDED 后旧 worker 提交 → task STALE，结果零落档不污染新 run")
    void generationFence_staleFinishDoesNotPolluteNewRun() {
        deliverFiring("checkout", "warning", "2026-09-03T09:00:00Z", "材料一");
        Optional<RcaWorker.ClaimedWork> work = worker.claimWork();
        assertThat(work).isPresent();
        RcaTask held = work.get().task();

        // incident 代际前进：旧 run 出活跃集（SUPERSEDED），新 run gen+1 上位（uq 允许）
        RcaRun oldRun = stores.runs.findByIdForUpdate(work.get().run().id()).orElseThrow();
        stores.runs.update(new RcaRun(oldRun.id(), oldRun.incidentId(), oldRun.generation(),
                oldRun.trigger(), RcaRunState.SUPERSEDED, oldRun.investigationHash(),
                oldRun.createdAt(), clock.now, oldRun.startedAt(), clock.now, null));
        RcaRun newRun = new RcaRun(UUID.randomUUID(), oldRun.incidentId(), oldRun.generation() + 1,
                RunTrigger.INITIAL, RcaRunState.QUEUED, oldRun.investigationHash(),
                clock.now, clock.now, null, null, null);
        stores.runs.insert(newRun);

        RcaAttempt attempt = new RcaAttempt(UUID.randomUUID(), held.id(), held.attemptCount(),
                held.leaseEpoch(), "worker-a", RcaAttemptStatus.STARTED, null, null, null,
                clock.now, null, null);
        stores.attempts.insert(attempt);
        RcaRunOrchestrator.FinishOutcome outcome = orchestrator.finishTask(held, "worker-a",
                work.get().slotNo(), work.get().slotEpoch(),
                RcaTaskExecutor.ExecutionResult.success(staticArtifact()), attempt);

        assertThat(outcome).isEqualTo(RcaRunOrchestrator.FinishOutcome.STALE_GENERATION);
        assertThat(stores.tasks.findById(held.id()).orElseThrow().state())
                .as("旧代结果只能 STALE（INV-AM4-4）").isEqualTo(RcaTaskState.STALE);
        assertThat(stores.reports.all()).as("旧代结果不产报告").isEmpty();
        assertThat(stores.publications.all()).isEmpty();
        assertThat(stores.outboxes.all()).isEmpty();
        assertThat(stores.investigations.all())
                .as("旧代结果不落调查终态（merge 面栅栏）").isEmpty();
        assertThat(stores.runs.findById(newRun.id()).orElseThrow().state())
                .as("新 run 不受污染").isEqualTo(RcaRunState.QUEUED);
        assertThat(stores.slots.occupiedSlots("rca")).as("槽正常归还").isEmpty();
    }

    @Test
    @DisplayName("M4-07 generation fence：崩溃回收遇死 run → STALE 不重排（不复活死 run 的工作）")
    void generationFence_recoverDoesNotRequeueSupersededRun() {
        deliverFiring("checkout", "warning", "2026-09-03T09:00:00Z", "材料一");
        Optional<RcaWorker.ClaimedWork> work = worker.claimWork();
        assertThat(work).isPresent();

        RcaRun oldRun = stores.runs.findByIdForUpdate(work.get().run().id()).orElseThrow();
        stores.runs.update(new RcaRun(oldRun.id(), oldRun.incidentId(), oldRun.generation(),
                oldRun.trigger(), RcaRunState.SUPERSEDED, oldRun.investigationHash(),
                oldRun.createdAt(), clock.now, oldRun.startedAt(), clock.now, null));

        clock.now = clock.now.plus(Duration.ofMinutes(6));   // 租约过期
        assertThat(worker.recoverExpired()).isEqualTo(1);
        assertThat(stores.tasks.all().get(0).state())
                .as("死 run 的过期租约 → STALE，不走 RETRY_WAIT 重排")
                .isEqualTo(RcaTaskState.STALE);
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

    // ------------------------------------------------------------------ EX-A2（F10/F12）租约与提交栅栏

    @Test
    @DisplayName("EX-A2 F10：reclaimExpired 四条件语义——epoch 不符 0 行；胜出清租约且 epoch 不动；已收敛再回收 0 行")
    void reclaimExpiredConditionalSemantics() {
        deliverFiring("checkout", "warning", "2026-09-03T09:00:00Z", "材料一");
        Optional<RcaWorker.ClaimedWork> work = worker.claimWork();
        assertThat(work).isPresent();
        RcaTask held = work.get().task();
        Instant readyAt = clock.now.plus(Duration.ofMinutes(1));

        // epoch 不符（已被他人重领的镜像）→ 0 行竞态失败
        assertThat(stores.tasks.reclaimExpired(held.id(), held.leaseEpoch() + 5, clock.now,
                RcaTaskState.RETRY_WAIT, readyAt)).isFalse();
        assertThat(stores.tasks.findById(held.id()).orElseThrow().state())
                .isEqualTo(RcaTaskState.LEASED);

        // epoch 相符且已过期 → 回收胜：RETRY_WAIT、租约列清空、epoch/attempt 不动
        clock.now = clock.now.plus(Duration.ofMinutes(6));
        assertThat(stores.tasks.reclaimExpired(held.id(), held.leaseEpoch(), clock.now,
                RcaTaskState.RETRY_WAIT, readyAt)).isTrue();
        RcaTask reclaimed = stores.tasks.findById(held.id()).orElseThrow();
        assertThat(reclaimed.state()).isEqualTo(RcaTaskState.RETRY_WAIT);
        assertThat(reclaimed.leaseOwner()).isNull();
        assertThat(reclaimed.leaseUntil()).isNull();
        assertThat(reclaimed.leaseEpoch()).isEqualTo(held.leaseEpoch());
        assertThat(reclaimed.availableAt()).isEqualTo(readyAt);
        assertThat(reclaimed.attemptCount()).isEqualTo(held.attemptCount());

        // 已收敛（state≠LEASED）再回收 → 0 行（他回收者已胜的镜像）
        assertThat(stores.tasks.reclaimExpired(held.id(), held.leaseEpoch(), clock.now,
                RcaTaskState.RETRY_WAIT, readyAt)).isFalse();
    }

    @Test
    @DisplayName("EX-A2 F10：心跳已续的租约不回收（lease_until<now 复核在条件写内）")
    void reclaimLosesWhenHeartbeatRenewedLease() {
        deliverFiring("checkout", "warning", "2026-09-03T09:00:00Z", "材料一");
        Optional<RcaWorker.ClaimedWork> work = worker.claimWork();
        assertThat(work).isPresent();
        RcaTask held = work.get().task();

        // 原 5 分钟租约在 T+6 已过期，但 worker 在 T+3 心跳续到 T+8——T+6 时刻不得被回收
        clock.now = clock.now.plus(Duration.ofMinutes(3));
        stores.tasks.heartbeat(held.id(), "worker-a", held.leaseEpoch(), clock.now,
                Duration.ofMinutes(5));
        clock.now = clock.now.plus(Duration.ofMinutes(3));

        assertThat(stores.tasks.reclaimExpired(held.id(), held.leaseEpoch(), clock.now,
                RcaTaskState.RETRY_WAIT, clock.now.plus(Duration.ofMinutes(1)))).isFalse();
        assertThat(stores.tasks.findById(held.id()).orElseThrow().state())
                .as("活租约不得被回收夺走").isEqualTo(RcaTaskState.LEASED);
        assertThat(worker.recoverExpired()).as("恢复扫描对续期租约零回收").isZero();
    }

    @Test
    @DisplayName("EX-A2 F12：取消在领取与开跑之间落地 → markRunRunning CAS 败，run 不复活")
    void markRunRunningLosesCasWhenCancelledAfterClaim() {
        deliverFiring("checkout", "warning", "2026-09-03T09:00:00Z", "材料一");
        Optional<RcaWorker.ClaimedWork> work = worker.claimWork();
        assertThat(work).isPresent();
        RcaRun run = work.get().run();

        // 模拟取消事务先胜：QUEUED→CANCELLED 的修订条件写成功（修订号 0→1）
        assertThat(stores.runs.updateIfRevision(new RcaRun(run.id(), run.incidentId(),
                run.generation(), run.trigger(), RcaRunState.CANCELLED, run.investigationHash(),
                run.createdAt(), clock.now, run.startedAt(), clock.now, null),
                work.get().revision())).isTrue();

        // worker 迟到开跑：CAS 锚旧修订号 → 0 行，run 保持 CANCELLED 不复活
        assertThat(orchestrator.markRunRunning(work.get().run(), work.get().revision(),
                clock.now)).isFalse();
        assertThat(stores.runs.findById(run.id()).orElseThrow().state())
                .isEqualTo(RcaRunState.CANCELLED);
    }

    // ------------------------------------------------------------------ M3-04/08 落档语义

    @Test
    @DisplayName("INV-AM3-7：REJECTED_* 终态同权落档——无报告无通知，但有调查终态与 CAS 原文")
    void rejectedArtifactStillArchived() {
        deliverFiring("checkout", "warning", "2026-09-03T09:00:00Z", "材料一");
        executor.rejectWithArtifactNext();

        assertThat(worker.runOneCycle()).isEqualTo(RcaWorker.CycleOutcome.EXECUTED);

        // 结构违约 = 终态：task DEAD + run FAILED
        assertThat(stores.tasks.all().get(0).state()).isEqualTo(RcaTaskState.DEAD);
        assertThat(stores.runs.all().get(0).state()).isEqualTo(RcaRunState.FAILED);

        // 但调查记录诚实落档：FAILED + REJECTED_MALFORMED，无 packageJson
        assertThat(stores.investigations.all()).hasSize(1);
        var investigation = stores.investigations.all().get(0);
        assertThat(investigation.executionStatus())
                .isEqualTo(com.objwww.pr.control.alert.domain.model.ExecutionStatus.FAILED);
        assertThat(investigation.validationStatus())
                .isEqualTo(ValidationStatus.REJECTED_MALFORMED);
        assertThat(investigation.packageJson()).isNull();
        assertThat(investigation.rawDigest()).isNotNull();

        // 验证失败不产报告/通知；脱敏原文仍进 CAS
        assertThat(stores.reports.all()).isEmpty();
        assertThat(stores.publications.all()).isEmpty();
        assertThat(stores.outboxes.all()).isEmpty();
        assertThat(stores.cas.size()).isEqualTo(1);
    }

    @Test
    @DisplayName("M3-04：悬挂调查记录（STARTED 后崩溃）由恢复扫描标 UNKNOWN，不阻断 task 回收")
    void hangingInvestigationMarkedUnknownOnRecovery() {
        deliverFiring("checkout", "warning", "2026-09-03T09:00:00Z", "材料一");
        // 人工制造"STARTED 已落、终态未达"的悬挂行（模拟收尾事务前被杀；不动 task 状态）
        stores.investigations.insertStartedIfAbsent(com.objwww.pr.control.alert.domain.model
                .InvestigationResult.started(UUID.randomUUID(), UUID.randomUUID(),
                        UUID.randomUUID(), 0, 2, null, clock.now));

        clock.now = clock.now.plus(Duration.ofMinutes(20));   // 越过悬挂宽限（10m）
        assertThat(worker.recoverExpired()).isEqualTo(0);     // 无过期 LEASED task

        var recovered = stores.investigations.all().get(0);
        assertThat(recovered.executionStatus())
                .isEqualTo(com.objwww.pr.control.alert.domain.model.ExecutionStatus.UNKNOWN);
        assertThat(recovered.validationStatus()).isEqualTo(ValidationStatus.NOT_VALIDATED);
        assertThat(recovered.finishedAt()).isNotNull();
    }

    // ------------------------------------------------------------------ EX-A4a（F16）工具调用账本悬挂回收

    @Test
    @DisplayName("EX-A4a F16：恢复扫描把宽限外 PENDING 工具账本行 UNKNOWN 化（孤儿回执诚实归档），宽限内在途不动")
    void recoverExpiredSweepsPendingToolLedgerRowsToUnknown() {
        UUID agedOp = UUID.randomUUID();
        stores.toolLedger.open(new RcaToolInvocationLedger.InvocationIdentity(agedOp,
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), 1,
                "prometheus.query", "1", Digests.sha256Hex("aged")));
        stores.toolLedger.agePending(agedOp, clock.now.minus(Duration.ofMinutes(20)));
        UUID freshOp = UUID.randomUUID();
        stores.toolLedger.open(new RcaToolInvocationLedger.InvocationIdentity(freshOp,
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), 2,
                "prometheus.query", "1", Digests.sha256Hex("fresh")));

        assertThat(worker.recoverExpired()).as("无过期 task，纯账本扫描").isZero();

        var swept = stores.toolLedger.rows.get(agedOp);
        assertThat(swept.state).isEqualTo(ToolInvocationState.UNKNOWN);
        assertThat(swept.reason).isEqualTo(ToolReasonCode.TRANSPORT_UNKNOWN);
        assertThat(swept.settledAt).isNotNull();
        var inFlight = stores.toolLedger.rows.get(freshOp);
        assertThat(inFlight.state).as("宽限内在途调用不被误杀")
                .isEqualTo(ToolInvocationState.PENDING);
        assertThat(inFlight.reason).isNull();
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
