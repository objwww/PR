package com.objwww.pr.control.alert.application;

import com.objwww.pr.control.alert.domain.model.ExecutionStatus;
import com.objwww.pr.control.alert.domain.model.Incident;
import com.objwww.pr.control.alert.domain.model.IncidentStatus;
import com.objwww.pr.control.alert.domain.model.InvestigationResult;
import com.objwww.pr.control.alert.domain.model.RcaAttempt;
import com.objwww.pr.control.alert.domain.model.RcaAttemptStatus;
import com.objwww.pr.control.alert.domain.model.RcaRun;
import com.objwww.pr.control.alert.domain.model.RcaRunState;
import com.objwww.pr.control.alert.domain.model.RcaTask;
import com.objwww.pr.control.alert.domain.model.RcaTaskState;
import com.objwww.pr.control.alert.domain.model.RcaEngine;
import com.objwww.pr.control.alert.domain.model.RunPurpose;
import com.objwww.pr.control.alert.domain.model.RunTrigger;
import com.objwww.pr.control.alert.domain.model.ValidationStatus;
import com.objwww.pr.control.alert.domain.service.SlaPolicy;
import com.objwww.pr.control.alert.support.AlertInMemoryStores;
import com.objwww.pr.control.infrastructure.observability.AlertMetrics;
import com.objwww.pr.control.release.application.CanaryRouter;
import com.objwww.pr.shared.Digest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionOperations;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SR §3.2/§4.3 收口面单测：
 * <ul>
 *   <li>SR01 负向门：影子身份 Run 即便走到 finishTask（复用路径串入），发布准入层
 *       拒绝正式报告/发布/通知——材料照档；不依赖"调用者不调用 notifier"约定；</li>
 *   <li>SR06（UT 面）：材料预提交后、收尾事务前"崩溃"（孤立 REPORTING + 终态材料）→
 *       RunReconciler 铸唯一 REPORT_FINALIZE → worker 领取 → ReportFinalizeExecutor
 *       只组既有材料走同一 finishTask 发布链——报告/发布/outbox 恰一份，重复扫描不重复；</li>
 *   <li>MATERIALS_INCOMPLETE：finalize 铸出但 raw 不可读（CAS 缺 blob）→ 终态失败
 *       收口 run FAILED，不重跑调查。</li>
 * </ul>
 */
class ReportFinalizeRecoveryTest {

    private static final Instant NOW = Instant.parse("2026-09-13T10:00:00Z");

    private final AlertInMemoryStores stores = new AlertInMemoryStores();
    private RcaRunOrchestrator orchestrator;
    private ReportCompletedNotifier notifier;
    private RcaWorker worker;
    private RunReconciler reconciler;

    @BeforeEach
    void setUp() {
        notifier = new ReportCompletedNotifier(stores.publications, stores.outboxes,
                List.of("test"), "am3-candidate-v1", 280);
        // canary percent=100 全桶 NATIVE（同 RcaWorkerTest.canaryBundle100）
        NativeEngineWiringTest.WiringBundles bundles = new NativeEngineWiringTest.WiringBundles();
        Map<String, Object> canary = new java.util.LinkedHashMap<>();
        canary.put("percent", 100);
        canary.put("whitelist", List.of());
        canary.put("max_native_runs", 100);
        Map<String, Object> content = new java.util.LinkedHashMap<>();
        content.put("policy_version", "policy-2026-09");
        content.put("canary", canary);
        bundles.publish(content);
        CanaryRouter router = new CanaryRouter(bundles,
                new NativeEngineWiringTest.WiringDecisions(), true, () -> NOW);
        orchestrator = new RcaRunOrchestrator(stores.tasks, stores.runs, stores.attempts,
                stores.reports, stores.incidents, stores.slots, stores.investigations,
                stores.toolCalls, notifier, stores.cas, SlaPolicy.defaults(), () -> NOW,
                "rca", AlertMetrics.NOOP, router, stores.winners, null);
        ReportFinalizeExecutor finalizer = new ReportFinalizeExecutor(stores.reports,
                stores.investigations, stores.cas);
        worker = new RcaWorker(stores.tasks, stores.runs, stores.attempts, stores.investigations,
                stores.incidents, stores.slots, stores.invocations, stores.toolLedger,
                Map.of(RcaEngine.NATIVE, new NoopEngineExecutor()),
                finalizer, orchestrator, TransactionOperations.withoutTransaction(), () -> NOW,
                "worker-a", "rca", Duration.ofMinutes(5), Duration.ofSeconds(30),
                Duration.ofSeconds(1), Duration.ofMinutes(1), Duration.ofMinutes(10), 2,
                org.mockito.Mockito.mock(RunConfigSwitchService.class), null);
        reconciler = new RunReconciler(stores.runs, stores.tasks, stores.reports,
                stores.investigations, stores.incidents, stores.rcaEvents,
                TransactionOperations.withoutTransaction(), SlaPolicy.defaults(),
                () -> NOW, RunReconciler.Mode.SAFE_RECOVER, Duration.ofSeconds(30), 50);
    }

    /** 引擎执行不应发生（driver 已终态/恢复面只组材料）——发生即测试失败 */
    private static final class NoopEngineExecutor implements RcaTaskExecutor {
        @Override
        public ExecutionResult execute(RcaTask task, RcaRun run, Incident incident,
                                       RcaAttempt attempt, Runnable heartbeat) {
            throw new IllegalStateException("恢复用例不应触发引擎执行: " + task.taskKey());
        }
    }

    // ------------------------------------------------------------------ SR01 负向门

    @Test
    @DisplayName("SR01 影子身份串入 finishTask：发布准入层拒报告/发布/通知，材料照档")
    void shadowRunCannotProduceReportEvenViaFinishTaskReuse() {
        RcaRun shadow = insertRun(RcaRunState.RUNNING, RunPurpose.SHADOW);
        RcaTask task = insertLeasedDriver(shadow.id());
        RcaAttempt attempt = insertStartedAttempt(task);

        orchestrator.finishTask(task, "w1", -1, -1,
                RcaTaskExecutor.ExecutionResult.success(validatedArtifact()), attempt);

        // 负向门：零正式报告/零发布/零 outbox/零发布权（不依赖调用者不调用 notifier 的约定）
        assertThat(stores.reports.all()).isEmpty();
        assertThat(stores.publications.all()).isEmpty();
        assertThat(stores.outboxes.all()).isEmpty();
        assertThat(stores.winners.size()).isZero();
        // 材料照档（影子取证即目的）：调查终态行带验证通过与 package
        InvestigationResult row = stores.investigations.findByAttemptId(attempt.id()).orElseThrow();
        assertThat(row.validationStatus()).isEqualTo(ValidationStatus.STRUCTURE_VALIDATED);
        assertThat(row.packageJson()).isNotNull();
        // 身份不被收尾改写
        assertThat(stores.runs.findById(shadow.id()).orElseThrow().purpose())
                .isEqualTo(RunPurpose.SHADOW);
    }

    // ------------------------------------------------------------------ SR06 恢复链（UT 面）

    @Test
    @DisplayName("SR06 材料已提交+收尾事务前崩溃：唯一 finalizer 恢复，报告/发布/outbox 恰一份")
    void committedMaterialsSurviveCrashAndFinalizeExactlyOnce() {
        // 形状：执行器完成调查并预提交材料后、finishTask 提交前进程被杀——
        // driver 已终态（DEAD）、run 仍 REPORTING、终态调查行+CAS raw 在库
        RcaRun run = insertRun(RcaRunState.REPORTING, RunPurpose.PRODUCTION);
        insertDeadDriver(run.id());
        commitMaterials(run.id(), UUID.randomUUID(), "raw-evidence", true);

        // ① 对账（SAFE_RECOVER）识别可重入收尾 → 铸唯一 REPORT_FINALIZE
        assertThat(reconciler.scanOnce()).isEqualTo(1);
        RcaTask finalize = onlyFinalizeTask(run.id());
        assertThat(finalize.state()).isEqualTo(RcaTaskState.READY);
        assertThat(finalize.priority()).isEqualTo(9);

        // ② worker 领取 finalize（REPORT_FINALIZE 可领 + 生产 run 活跃）→
        //    只组既有材料（不触发引擎执行）→ 同一 finishTask 发布链
        assertThat(worker.runOneCycle()).isEqualTo(RcaWorker.CycleOutcome.EXECUTED);

        assertThat(stores.reports.all()).hasSize(1);
        assertThat(stores.reports.all().get(0).runId()).isEqualTo(run.id());
        assertThat(stores.reports.all().get(0).packageJson()).contains("summary");
        assertThat(stores.publications.all()).hasSize(1);
        assertThat(stores.outboxes.all()).hasSize(1);
        assertThat(stores.winners.size()).isEqualTo(1);
        assertThat(stores.runs.findById(run.id()).orElseThrow().state())
                .isEqualTo(RcaRunState.SUCCEEDED);
        assertThat(onlyFinalizeTask(run.id()).state()).isEqualTo(RcaTaskState.DONE);

        // ③ 重复扫描：run 已终态 → 非候选，零新动作零重复
        assertThat(reconciler.scanOnce()).isZero();
        assertThat(stores.reports.all()).hasSize(1);
        assertThat(stores.publications.all()).hasSize(1);
        assertThat(stores.outboxes.all()).hasSize(1);
    }

    @Test
    @DisplayName("SR06 raw 不可读：finalize 终态失败 MATERIALS_INCOMPLETE，run FAILED 不重跑调查")
    void finalizeWithUnreadableRawFailsTerminalInsteadOfReinvestigating() {
        // 材料行验证通过（对账按行判定可铸 finalize），但 raw 原文从未进 CAS
        // （错位/丢失）——finalize 执行期发现不可读即终态失败，不凭推测补跑
        RcaRun run = insertRun(RcaRunState.REPORTING, RunPurpose.PRODUCTION);
        insertDeadDriver(run.id());
        commitMaterials(run.id(), UUID.randomUUID(), "ghost", false);

        assertThat(reconciler.scanOnce()).isEqualTo(1);
        assertThat(worker.runOneCycle()).isEqualTo(RcaWorker.CycleOutcome.EXECUTED);

        assertThat(onlyFinalizeTask(run.id()).state()).isEqualTo(RcaTaskState.DEAD);
        assertThat(stores.runs.findById(run.id()).orElseThrow().state())
                .isEqualTo(RcaRunState.FAILED);
        assertThat(stores.reports.all()).isEmpty();
        assertThat(stores.publications.all()).isEmpty();
        assertThat(stores.outboxes.all()).isEmpty();
    }

    // ------------------------------------------------------------------ 基座

    private RcaRun insertRun(RcaRunState state, RunPurpose purpose) {
        UUID incidentId = UUID.randomUUID();
        UUID runId = UUID.randomUUID();
        stores.incidents.insert(new Incident(incidentId, "k-" + incidentId,
                IncidentStatus.FIRING, 3, NOW.minusSeconds(3600), NOW.minusSeconds(3600),
                null, Digest.sha256Of("inv"), null, 1, 1, 0, runId, NOW.minusSeconds(3600),
                NOW, NOW.minusSeconds(3600), NOW));
        RcaRun run = new RcaRun(runId, incidentId, 3, RunTrigger.INITIAL, state,
                Digest.sha256Of("inv"), NOW.minusSeconds(3600), NOW.minusSeconds(3500),
                NOW.minusSeconds(3500), null, null, purpose, "ut", null);
        stores.runs.insert(run);
        return run;
    }

    private RcaTask insertLeasedDriver(UUID runId) {
        RcaTask task = new RcaTask(UUID.randomUUID(), runId, RcaTask.NATIVE_INVESTIGATE,
                RcaTaskState.LEASED, 3, NOW.minusSeconds(600), NOW.minusSeconds(600),
                NOW.plusSeconds(3600), "w1", NOW.plusSeconds(300), 5, 1, 3,
                NOW.minusSeconds(600), NOW, 0);
        stores.tasks.insert(task);
        return task;
    }

    private void insertDeadDriver(UUID runId) {
        stores.tasks.insert(new RcaTask(UUID.randomUUID(), runId, RcaTask.NATIVE_INVESTIGATE,
                RcaTaskState.DEAD, 3, NOW.minusSeconds(3600), NOW.minusSeconds(3600),
                NOW.plusSeconds(3600), null, null, 0, 3, 3, NOW.minusSeconds(3600),
                NOW.minusSeconds(60), 0));
    }

    private RcaAttempt insertStartedAttempt(RcaTask task) {
        RcaAttempt attempt = new RcaAttempt(UUID.randomUUID(), task.id(), task.attemptCount(),
                task.leaseEpoch(), "w1", RcaAttemptStatus.STARTED, null, null, null,
                NOW.minusSeconds(600), null, null);
        stores.attempts.insert(attempt);
        stores.investigations.insertStartedIfAbsent(InvestigationResult.started(
                attempt.id(), attempt.id(), task.runId(), 3, 2, null, NOW.minusSeconds(600)));
        return attempt;
    }

    /**
     * 材料预提交面（RcaWorker 收尾事务前独立落）：验证通过终态调查行 + CAS raw。
     *
     * @param withRaw true = raw 原文真实落 CAS（digest 复核可过）；
     *                false = 行携带合法 hex ref 但 CAS 无 blob（执行期不可读）
     */
    private void commitMaterials(UUID runId, UUID attemptId, String rawText, boolean withRaw) {
        Digest rawDigest = Digest.sha256Of(rawText);
        String ref = withRaw
                ? stores.cas.putIfAbsent(rawDigest, rawText.getBytes(StandardCharsets.UTF_8))
                : "zz/" + rawDigest.value();
        stores.investigations.insertStartedIfAbsent(InvestigationResult.started(
                attemptId, attemptId, runId, 3, 2, null, NOW.minusSeconds(700)));
        stores.investigations.finishTerminal(new InvestigationResult(
                attemptId, attemptId, runId, 3, 2, ExecutionStatus.SUCCEEDED,
                ValidationStatus.STRUCTURE_VALIDATED, null,
                "{\"schema_version\":\"2\",\"summary\":\"disk full on node-3\","
                        + "\"root_cause\":{\"component\":\"disk\",\"fault_type\":\"RESOURCE\","
                        + "\"reason_code\":\"DISK_FULL\"},\"impact\":\"写失败\","
                        + "\"remediation\":\"扩容\"}",
                ref, rawDigest, null, "test-model",
                "{\"prompt_tokens\":10,\"completion_tokens\":20,\"total_tokens\":30}",
                NOW.minusSeconds(700), NOW.minusSeconds(699)));
    }

    private static RcaTaskExecutor.AttemptArtifact validatedArtifact() {
        return new RcaTaskExecutor.AttemptArtifact(2, ValidationStatus.STRUCTURE_VALIDATED,
                List.of(), "{\"schema_version\":\"2\",\"summary\":\"s\"}", "raw", null,
                List.of(), Digest.sha256Of("raw"), null, "deepseek-v3",
                null, null, null, true, null);
    }

    private RcaTask onlyFinalizeTask(UUID runId) {
        return stores.tasks.findByRunId(runId).stream()
                .filter(t -> RcaTask.REPORT_FINALIZE.equals(t.taskKey()))
                .findFirst().orElseThrow();
    }
}
