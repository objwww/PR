package com.objwww.pr.control.eval.application;

import com.objwww.pr.control.alert.domain.model.InvestigationResult;
import com.objwww.pr.control.alert.domain.model.RcaReport;
import com.objwww.pr.control.alert.domain.model.RcaRun;
import com.objwww.pr.control.alert.domain.model.RcaRunState;
import com.objwww.pr.control.alert.domain.model.RcaToolCall;
import com.objwww.pr.control.alert.domain.model.RunTrigger;
import com.objwww.pr.control.alert.domain.model.TypedRootCause;
import com.objwww.pr.control.alert.domain.model.ValidationStatus;
import com.objwww.pr.control.alert.support.AlertInMemoryStores;
import com.objwww.pr.control.eval.domain.EvalCaseResult;
import com.objwww.pr.control.eval.domain.EvalRun;
import com.objwww.pr.control.eval.domain.EvalRunMetadata;
import com.objwww.pr.control.eval.domain.GoldenCase;
import com.objwww.pr.control.eval.domain.GoldenScenarioRegistry;
import com.objwww.pr.control.eval.domain.ScenarioMetrics;
import com.objwww.pr.control.eval.domain.SynonymLexicon;
import com.objwww.pr.control.eval.domain.repository.EvalPhaseEventSink;
import com.objwww.pr.control.eval.domain.repository.EvalRunRepository;
import com.objwww.pr.shared.Digest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * EV-04 worker 形态的批量编排（真断言假件）：
 * 阶段事件按真实迁移落 eval_phase_event（PREPARING→INJECTING→AWAITING_ALERT→
 * AWAITING_RCA→SCORING→FINALIZING）、发起身份回填（display_name/mode/launch_plan）、
 * 取消检查点（案例边界生效——受理后在跑轮次走完，不再推进新案例）、
 * L 模式取消强制恢复路径（RECOVERING→VERIFIED/FAILED→FAILED 终态，EU14）。
 */
class EvalBatchRunnerLifecycleTest {

    private static final Instant BASE = Instant.parse("2026-01-01T00:00:00Z");

    private static final String REGISTRY = """
            registry_version: 1
            schema_version: 1
            lexicon_binding: "synonym-lexicon-v1.yml (lexicon_version: 1)"
            scenarios:
              - scenario_id: S1
                name: paymentFailure=50%
                driver: FlagdScenarioDriver
                chaos_family: null
                target: payment
                expected_root_cause:
                  component: payment
                  fault_type: BUSINESS_ERROR_RATE
                  reason_code: PAYMENT_CHARGE_FAILURE
                expected_symptom_codes:
                  - checkout
                timing:
                  preheat_seconds: 5
                  hold_seconds: 10
                  max_firing_wait_seconds: 10
                  max_resolved_wait_seconds: 10
                  cleanup_timeout_seconds: 5
            """;

    private static final String HIT_JSON = "{\"schema_version\":2,\"summary\":\"s\","
            + "\"root_cause\":{\"component\":\"payment\",\"fault_type\":"
            + "\"BUSINESS_ERROR_RATE\",\"reason_code\":\"PAYMENT_CHARGE_FAILURE\"},"
            + "\"claims\":[{\"claim_type\":\"root_cause\",\"status\":\"TRUE\","
            + "\"component\":\"payment\",\"fault_type\":\"BUSINESS_ERROR_RATE\","
            + "\"symptom_codes\":[\"checkout\"],\"evidence_refs\":[\"r\"]}],"
            + "\"evidence\":[\"e\"],\"impact\":\"i\",\"remediation\":\"r\",\"references\":[]}";

    private final AlertInMemoryStores.Runs runs = new AlertInMemoryStores.Runs();
    private final AlertInMemoryStores.Reports reports = new AlertInMemoryStores.Reports();
    private final AlertInMemoryStores.Investigations investigations =
            new AlertInMemoryStores.Investigations();
    private final AlertInMemoryStores.ToolCalls toolCalls =
            new AlertInMemoryStores.ToolCalls();

    @BeforeEach
    void setUp() {
    }

    // ------------------------------------------------------------------ 假件

    private static final class ScriptedDriver implements ScenarioDriver {
        int activations;
        int deactivations;

        @Override
        public ActivationReceipt activate(GoldenCase golden, int roundNo) {
            activations++;
            return new ActivationReceipt(golden.scenarioId(), "digest-" + activations,
                    activations, "alert");
        }

        @Override
        public RecoveryReceipt deactivate(GoldenCase golden, ActivationReceipt receipt) {
            deactivations++;
            return new RecoveryReceipt(golden.scenarioId(), receipt.actionDigest(),
                    receipt.generation(), true, true, List.of());
        }
    }

    private static final class StubProbe implements AlertProbe {
        @Override
        public boolean awaitAllFiring(String scenarioId, int maxWaitSeconds) {
            return true;
        }

        @Override
        public boolean awaitAllResolved(String scenarioId, int maxWaitSeconds) {
            return true;
        }

        @Override
        public boolean awaitSessionClosed(String scenarioId, int cleanupTimeoutSeconds) {
            return true;
        }

        @Override
        public Digest ruleDigest(String alertname) {
            return Digest.sha256Of("rules:" + alertname);
        }
    }

    private static final class StubResolver implements RcaRunResolver {
        private final UUID runId;

        StubResolver(UUID runId) {
            this.runId = runId;
        }

        @Override
        public Optional<UUID> resolve(GoldenCase golden, int roundNo, Instant activatedAt,
                                      int timeoutSeconds) {
            return Optional.of(runId);
        }
    }

    /** 记录生命周期写面的仓储（身份回填 + 恢复分面 + CAS 终态） */
    private static final class RecordingEvalRuns implements EvalRunRepository {
        final List<EvalRun> runningInserts = new ArrayList<>();
        final List<EvalRun> finalizedRuns = new ArrayList<>();
        final List<EvalCaseResult> cases = new ArrayList<>();
        final List<String> identityCalls = new ArrayList<>();
        final List<String> recoveryStates = new ArrayList<>();
        private final Set<String> caseKeys = new HashSet<>();

        @Override
        public void insertRunning(EvalRun run) {
            runningInserts.add(run);
        }

        @Override
        public boolean finalizeOnce(EvalRun terminal) {
            if (finalizedRuns.isEmpty()) {
                finalizedRuns.add(terminal);
                return true;
            }
            return false;
        }

        @Override
        public boolean applyLaunchIdentity(UUID runId, String displayName, String mode,
                                           String launchPlanJson) {
            identityCalls.add(displayName + "|" + mode + "|" + launchPlanJson);
            return true;
        }

        @Override
        public boolean updateRecoveryState(UUID runId, String recoveryState) {
            recoveryStates.add(recoveryState);
            return true;
        }

        @Override
        public boolean insertCaseResult(EvalCaseResult result) {
            String key = result.evalRunId() + "/" + result.scenarioId() + "/"
                    + result.roundNo();
            if (!caseKeys.add(key)) {
                return false;
            }
            cases.add(result);
            return true;
        }

        @Override
        public Optional<EvalRun> findById(UUID runId) {
            return Optional.empty();
        }

        @Override
        public List<EvalCaseResult> findCasesByRunId(UUID runId) {
            return List.copyOf(cases);
        }
    }

    /** 记录阶段事件（insert-only 顺序面可断言） */
    private static final class RecordingPhaseSink implements EvalPhaseEventSink {
        final List<String> phases = new ArrayList<>();
        final List<String> workerIds = new ArrayList<>();
        final List<String> details = new ArrayList<>();

        @Override
        public void record(UUID evalRunId, String phase, Instant enteredAt,
                           String workerId, String detailJson) {
            phases.add(phase);
            workerIds.add(workerId);
            details.add(detailJson);
        }
    }

    private static final class StepClock implements EvalBatchRunner.EvalClock {
        private Instant now = BASE;

        @Override
        public Instant now() {
            return now;
        }

        @Override
        public void sleepSeconds(long seconds) {
            now = now.plusSeconds(seconds);
        }
    }

    // ------------------------------------------------------------------ 装配

    private SingleCaseScorer scorer() {
        SynonymLexicon lexicon = SynonymLexicon.load("""
                lexicon_version: 1
                components:
                  - code: payment
                    synonyms: [payment-svc]
                fault_types:
                  - code: BUSINESS_ERROR_RATE
                    synonyms: [业务错误率升高]
                reason_codes:
                  - code: PAYMENT_CHARGE_FAILURE
                    fault_type: BUSINESS_ERROR_RATE
                    synonyms: [扣款失败]
                """);
        return new SingleCaseScorer(runs, reports, investigations, toolCalls,
                new ScenarioEvaluator(lexicon));
    }

    private UUID seedHitChain() {
        UUID runId = UUID.randomUUID();
        runs.insert(new RcaRun(runId, UUID.randomUUID(), 0, RunTrigger.INITIAL,
                RcaRunState.SUCCEEDED, Digest.sha256Of("it"), BASE, BASE.plusSeconds(60),
                BASE, BASE.plusSeconds(60), null));
        UUID attemptId = UUID.randomUUID();
        UUID resultId = UUID.randomUUID();
        investigations.insertStartedIfAbsent(InvestigationResult.started(
                resultId, attemptId, runId, 0, 2, "m", BASE));
        toolCalls.insertAll(List.of(new RcaToolCall(resultId, "tc-1", 1,
                "prometheus_query", null, null, null, null, null, runId, 0, 2,
                Digest.sha256Of("p"))));
        reports.insert(new RcaReport(UUID.randomUUID(), runId, attemptId, 2,
                ValidationStatus.STRUCTURE_VALIDATED, List.of(), HIT_JSON, "raw", "m",
                1, 1, 2, false, BASE.plusSeconds(30)));
        return runId;
    }

    private static EvalRunMetadata metadata() {
        return new EvalRunMetadata(1, "eval-ds-it", Digest.sha256Of("registry"), 1,
                "deepseek-v3", "am3-rca-v2", Digest.sha256Of("prompt"),
                Digest.sha256Of("tools"), null, null, null, null, null, "fp-x",
                Digest.sha256Of("rules"), "scenario-driver-v1");
    }

    private EvalBatchRunner runner(ScriptedDriver driver, UUID rcaRunId,
                                   RecordingEvalRuns repo, StepClock clock,
                                   IncidentResolutionProbe incidentProbe) {
        return new EvalBatchRunner(GoldenScenarioRegistry.load(REGISTRY),
                Map.of("FlagdScenarioDriver", driver), new StubProbe(), incidentProbe,
                new StubResolver(rcaRunId), scorer(), repo, new BaselineReportGenerator(),
                metadata(), 2, clock);
    }

    private static EvalBatchRunner.RunLifecycle lifecycle(String mode,
                                                          RecordingPhaseSink sink,
                                                          EvalBatchRunner.CancelSignal cancel) {
        return new EvalBatchRunner.RunLifecycle(mode, "实验甲",
                "{\"displayName\":\"实验甲\",\"mode\":\"" + mode + "\"}",
                "worker-it", sink, cancel);
    }

    // ------------------------------------------------------------------ 用例

    @Test
    @DisplayName("阶段事件接线：发起即 PREPARING + 身份回填；逐轮 INJECTING→AWAITING_ALERT"
            + "→AWAITING_RCA→SCORING；收尾 FINALIZING；事件带 worker_id 与案例 detail")
    void phaseEventsFollowRealTransitions() {
        UUID rcaRunId = seedHitChain();
        ScriptedDriver driver = new ScriptedDriver();
        RecordingEvalRuns repo = new RecordingEvalRuns();
        RecordingPhaseSink sink = new RecordingPhaseSink();
        EvalBatchRunner batch = runner(driver, rcaRunId, repo, new StepClock(),
                (alertname, maxWaitSeconds) -> true);

        EvalBatchRunner.BatchResult result = batch.runBatch(UUID.randomUUID(),
                lifecycle("E", sink, id -> false));

        assertThat(result.finalized()).isTrue();
        assertThat(repo.identityCalls).hasSize(1);
        assertThat(repo.identityCalls.get(0)).startsWith("实验甲|E|");
        assertThat(sink.phases).containsExactly(
                "PREPARING",
                "INJECTING", "AWAITING_ALERT", "AWAITING_RCA", "SCORING",
                "INJECTING", "AWAITING_ALERT", "AWAITING_RCA", "SCORING",
                "FINALIZING");
        assertThat(sink.workerIds).allSatisfy(w -> assertThat(w).isEqualTo("worker-it"));
        assertThat(sink.details.get(1)).contains("\"scenarioId\":\"S1\"")
                .contains("\"round\":1");
        // E 模式无恢复义务：recovery_state 零写面
        assertThat(repo.recoveryStates).isEmpty();
        assertThat(repo.finalizedRuns.get(0).state())
                .isEqualTo(EvalRun.EvalRunState.SUCCEEDED);
        assertThat(repo.finalizedRuns.get(0).terminalReason()).isNull();
    }

    @Test
    @DisplayName("取消检查点（E 模式）：受理后在跑轮次走完（含解除注入），不再推进"
            + "新案例；FAILED 终态带 cancelled_by_operator 卡因；无 RECOVERING")
    void cancelStopsNewCasesAtCheckpoint() {
        UUID rcaRunId = seedHitChain();
        ScriptedDriver driver = new ScriptedDriver();
        RecordingEvalRuns repo = new RecordingEvalRuns();
        RecordingPhaseSink sink = new RecordingPhaseSink();
        // 第一次检查（R1 前）放行，之后所有检查点都见取消
        AtomicInteger checks = new AtomicInteger();
        EvalBatchRunner.CancelSignal cancel = id -> checks.getAndIncrement() >= 1;
        EvalBatchRunner batch = runner(driver, rcaRunId, repo, new StepClock(),
                (alertname, maxWaitSeconds) -> true);

        EvalBatchRunner.BatchResult result = batch.runBatch(UUID.randomUUID(),
                lifecycle("E", sink, cancel));

        // 在跑的 R1 完整走完（注入→评分→解除注入），R2 不再推进
        assertThat(driver.activations).isEqualTo(1);
        assertThat(driver.deactivations).isEqualTo(1);
        assertThat(repo.cases).hasSize(1);
        assertThat(repo.cases.get(0).roundNo()).isEqualTo(1);
        // 取消收尾：FAILED + 卡因；无指标快照、无基线报告、无恢复面
        assertThat(repo.finalizedRuns).hasSize(1);
        EvalRun terminal = repo.finalizedRuns.get(0);
        assertThat(terminal.state()).isEqualTo(EvalRun.EvalRunState.FAILED);
        assertThat(terminal.terminalReason()).isEqualTo("cancelled_by_operator");
        assertThat(terminal.summary()).isNull();
        assertThat(result.snapshot()).isNull();
        assertThat(sink.phases).doesNotContain("FINALIZING", "RECOVERING");
        assertThat(repo.recoveryStates).isEmpty();
    }

    @Test
    @DisplayName("EU14 L 模式取消：转 RECOVERING 并完成恢复核验（VERIFIED）后才到"
            + " FAILED 终态；卡因带 recovery=VERIFIED")
    void liveModeCancelForcesRecoveryVerification() {
        UUID rcaRunId = seedHitChain();
        ScriptedDriver driver = new ScriptedDriver();
        RecordingEvalRuns repo = new RecordingEvalRuns();
        RecordingPhaseSink sink = new RecordingPhaseSink();
        AtomicInteger checks = new AtomicInteger();
        EvalBatchRunner.CancelSignal cancel = id -> checks.getAndIncrement() >= 1;
        // 恢复核验探针：全部 RESOLVED
        EvalBatchRunner batch = runner(driver, rcaRunId, repo, new StepClock(),
                (alertname, maxWaitSeconds) -> true);

        batch.runBatch(UUID.randomUUID(), lifecycle("L", sink, cancel));

        assertThat(driver.activations).isEqualTo(1);
        assertThat(sink.phases).contains("RECOVERING");
        assertThat(sink.phases).doesNotContain("FINALIZING");
        // 恢复分面真值轨迹：开跑 PENDING → RECOVERING → VERIFIED
        assertThat(repo.recoveryStates).containsExactly("PENDING", "RECOVERING", "VERIFIED");
        EvalRun terminal = repo.finalizedRuns.get(0);
        assertThat(terminal.state()).isEqualTo(EvalRun.EvalRunState.FAILED);
        assertThat(terminal.terminalReason())
                .isEqualTo("cancelled_by_operator;recovery=VERIFIED");
    }

    @Test
    @DisplayName("EU14 L 模式取消但现场未恢复：核验失败 recovery=FAILED，终态卡因如实")
    void liveModeCancelWithFailedRecoveryVerification() {
        UUID rcaRunId = seedHitChain();
        ScriptedDriver driver = new ScriptedDriver();
        RecordingEvalRuns repo = new RecordingEvalRuns();
        RecordingPhaseSink sink = new RecordingPhaseSink();
        AtomicInteger checks = new AtomicInteger();
        EvalBatchRunner.CancelSignal cancel = id -> checks.getAndIncrement() >= 1;
        // 恢复核验探针：incident 未 RESOLVED（现场仍有效）
        EvalBatchRunner batch = runner(driver, rcaRunId, repo, new StepClock(),
                (alertname, maxWaitSeconds) -> false);

        batch.runBatch(UUID.randomUUID(), lifecycle("L", sink, cancel));

        assertThat(repo.recoveryStates).containsExactly("PENDING", "RECOVERING", "FAILED");
        EvalRun terminal = repo.finalizedRuns.get(0);
        assertThat(terminal.terminalReason())
                .isEqualTo("cancelled_by_operator;recovery=FAILED");
    }

    @Test
    @DisplayName("noop 生命周期（旧 CLI 形态）：零事件零身份回填零取消检查，M3 语义不变")
    void noopLifecycleKeepsLegacySemantics() {
        UUID rcaRunId = seedHitChain();
        ScriptedDriver driver = new ScriptedDriver();
        RecordingEvalRuns repo = new RecordingEvalRuns();
        EvalBatchRunner batch = runner(driver, rcaRunId, repo, new StepClock(),
                (alertname, maxWaitSeconds) -> true);

        EvalBatchRunner.BatchResult result = batch.runBatch();

        assertThat(result.finalized()).isTrue();
        assertThat(driver.activations).isEqualTo(2);
        assertThat(repo.identityCalls).isEmpty();
        assertThat(repo.recoveryStates).isEmpty();
        assertThat(repo.finalizedRuns.get(0).state())
                .isEqualTo(EvalRun.EvalRunState.SUCCEEDED);
    }
}
