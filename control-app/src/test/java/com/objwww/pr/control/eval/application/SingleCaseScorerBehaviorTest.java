package com.objwww.pr.control.eval.application;

import com.objwww.pr.control.alert.domain.evidence.EvidenceEnvelope;
import com.objwww.pr.control.alert.domain.evidence.EvidenceRepository;
import com.objwww.pr.control.alert.domain.model.RcaReport;
import com.objwww.pr.control.alert.domain.model.RcaRun;
import com.objwww.pr.control.alert.domain.model.RunTrigger;
import com.objwww.pr.control.alert.domain.model.TypedRootCause;
import com.objwww.pr.control.alert.domain.model.ValidationStatus;
import com.objwww.pr.control.alert.support.AlertInMemoryStores;
import com.objwww.pr.control.eval.domain.GoldenCase;
import com.objwww.pr.control.eval.domain.SynonymLexicon;
import com.objwww.pr.control.eval.domain.repository.EvalCaseBehaviorSink;
import com.objwww.pr.shared.Digest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ME-T04（D04）SingleCaseScorer.recordBehavior 终态收尾装配：只读观测投影 →
 * BehaviorEvaluator 纯函数 → V160 落行；观测读失败落 ERROR 行；sink 缺席 = 不落。
 */
class SingleCaseScorerBehaviorTest {

    private static final TypedRootCause EXPECTED =
            new TypedRootCause("payment", "BUSINESS_ERROR_RATE", "PAYMENT_CHARGE_FAILURE");
    private static final Instant T0 = Instant.parse("2026-01-01T00:00:00Z");

    private final AlertInMemoryStores.Runs runs = new AlertInMemoryStores.Runs();
    private final AlertInMemoryStores.Reports reports = new AlertInMemoryStores.Reports();
    private final AlertInMemoryStores.Investigations investigations =
            new AlertInMemoryStores.Investigations();
    private final AlertInMemoryStores.ToolCalls toolCalls =
            new AlertInMemoryStores.ToolCalls();

    private GoldenCase golden;

    @BeforeEach
    void setUp() {
        golden = new GoldenCase("S1", "paymentFailure=50%", "FlagdScenarioDriver", null,
                "payment", EXPECTED, List.of("checkout"),
                new GoldenCase.Timing(1, 1, 1, 1, 1));
    }

    /** 捕获面：内存 sink + 可故障证据读面桩 */
    private static final class Capturing {
        final List<List<Object>> rows = new ArrayList<>();
        final Map<UUID, EvidenceEnvelope> evidence = new LinkedHashMap<>();
        boolean failReads;

        final EvalCaseBehaviorSink sink = new EvalCaseBehaviorSink() {
            @Override
            public void insert(UUID caseResultId, UUID evalRunId, String scenarioId,
                               int roundNo, String graderVersion, String traceDigest,
                               String coverageJson, String checksJson, String metricsJson,
                               String failureLabelsJson, String evidenceRefsJson) {
                rows.add(List.of(caseResultId, evalRunId, scenarioId, roundNo,
                        graderVersion, String.valueOf(traceDigest), checksJson,
                        failureLabelsJson));
            }
        };

        final EvidenceRepository evidenceRepository = new EvidenceRepository() {
            @Override
            public void insert(EvidenceEnvelope envelope) {
                evidence.put(envelope.evidenceId(), envelope);
            }

            @Override
            public Optional<EvidenceEnvelope> findById(UUID evidenceId) {
                if (failReads) {
                    throw new IllegalStateException("read down");
                }
                return Optional.ofNullable(evidence.get(evidenceId));
            }

            @Override
            public List<EvidenceEnvelope> findByRunId(UUID runId) {
                if (failReads) {
                    throw new IllegalStateException("read down");
                }
                return evidence.values().stream().filter(e -> e.runId().equals(runId)).toList();
            }
        };
    }

    private SingleCaseScorer scorer(Capturing cap, boolean withEvidence) {
        return new SingleCaseScorer(runs, reports, investigations, toolCalls,
                new ScenarioEvaluator(SynonymLexicon.load("""
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
                        """)),
                null, null, null,
                withEvidence ? cap.evidenceRepository : null,
                null, null, cap.sink);
    }

    private UUID seedRunAndReport(UUID evidenceId) {
        UUID runId = UUID.randomUUID();
        runs.insert(new RcaRun(runId, UUID.randomUUID(), 0, RunTrigger.INITIAL,
                com.objwww.pr.control.alert.domain.model.RcaRunState.SUCCEEDED,
                Digest.sha256Of("it"), T0, T0.plusSeconds(300),
                T0, T0.plusSeconds(300), null));
        UUID attemptId = UUID.randomUUID();
        String ref = evidenceId == null ? "no-ref" : evidenceId.toString();
        reports.insert(new RcaReport(UUID.randomUUID(), runId, attemptId, 2,
                ValidationStatus.STRUCTURE_VALIDATED, List.of(),
                "{\"schema_version\":2,\"summary\":\"s\",\"root_cause\":{\"component\":"
                        + "\"payment\",\"fault_type\":\"BUSINESS_ERROR_RATE\",\"reason_code\":"
                        + "\"PAYMENT_CHARGE_FAILURE\"},\"claims\":[{\"claim_type\":\"root_cause\","
                        + "\"status\":\"TRUE\",\"component\":\"payment\",\"fault_type\":"
                        + "\"BUSINESS_ERROR_RATE\",\"symptom_codes\":[\"checkout\"],"
                        + "\"evidence_refs\":[\"" + ref + "\"]}],\"evidence\":[],"
                        + "\"impact\":\"i\",\"remediation\":\"r\",\"references\":[]}",
                "raw", "m", 1, 1, 2, false, T0.plusSeconds(240)));
        return runId;
    }

    private static EvidenceEnvelope envelope(UUID id, UUID runId, String payload) {
        return new EvidenceEnvelope(id, runId, UUID.randomUUID(), "logs.query",
                EvidenceEnvelope.SCHEMA_VERSION, 0, "test", Map.of(),
                T0, T0.plusSeconds(60), payload, "d-" + payload.hashCode());
    }

    @Test
    @DisplayName("终态行为落档：引用解析同 run 证据 → 存在/归属 PASS，checks/metrics 带分子分母")
    void recordBehaviorPersistsResolvedEvaluation() {
        Capturing cap = new Capturing();
        UUID evidenceId = UUID.randomUUID();
        UUID runId = seedRunAndReport(evidenceId);
        cap.evidenceRepository.insert(envelope(evidenceId, runId,
                "{\"text\":\"payment timeout 命中\"}"));
        UUID evalRunId = UUID.randomUUID();
        UUID caseResultId = UUID.randomUUID();

        scorer(cap, true).recordBehavior(evalRunId, golden, 1, runId, caseResultId);

        assertThat(cap.rows).hasSize(1);
        List<Object> row = cap.rows.getFirst();
        assertThat(row.get(0)).isEqualTo(caseResultId);
        assertThat(row.get(1)).isEqualTo(evalRunId);
        assertThat(row.get(4)).isEqualTo("behavior-v1");
        String checks = (String) row.get(6);
        assertThat(checks).contains(BehaviorEvaluatorCheckNames.EXISTENCE)
                .contains("\"status\":\"PASS\"");
        assertThat(checks).contains(evidenceId.toString());
        // 幂等键（case_result_id + grader 版本）归 SQL 契约测试；traceDigest 非空
        assertThat((String) row.get(5)).isNotBlank().hasSize(64);
    }

    @Test
    @DisplayName("观测读失败 → ERROR 行（全检查 TRACE_READ_ERROR），不冒充零问题通过")
    void recordBehaviorReadFailureLandsErrorRow() {
        Capturing cap = new Capturing();
        UUID evidenceId = UUID.randomUUID();
        UUID runId = seedRunAndReport(evidenceId);
        cap.evidenceRepository.insert(envelope(evidenceId, runId, "{\"text\":\"x\"}"));
        cap.failReads = true;

        scorer(cap, true).recordBehavior(UUID.randomUUID(), golden, 1, runId,
                UUID.randomUUID());

        assertThat(cap.rows).hasSize(1);
        String checks = (String) cap.rows.getFirst().get(6);
        assertThat(checks).contains("\"status\":\"ERROR\"").contains("TRACE_READ_ERROR");
        assertThat((String) cap.rows.getFirst().get(7)).contains("TRACE_READ_ERROR");
    }

    @Test
    @DisplayName("trace 缺失（rcaRunId null）→ 依赖轨迹检查 NOT_ASSESSED，不猜通过")
    void recordBehaviorWithoutTraceMarksNotAssessed() {
        Capturing cap = new Capturing();
        GoldenCase withCheckpoints = new GoldenCase("S1", "n", "FlagdScenarioDriver", null,
                "payment", EXPECTED, List.of("checkout"), Map.of(), null,
                new GoldenCase.Timing(1, 1, 1, 1, 1),
                GoldenCase.KIND_INJECT, List.of("payment timeout"));
        scorer(cap, true).recordBehavior(UUID.randomUUID(), withCheckpoints, 1, null,
                UUID.randomUUID());

        assertThat(cap.rows).hasSize(1);
        String checks = (String) cap.rows.getFirst().get(6);
        assertThat(checks).contains("TRACE_MISSING")
                .doesNotContain("\"status\":\"PASS\"");
    }

    /** 词表锚定字面量（测试不反向依赖 domain 常量字符串拼接） */
    private static final class BehaviorEvaluatorCheckNames {
        static final String EXISTENCE = "citation_existence";
    }
}
