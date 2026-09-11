package com.objwww.pr.control.eval.application;

import com.objwww.pr.control.alert.domain.model.InvestigationResult;
import com.objwww.pr.control.alert.domain.model.RcaReport;
import com.objwww.pr.control.alert.domain.model.RcaRun;
import com.objwww.pr.control.alert.domain.model.RcaRunState;
import com.objwww.pr.control.alert.domain.model.RcaToolCall;
import com.objwww.pr.control.alert.domain.model.RunTrigger;
import com.objwww.pr.control.alert.domain.model.TypedRootCause;
import com.objwww.pr.control.alert.domain.model.ValidationStatus;
import com.objwww.pr.control.alert.domain.repository.RcaRunRepository;
import com.objwww.pr.control.alert.domain.repository.RcaReportRepository;
import com.objwww.pr.control.alert.domain.repository.RcaToolCallRepository;
import com.objwww.pr.control.alert.domain.repository.InvestigationResultRepository;
import com.objwww.pr.control.alert.support.AlertInMemoryStores;
import com.objwww.pr.control.eval.domain.EvalCaseResult;
import com.objwww.pr.control.eval.domain.EvalRun;
import com.objwww.pr.control.eval.domain.EvalRunMetadata;
import com.objwww.pr.control.eval.domain.GoldenCase;
import com.objwww.pr.control.eval.domain.GoldenScenarioRegistry;
import com.objwww.pr.control.eval.domain.ScenarioMetrics.ScoringVerdict;
import com.objwww.pr.control.eval.domain.SynonymLexicon;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * M3-17 批量编排冻结语义：两轮两条独立案例行、next_round_gate 关闭阻断后续轮
 * （gate_blocked 落档不注入）、注入失败落档且门关闭（失败不中断）、评分链异常
 * finally 解除注入 + run 终态 FAILED、SUCCEEDED 终态一次性回填。
 */
class EvalBatchRunnerTest {

    private static final TypedRootCause EXPECTED =
            new TypedRootCause("payment", "BUSINESS_ERROR_RATE", "PAYMENT_CHARGE_FAILURE");

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

    private Instant base;

    @BeforeEach
    void setUp() {
        base = Instant.parse("2026-01-01T00:00:00Z");
    }

    // ------------------------------------------------------------------ 假件

    /** 可编排的驱动器：注入失败脚本 + 恢复回执脚本 + 激活/解除计数 */
    private static final class ScriptedDriver implements ScenarioDriver {
        int activations;
        int deactivations;
        final List<RuntimeException> activateFailures = new ArrayList<>();
        final List<RecoveryReceipt> receipts = new ArrayList<>();

        @Override
        public ActivationReceipt activate(GoldenCase golden, int roundNo) {
            activations++;
            if (!activateFailures.isEmpty()) {
                throw activateFailures.remove(0);
            }
            return new ActivationReceipt(golden.scenarioId(), "digest-" + activations,
                    activations, "alert");
        }

        @Override
        public RecoveryReceipt deactivate(GoldenCase golden, ActivationReceipt receipt) {
            deactivations++;
            if (receipts.isEmpty()) {
                return new RecoveryReceipt(golden.scenarioId(), receipt.actionDigest(),
                        receipt.generation(), true, true, List.of());
            }
            return receipts.remove(0);
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
        private final RuntimeException toThrow;

        StubResolver(UUID runId, RuntimeException toThrow) {
            this.runId = runId;
            this.toThrow = toThrow;
        }

        @Override
        public Optional<UUID> resolve(GoldenCase golden, int roundNo, Instant activatedAt,
                                      int timeoutSeconds) {
            if (toThrow != null) {
                throw toThrow;
            }
            return Optional.ofNullable(runId);
        }
    }

    /** CAS 语义的记录仓储：终态只收一次；同 (run, scenario, round) 拒绝重复 */
    private static final class RecordingEvalRuns implements EvalRunRepository {
        final List<EvalRun> runningInserts = new ArrayList<>();
        final List<EvalRun> finalizedRuns = new ArrayList<>();
        final List<EvalCaseResult> cases = new ArrayList<>();
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
            return true;
        }

        @Override
        public boolean updateRecoveryState(UUID runId, String recoveryState) {
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

    /** 拨钟：sleep 即拨针（不真睡），预热调用序列可断言 */
    private static final class StepClock implements EvalBatchRunner.EvalClock {
        private Instant now = Instant.parse("2026-01-01T00:00:00Z");
        final List<Long> sleeps = new ArrayList<>();

        @Override
        public Instant now() {
            return now;
        }

        @Override
        public void sleepSeconds(long seconds) {
            sleeps.add(seconds);
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

    /** 命中链：SUCCEEDED run + 已验证命中报告 + tool_calls（silence 豁免） */
    private UUID seedHitChain() {
        UUID runId = UUID.randomUUID();
        runs.insert(new RcaRun(runId, UUID.randomUUID(), 0, RunTrigger.INITIAL,
                RcaRunState.SUCCEEDED, Digest.sha256Of("it"), base, base.plusSeconds(60),
                base, base.plusSeconds(60), null));
        UUID attemptId = UUID.randomUUID();
        UUID resultId = UUID.randomUUID();
        investigations.insertStartedIfAbsent(InvestigationResult.started(
                resultId, attemptId, runId, 0, 2, "m", base));
        toolCalls.insertAll(List.of(new RcaToolCall(resultId, "tc-1", 1,
                "prometheus_query", null, null, null, null, null, runId, 0, 2,
                Digest.sha256Of("p"))));
        reports.insert(new RcaReport(UUID.randomUUID(), runId, attemptId, 2,
                ValidationStatus.STRUCTURE_VALIDATED, List.of(), HIT_JSON, "raw", "m",
                1, 1, 2, false, base.plusSeconds(30)));
        return runId;
    }

    private EvalBatchRunner runner(ScriptedDriver driver, RcaRunResolver resolver,
                                   RecordingEvalRuns repo, EvalBatchRunner.EvalClock clock) {
        return runner(driver, resolver, repo, clock, (alertname, maxWaitSeconds) -> true);
    }

    private EvalBatchRunner runner(ScriptedDriver driver, RcaRunResolver resolver,
                                   RecordingEvalRuns repo, EvalBatchRunner.EvalClock clock,
                                   IncidentResolutionProbe incidentProbe) {
        return new EvalBatchRunner(GoldenScenarioRegistry.load(REGISTRY),
                Map.of("FlagdScenarioDriver", driver), new StubProbe(), incidentProbe,
                resolver, scorer(), repo, new BaselineReportGenerator(), metadata(), 2, clock);
    }

    private static EvalRunMetadata metadata() {
        return new EvalRunMetadata(1, "eval-ds-it", Digest.sha256Of("registry"), 1,
                "deepseek-v3", "am3-rca-v2", Digest.sha256Of("prompt"),
                Digest.sha256Of("tools"), null, null, null, null, null, "fp-x",
                Digest.sha256Of("rules"), "scenario-driver-v1", "grader-test-v1");
    }

    // ------------------------------------------------------------------ 用例

    @Test
    @DisplayName("两轮正常路径：每轮注入/解除/预热、两条 DECIDABLE 独立案例行、SUCCEEDED 终态")
    void twoRoundsBothScoredFinalizesSucceeded() {
        UUID runId = seedHitChain();
        ScriptedDriver driver = new ScriptedDriver();
        RecordingEvalRuns repo = new RecordingEvalRuns();
        StepClock clock = new StepClock();
        EvalBatchRunner batch = runner(driver, new StubResolver(runId, null), repo, clock);

        EvalBatchRunner.BatchResult result = batch.runBatch();

        assertThat(driver.activations).isEqualTo(2);
        assertThat(driver.deactivations).isEqualTo(2);
        assertThat(repo.runningInserts).hasSize(1);
        assertThat(repo.cases).hasSize(2);
        assertThat(repo.cases).extracting(EvalCaseResult::roundNo).containsExactly(1, 2);
        assertThat(repo.cases).allSatisfy(r -> {
            assertThat(r.verdict()).isEqualTo(ScoringVerdict.DECIDABLE);
            assertThat(r.rootCauseHit()).isTrue();
            assertThat(r.silencePenalty()).isFalse();
        });
        assertThat(clock.sleeps).containsExactly(5L, 5L);
        assertThat(repo.finalizedRuns).hasSize(1);
        assertThat(repo.finalizedRuns.get(0).state()).isEqualTo(EvalRun.EvalRunState.SUCCEEDED);
        assertThat(result.finalized()).isTrue();
        assertThat(result.snapshot().total()).isEqualTo(2);
        assertThat(result.snapshot().decidable()).isEqualTo(2);
        assertThat(result.snapshot().hits()).isEqualTo(2);
        assertThat(result.snapshot().endToEndHitRate()).isEqualTo(1.0);
        assertThat(result.symptomCounts().truePositives()).isEqualTo(2);
    }

    @Test
    @DisplayName("next_round_gate：恢复回执未达 → 下一轮 gate_blocked 落档且不注入")
    void gateClosedBlocksNextRound() {
        UUID runId = seedHitChain();
        ScriptedDriver driver = new ScriptedDriver();
        driver.receipts.add(new ScenarioDriver.RecoveryReceipt("S1", "d", 1,
                false, true, List.of("injection_not_reverted")));
        RecordingEvalRuns repo = new RecordingEvalRuns();
        EvalBatchRunner batch = runner(driver, new StubResolver(runId, null), repo,
                new StepClock());

        EvalBatchRunner.BatchResult result = batch.runBatch();

        assertThat(driver.activations).isEqualTo(1);
        assertThat(driver.deactivations).isEqualTo(1);
        assertThat(repo.cases).hasSize(2);
        EvalCaseResult blocked = repo.cases.get(1);
        assertThat(blocked.roundNo()).isEqualTo(2);
        assertThat(blocked.verdict()).isEqualTo(ScoringVerdict.TIMEOUT_OR_ABSENT);
        assertThat(blocked.failureSampleJson()).contains("gate_blocked");
        assertThat(blocked.fnCount()).isEqualTo(1);
        assertThat(result.finalized()).isTrue();
        assertThat(repo.finalizedRuns.get(0).state()).isEqualTo(EvalRun.EvalRunState.SUCCEEDED);
    }

    @Test
    @DisplayName("上轮 incident 未 RESOLVED：下一轮 prev_round_not_resolved 落档、不注入、门关闭")
    void prevRoundUnresolvedBlocksInjectionAndClosesGate() {
        UUID runId = seedHitChain();
        ScriptedDriver driver = new ScriptedDriver();
        // R1 前 episode 干净（放行）→ R1 正常走通；R2 前残留（FIRING）→ 判败关门
        boolean[] probeAnswers = {true, false};
        int[] calls = {0};
        IncidentResolutionProbe probe = (alertname, maxWaitSeconds) ->
                probeAnswers[Math.min(calls[0]++, probeAnswers.length - 1)];
        RecordingEvalRuns repo = new RecordingEvalRuns();
        EvalBatchRunner batch = runner(driver, new StubResolver(runId, null), repo,
                new StepClock(), probe);

        EvalBatchRunner.BatchResult result = batch.runBatch();

        assertThat(driver.activations).isEqualTo(1);
        assertThat(driver.deactivations).isEqualTo(1);
        assertThat(repo.cases).hasSize(2);
        assertThat(repo.cases.get(0).verdict()).isEqualTo(ScoringVerdict.DECIDABLE);
        EvalCaseResult blocked = repo.cases.get(1);
        assertThat(blocked.roundNo()).isEqualTo(2);
        assertThat(blocked.verdict()).isEqualTo(ScoringVerdict.TIMEOUT_OR_ABSENT);
        assertThat(blocked.failureSampleJson()).contains("prev_round_not_resolved");
        assertThat(result.finalized()).isTrue();
        assertThat(repo.finalizedRuns.get(0).state()).isEqualTo(EvalRun.EvalRunState.SUCCEEDED);
    }

    @Test
    @DisplayName("注入失败：该轮落档 activate_failed、不解除（未激活）、门关闭、批仍 SUCCEEDED")
    void activateFailureDocumentedAndGateCloses() {
        UUID runId = seedHitChain();
        ScriptedDriver driver = new ScriptedDriver();
        driver.activateFailures.add(new IllegalStateException("flagd admin down"));
        RecordingEvalRuns repo = new RecordingEvalRuns();
        EvalBatchRunner batch = runner(driver, new StubResolver(runId, null), repo,
                new StepClock());

        EvalBatchRunner.BatchResult result = batch.runBatch();

        assertThat(driver.activations).isEqualTo(1);
        assertThat(driver.deactivations).isZero();
        assertThat(repo.cases).hasSize(2);
        EvalCaseResult failed = repo.cases.get(0);
        assertThat(failed.verdict()).isEqualTo(ScoringVerdict.TIMEOUT_OR_ABSENT);
        assertThat(failed.failureSampleJson())
                .contains("activate_failed").contains("flagd admin down");
        assertThat(repo.cases.get(1).failureSampleJson()).contains("gate_blocked");
        assertThat(result.finalized()).isTrue();
        assertThat(repo.finalizedRuns.get(0).state()).isEqualTo(EvalRun.EvalRunState.SUCCEEDED);
    }

    @Test
    @DisplayName("评分链异常：finally 解除注入、run 终态 FAILED、异常上抛不吞")
    void scoringChainFailureDeactivatesAndFinalizesFailed() {
        UUID runId = seedHitChain();
        ScriptedDriver driver = new ScriptedDriver();
        RecordingEvalRuns repo = new RecordingEvalRuns();
        EvalBatchRunner batch = runner(driver,
                new StubResolver(runId, new IllegalStateException("arena map missing")),
                repo, new StepClock());

        assertThatThrownBy(batch::runBatch)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("arena map missing");

        assertThat(driver.activations).isEqualTo(1);
        assertThat(driver.deactivations).isEqualTo(1);
        assertThat(repo.finalizedRuns).hasSize(1);
        assertThat(repo.finalizedRuns.get(0).state()).isEqualTo(EvalRun.EvalRunState.FAILED);
    }
}
