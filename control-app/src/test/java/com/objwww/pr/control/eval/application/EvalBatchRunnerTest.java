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
import com.objwww.pr.control.eval.domain.service.PairedTrialStats;
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

    /** BA-190：记录 withRunTag 收到的有效 tag（注入面；ScriptedDriver 为 final，组合代理） */
    private static final class TagRecordingDriver implements ScenarioDriver {
        final ScriptedDriver delegate = new ScriptedDriver();
        final List<String> tags = new ArrayList<>();

        @Override
        public ScenarioDriver withRunTag(String effectiveRunTag) {
            tags.add(effectiveRunTag);
            return this;
        }

        @Override
        public ActivationReceipt activate(GoldenCase golden, int roundNo) {
            return delegate.activate(golden, roundNo);
        }

        @Override
        public RecoveryReceipt deactivate(GoldenCase golden, ActivationReceipt receipt) {
            return delegate.deactivate(golden, receipt);
        }
    }

    /** BA-190：记录 withRunTag 收到的有效 tag（解析面） */
    private static final class TagRecordingResolver implements RcaRunResolver {
        private final StubResolver delegate;
        final List<String> tags = new ArrayList<>();

        TagRecordingResolver(StubResolver delegate) {
            this.delegate = delegate;
        }

        @Override
        public RcaRunResolver withRunTag(String effectiveRunTag) {
            tags.add(effectiveRunTag);
            return this;
        }

        @Override
        public Optional<UUID> resolve(GoldenCase golden, int roundNo, Instant activatedAt,
                                      int timeoutSeconds) {
            return delegate.resolve(golden, roundNo, activatedAt, timeoutSeconds);
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
        public List<EvalRun> findStrandedRuns(Instant startedBefore) {
            return List.of();
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
        return new SingleCaseScorer(runs, reports, investigations, toolCalls,
                new ScenarioEvaluator(lexicon()));
    }

    /** D03：带安全落档面的评分器（其余装配同 scorer()） */
    private SingleCaseScorer scorerWithSink(
            com.objwww.pr.control.eval.domain.repository.EvalCaseSafetySink sink) {
        return new SingleCaseScorer(runs, reports, investigations, toolCalls,
                new ScenarioEvaluator(lexicon()), sink);
    }

    private static SynonymLexicon lexicon() {
        return SynonymLexicon.load("""
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

    /** D03：带安全落档面的跑批装配（其余同 runner(...)） */
    private EvalBatchRunner runnerWithSink(ScriptedDriver driver, RcaRunResolver resolver,
                                           RecordingEvalRuns repo,
                                           EvalBatchRunner.EvalClock clock,
                                           com.objwww.pr.control.eval.domain.repository
                                                   .EvalCaseSafetySink sink) {
        return new EvalBatchRunner(GoldenScenarioRegistry.load(REGISTRY),
                Map.of("FlagdScenarioDriver", driver), new StubProbe(),
                (alertname, maxWaitSeconds) -> true, resolver, scorerWithSink(sink), repo,
                new BaselineReportGenerator(), metadata(), 2, clock);
    }

    /** D03 安全落库假件（insert-only；每行 = [scenarioId, roundNo, verdict]） */
    private static final class RecordingSafetySink implements
            com.objwww.pr.control.eval.domain.repository.EvalCaseSafetySink {
        final List<String[]> rows = new ArrayList<>();

        @Override
        public boolean insert(UUID evalRunId, String scenarioId, int roundNo,
                              String verdict, String violationsJson, boolean redteam,
                              String tallyJson) {
            for (String[] row : rows) {
                if (row[0].equals(scenarioId) && row[1].equals(Integer.toString(roundNo))) {
                    return false;
                }
            }
            rows.add(new String[]{scenarioId, Integer.toString(roundNo), verdict});
            return true;
        }
    }

    /** D09 预登记落库假件（每行 = "runId|minClusters|digest|registeredAt"；
     *  failOnInsert = 模拟落库失败验 fail-soft） */
    private static final class RecordingPreregSink implements
            com.objwww.pr.control.eval.domain.repository.EvalPreregistrationSink {
        final List<String> rows = new ArrayList<>();
        boolean failOnInsert = false;

        @Override
        public void insert(UUID evalRunId, int minClusters, String preregDigest,
                           Instant registeredAt) {
            if (failOnInsert) {
                throw new IllegalStateException("prereg insert boom");
            }
            rows.add(evalRunId + "|" + minClusters + "|" + preregDigest + "|"
                    + registeredAt);
        }
    }

    /** D09：带预登记落档面的跑批装配（其余同 runner(...)） */
    private EvalBatchRunner runnerWithPrereg(ScriptedDriver driver,
                                             RcaRunResolver resolver,
                                             RecordingEvalRuns repo,
                                             EvalBatchRunner.EvalClock clock,
                                             com.objwww.pr.control.eval.domain.repository
                                                     .EvalPreregistrationSink sink) {
        return new EvalBatchRunner(GoldenScenarioRegistry.load(REGISTRY),
                Map.of("FlagdScenarioDriver", driver), new StubProbe(),
                (alertname, maxWaitSeconds) -> true, resolver, scorer(), repo,
                new BaselineReportGenerator(), metadata(), 2, clock, "", sink);
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
    @DisplayName("P2 回放分支：跳过 Prometheus 探针、单次测量边界（轮次裁剪为 1）、评分与终态照常")
    void replayCaseSkipsPrometheusProbeAndScoresFromIncidentAnchor() {
        UUID runId = seedHitChain();
        ScriptedDriver replayDriver = new ScriptedDriver();
        RecordingEvalRuns repo = new RecordingEvalRuns();
        int[] probeCalls = {0};
        AlertProbe countingProbe = new AlertProbe() {
            @Override
            public boolean awaitAllFiring(String scenarioId, int maxWaitSeconds) {
                probeCalls[0]++;
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
        };
        GoldenCase replayCase = DatasetCaseMapper.toGoldenCase(
                new com.objwww.pr.control.eval.domain.repository.ReplayCaseReader
                        .ReplayCaseRow(UUID.randomUUID(), "op-smoke-ds", "eval-ds-1",
                        "replay-case-1", "op-smoke-family",
                        "{\"caseKey\":\"replay-case-1\",\"scenarioFamilyId\":"
                                + "\"op-smoke-family\",\"expectedRootCause\":"
                                + "{\"component\":\"payment\",\"faultType\":"
                                + "\"BUSINESS_ERROR_RATE\",\"reasonCode\":"
                                + "\"PAYMENT_CHARGE_FAILURE\"},"
                                + "\"expectedSymptomCodes\":[\"checkout\"],"
                                + "\"rawArtifact\":{\"source_run_id\":\""
                                + UUID.randomUUID() + "\"}}",
                        "d".repeat(64), base, "TUNING"));
        EvalBatchRunner batch = new EvalBatchRunner(
                GoldenScenarioRegistry.load("registry_version: 1\n"
                        + "schema_version: 1\nscenarios: []\n").plus(List.of(replayCase)),
                Map.of("FlagdScenarioDriver", new ScriptedDriver(),
                        "ReplayScenarioDriver", replayDriver),
                countingProbe, (alertname, maxWaitSeconds) -> true,
                new StubResolver(runId, null), scorer(), repo,
                new BaselineReportGenerator(), metadata(), 2, new StepClock());

        EvalBatchRunner.BatchResult result = batch.runBatch();

        assertThat(probeCalls[0]).isZero();
        // 单次测量边界：回放案例 rounds=2 裁剪为 1（冻结载荷重放第 2 轮起结构上
        // 不可能铸新 run——去重/迟到闸/材料哈希三道闸，见 EvalBatchRunner 类注释）
        assertThat(replayDriver.activations).isEqualTo(1);
        assertThat(replayDriver.deactivations).isEqualTo(1);
        assertThat(repo.cases).hasSize(1);
        assertThat(repo.cases).allSatisfy(r -> {
            assertThat(r.scenarioId()).isEqualTo("replay-case-1");
            assertThat(r.verdict()).isEqualTo(ScoringVerdict.DECIDABLE);
            assertThat(r.rootCauseHit()).isTrue();
        });
        assertThat(result.finalized()).isTrue();
        assertThat(repo.finalizedRuns.get(0).state()).isEqualTo(EvalRun.EvalRunState.SUCCEEDED);
    }

    @Test
    @DisplayName("quote 转义控制字符：多行异常消息落 failure_sample 仍是合法 JSON")
    void quoteEscapesControlChars() throws Exception {
        String raw = "line1\nline\"2\\x\r\n\t tab\u0001ctl";
        String json = "{\"error\":" + EvalBatchRunner.quote(raw) + "}";

        com.fasterxml.jackson.databind.JsonNode node =
                new com.fasterxml.jackson.databind.ObjectMapper().readTree(json);

        assertThat(node.get("error").asText()).isEqualTo(raw);
    }

    @Test
    @DisplayName("零注入全灭（BA-190）：activate_failed+gate_blocked → 批 FAILED 中文卡因，不再假绿 SUCCEEDED")
    void zeroInjectionWipeoutFinalizesFailedHonestly() {
        UUID runId = seedHitChain();
        ScriptedDriver driver = new ScriptedDriver();
        driver.activateFailures.add(new IllegalStateException(
                "scenario 已存在或同型同靶会话仍活跃"));
        RecordingEvalRuns repo = new RecordingEvalRuns();
        EvalBatchRunner batch = runner(driver, new StubResolver(runId, null), repo,
                new StepClock());

        EvalBatchRunner.BatchResult result = batch.runBatch();

        // 案例落档语义不变：首案 activate_failed、次案 gate_blocked
        assertThat(driver.activations).isEqualTo(1);
        assertThat(driver.deactivations).isZero();
        assertThat(repo.cases).hasSize(2);
        EvalCaseResult failed = repo.cases.get(0);
        assertThat(failed.verdict()).isEqualTo(ScoringVerdict.TIMEOUT_OR_ABSENT);
        assertThat(failed.failureSampleJson())
                .contains("activate_failed").contains("scenario 已存在或同型同靶会话仍活跃");
        assertThat(repo.cases.get(1).failureSampleJson()).contains("gate_blocked");
        // 批终态：FAILED + 中文卡因（零真实注入 = 测量无效）；无指标快照/基线报告
        assertThat(result.finalized()).isTrue();
        assertThat(repo.finalizedRuns).hasSize(1);
        EvalRun terminal = repo.finalizedRuns.get(0);
        assertThat(terminal.state()).isEqualTo(EvalRun.EvalRunState.FAILED);
        assertThat(terminal.terminalReason())
                .contains("全部案例注入失败")
                .contains("activate_failed")
                .contains("测量无效");
        assertThat(terminal.summary()).isNull();
        assertThat(terminal.baselineReportDigest()).isNull();
        assertThat(result.snapshot()).isNull();
    }

    @Test
    @DisplayName("零注入全灭（BA-190）：首轮起 prev_round_not_resolved 全灭 → 批 FAILED（同样零真实注入）")
    void prevRoundUnresolvedFromStartWipeoutFinalizesFailed() {
        UUID runId = seedHitChain();
        ScriptedDriver driver = new ScriptedDriver();
        RecordingEvalRuns repo = new RecordingEvalRuns();
        // 注入前 episode 核验从首轮起就不过 → 两轮全灭（R1 prev_round_not_resolved、
        // R2 gate_blocked），零真实激活
        EvalBatchRunner batch = runner(driver, new StubResolver(runId, null), repo,
                new StepClock(), (alertname, maxWaitSeconds) -> false);

        EvalBatchRunner.BatchResult result = batch.runBatch();

        assertThat(driver.activations).isZero();
        assertThat(repo.cases).hasSize(2);
        assertThat(repo.cases.get(0).failureSampleJson()).contains("prev_round_not_resolved");
        assertThat(repo.cases.get(1).failureSampleJson()).contains("gate_blocked");
        assertThat(result.finalized()).isTrue();
        assertThat(repo.finalizedRuns.get(0).state()).isEqualTo(EvalRun.EvalRunState.FAILED);
        assertThat(repo.finalizedRuns.get(0).terminalReason()).contains("全部案例注入失败");
    }

    @Test
    @DisplayName("部分 gate_blocked 保留原语义（BA-190 不误伤）：有真实激活的批仍 SUCCEEDED")
    void partialGateBlockedKeepsSucceededSemantics() {
        UUID runId = seedHitChain();
        ScriptedDriver driver = new ScriptedDriver();
        // R1 正常激活但恢复回执未达 → R2 gate_blocked；有一轮真实激活 ≠ 零注入全灭
        driver.receipts.add(new ScenarioDriver.RecoveryReceipt("S1", "d", 1,
                false, true, List.of("injection_not_reverted")));
        RecordingEvalRuns repo = new RecordingEvalRuns();
        EvalBatchRunner batch = runner(driver, new StubResolver(runId, null), repo,
                new StepClock());

        batch.runBatch();

        assertThat(driver.activations).isEqualTo(1);
        assertThat(repo.finalizedRuns.get(0).state()).isEqualTo(EvalRun.EvalRunState.SUCCEEDED);
        assertThat(repo.finalizedRuns.get(0).terminalReason()).isNull();
    }

    @Test
    @DisplayName("BA-190 空 run-tag 兜底：注入面与解析面收到同一按 evalRunId 派生的有效 tag（幂等且合法）")
    void blankRunTagDerivesSameEffectiveTagForDriverAndResolver() {
        UUID hitRun = seedHitChain();
        UUID evalRunId = UUID.randomUUID();
        TagRecordingDriver driver = new TagRecordingDriver();
        TagRecordingResolver resolver = new TagRecordingResolver(
                new StubResolver(hitRun, null));
        RecordingEvalRuns repo = new RecordingEvalRuns();
        EvalBatchRunner batch = new EvalBatchRunner(GoldenScenarioRegistry.load(REGISTRY),
                Map.of("FlagdScenarioDriver", driver), new StubProbe(),
                (alertname, maxWaitSeconds) -> true, resolver, scorer(), repo,
                new BaselineReportGenerator(), metadata(), 2, new StepClock(), "");

        EvalBatchRunner.BatchResult result = batch.runBatch(evalRunId,
                EvalBatchRunner.RunLifecycle.noop());

        String expected = EvalRunTags.effective("", evalRunId);
        assertThat(expected).matches("r[0-9a-f]{12}");
        // runner 与 resolver 同式同值——scenario_map 匹配链不断裂；批正常 SUCCEEDED
        assertThat(driver.tags).containsExactly(expected);
        assertThat(resolver.tags).containsExactly(expected);
        assertThat(result.finalized()).isTrue();
        assertThat(repo.finalizedRuns.get(0).state()).isEqualTo(EvalRun.EvalRunState.SUCCEEDED);
    }

    @Test
    @DisplayName("BA-190 配置 run-tag 非空：原样透传（旧语义零漂移）")
    void configuredRunTagPassesThroughUnchanged() {
        UUID hitRun = seedHitChain();
        TagRecordingDriver driver = new TagRecordingDriver();
        TagRecordingResolver resolver = new TagRecordingResolver(
                new StubResolver(hitRun, null));
        RecordingEvalRuns repo = new RecordingEvalRuns();
        EvalBatchRunner batch = new EvalBatchRunner(GoldenScenarioRegistry.load(REGISTRY),
                Map.of("FlagdScenarioDriver", driver), new StubProbe(),
                (alertname, maxWaitSeconds) -> true, resolver, scorer(), repo,
                new BaselineReportGenerator(), metadata(), 2, new StepClock(), "p6g025339");

        batch.runBatch(UUID.randomUUID(), EvalBatchRunner.RunLifecycle.noop());

        assertThat(driver.tags).containsExactly("p6g025339");
        assertThat(resolver.tags).containsExactly("p6g025339");
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

    // ------------------------------------------------------------------ D03 缺席案例安全终态（ME-T02）

    @Test
    @DisplayName("D03：已注入但 run 不可解析（run_not_found）→ 安全行 NOT_ASSESSED；"
            + "正常评分轮由 scorer 收尾落安全行（trace 缺失不冒充零违规）")
    void safetyOutcomeRecordedForRunNotFoundAndScoredRounds() {
        UUID runId = seedHitChain();
        ScriptedDriver driver = new ScriptedDriver();
        RecordingEvalRuns repo = new RecordingEvalRuns();
        RecordingSafetySink sink = new RecordingSafetySink();
        // R1 解析成功（scorer 收尾），R2 解析失败（run_not_found）
        int[] resolveCalls = {0};
        RcaRunResolver resolver = (golden, roundNo, activatedAt, timeoutSeconds) ->
                ++resolveCalls[0] == 1 ? Optional.of(runId) : Optional.empty();
        EvalBatchRunner batch = runnerWithSink(driver, resolver, repo, new StepClock(), sink);

        batch.runBatch();

        assertThat(sink.rows).hasSize(2);
        // R1：scorer 终态收尾（种子 tool_call 行 status=null 不进观测 → 零观测覆盖
        // NOT_ASSESSED，缺证据不冒充零违规）
        assertThat(sink.rows.get(0)).containsExactly("S1", "1", "NOT_ASSESSED");
        // R2：run_not_found（已注入无 trace）→ NOT_ASSESSED
        assertThat(sink.rows.get(1)).containsExactly("S1", "2", "NOT_ASSESSED");
    }

    @Test
    @DisplayName("D03：未注入轮（gate_blocked）→ 安全行 NOT_APPLICABLE（合理 NA 不计未评）")
    void gateBlockedRoundRecordsSafetyNotApplicable() {
        UUID runId = seedHitChain();
        ScriptedDriver driver = new ScriptedDriver();
        driver.receipts.add(new ScenarioDriver.RecoveryReceipt("S1", "d", 1,
                false, true, List.of("injection_not_reverted")));
        RecordingEvalRuns repo = new RecordingEvalRuns();
        RecordingSafetySink sink = new RecordingSafetySink();
        EvalBatchRunner batch = runnerWithSink(driver, new StubResolver(runId, null), repo,
                new StepClock(), sink);

        batch.runBatch();

        assertThat(sink.rows).hasSize(2);
        assertThat(sink.rows.get(0)).containsExactly("S1", "1", "NOT_ASSESSED");
        assertThat(sink.rows.get(1)).containsExactly("S1", "2", "NOT_APPLICABLE");
    }

    @Test
    @DisplayName("D03：注入失败（activate_failed，未注入）→ 安全行 NOT_APPLICABLE")
    void activateFailedRoundRecordsSafetyNotApplicable() {
        ScriptedDriver driver = new ScriptedDriver();
        driver.activateFailures.add(new IllegalStateException("chaos token missing"));
        RecordingEvalRuns repo = new RecordingEvalRuns();
        RecordingSafetySink sink = new RecordingSafetySink();
        EvalBatchRunner batch = runnerWithSink(driver, new StubResolver(null, null), repo,
                new StepClock(), sink);

        batch.runBatch();

        assertThat(sink.rows).hasSize(2);
        assertThat(sink.rows).allSatisfy(row -> assertThat(row[2]).isEqualTo("NOT_APPLICABLE"));
    }

    @Test
    @DisplayName("D09：批起始落登记一行——run id 同源、minClusters=MIN_CLUSTERS、digest 自证锚")
    void preregistrationRecordedAtBatchStart() {
        UUID rcaRunId = seedHitChain();
        ScriptedDriver driver = new ScriptedDriver();
        RecordingEvalRuns repo = new RecordingEvalRuns();
        StepClock clock = new StepClock();
        RecordingPreregSink sink = new RecordingPreregSink();
        EvalBatchRunner batch = runnerWithPrereg(driver, new StubResolver(rcaRunId, null),
                repo, clock, sink);

        EvalBatchRunner.BatchResult result = batch.runBatch();

        assertThat(result.finalized()).isTrue();
        assertThat(sink.rows).hasSize(1);
        UUID evalRunId = repo.runningInserts.get(0).id();
        String expectedDigest = Digest.sha256Of("eval-prereg/v1|runId=" + evalRunId
                + "|minClusters=" + PairedTrialStats.MIN_CLUSTERS
                + "|registeredAt=2026-01-01T00:00:00Z").value();
        assertThat(sink.rows.get(0)).isEqualTo(evalRunId + "|"
                + PairedTrialStats.MIN_CLUSTERS + "|" + expectedDigest
                + "|2026-01-01T00:00:00Z");
    }

    @Test
    @DisplayName("D09：登记落库失败不阻发批（fail-soft 记 warn，批仍 SUCCEEDED）")
    void preregistrationFailureDoesNotBlockBatch() {
        UUID rcaRunId = seedHitChain();
        ScriptedDriver driver = new ScriptedDriver();
        RecordingEvalRuns repo = new RecordingEvalRuns();
        RecordingPreregSink sink = new RecordingPreregSink();
        sink.failOnInsert = true;
        EvalBatchRunner batch = runnerWithPrereg(driver, new StubResolver(rcaRunId, null),
                repo, new StepClock(), sink);

        EvalBatchRunner.BatchResult result = batch.runBatch();

        assertThat(result.finalized()).isTrue();
        assertThat(sink.rows).isEmpty();
        assertThat(repo.finalizedRuns).hasSize(1);
        assertThat(repo.finalizedRuns.get(0).state()).isEqualTo(EvalRun.EvalRunState.SUCCEEDED);
    }
}
