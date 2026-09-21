package com.objwww.pr.control.eval.application;

import com.objwww.pr.control.alert.domain.model.TypedRootCause;
import com.objwww.pr.control.alert.support.AlertInMemoryStores;
import com.objwww.pr.control.eval.domain.GoldenCase;
import com.objwww.pr.control.eval.domain.SynonymLexicon;
import com.objwww.pr.control.eval.domain.model.ContextDriftInput;
import com.objwww.pr.control.eval.domain.repository.EvalCaseDriftSink;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * ME-T12a（D08）SingleCaseScorer.recordDrift 终态收尾装配：只读观测投影
 * （rca_context_summary 最新摘要 + rca_compaction_consumption 最新消费观测）→
 * ContextDriftEvaluator 纯函数 → V164 落行；无压缩事件 → summaryText null
 * （忠实性面 NO_SUMMARY）；有摘要无消费观测 → consumption null 如实；
 * 投影读失败落 ERROR 行；落库失败不回滚评分主链；sink 缺席 = 不落。
 */
class SingleCaseScorerDriftTest {

    private static final TypedRootCause EXPECTED =
            new TypedRootCause("payment", "BUSINESS_ERROR_RATE", "PAYMENT_CHARGE_FAILURE");
    private static final String DIGEST =
            "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";

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

    /** 捕获面：内存 sink + 可故障投影读缝行 */
    private static final class Capturing {
        final List<List<Object>> rows = new ArrayList<>();
        SingleCaseScorer.DriftSummaryRow summary;
        ContextDriftInput.ConsumptionFace consumption;
        boolean failReads;
        boolean failInsert;

        final EvalCaseDriftSink sink = new EvalCaseDriftSink() {
            @Override
            public void insert(UUID caseResultId, UUID evalRunId, String scenarioId,
                               int roundNo, String graderVersion, String summaryDigest,
                               String consumptionJson, String checksJson,
                               String metricsJson, String failureLabelsJson,
                               String deferredJson) {
                if (failInsert) {
                    throw new IllegalStateException("insert down");
                }
                rows.add(List.of(caseResultId, evalRunId, scenarioId, roundNo,
                        graderVersion, String.valueOf(summaryDigest), consumptionJson,
                        checksJson, metricsJson, failureLabelsJson, deferredJson));
            }
        };
    }

    /** 假 jdbc：子类覆写两条只读投影缝返回假行（15 形构造器，driftSink 在末尾） */
    private SingleCaseScorer scorer(Capturing cap) {
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
                null, null, null, null, null, null, null, null, null, cap.sink) {
            @Override
            DriftSummaryRow driftSummary(UUID rcaRunId) {
                if (cap.failReads) {
                    throw new IllegalStateException("read down");
                }
                return cap.summary;
            }

            @Override
            ContextDriftInput.ConsumptionFace driftConsumption(UUID rcaRunId) {
                if (cap.failReads) {
                    throw new IllegalStateException("read down");
                }
                return cap.consumption;
            }
        };
    }

    @Test
    @DisplayName("无压缩事件（无摘要行）→ summaryText null：忠实性面 NO_SUMMARY 合理 NA，使用面 NOT_ASSESSED，digest 不出数")
    void recordDriftNoSummaryHonestNa() {
        Capturing cap = new Capturing();
        UUID caseResultId = UUID.randomUUID();

        scorer(cap).recordDrift(UUID.randomUUID(), golden, 1, UUID.randomUUID(),
                caseResultId);

        assertThat(cap.rows).hasSize(1);
        List<Object> row = cap.rows.getFirst();
        assertThat(row.get(0)).isEqualTo(caseResultId);
        assertThat(row.get(4)).isEqualTo("context-drift-v1");
        assertThat(row.get(5)).isEqualTo("null");        // summary_digest 不出数
        assertThat(row.get(6)).isEqualTo("null");        // consumption 无观测面
        String checks = (String) row.get(7);
        assertThat(checks)
                .contains("\"name\":\"key_fact_retention\"")
                .contains("NO_SUMMARY")
                .contains("BEHAVIOR_UNOBSERVED")
                .contains("\"status\":\"NOT_APPLICABLE\"")
                .contains("\"status\":\"NOT_ASSESSED\"")
                .doesNotContain("\"status\":\"FAIL\"")
                .doesNotContain("\"status\":\"PASS\"");
        assertThat((String) row.get(8)).isEqualTo("[]"); // metrics 空不出数
        assertThat((String) row.get(9)).isEqualTo("[]"); // failureLabels 无 FAIL
        assertThat((String) row.get(10)).contains("task_quality_change")
                .contains("injection_attack_success_rate"); // deferred 留痕
    }

    @Test
    @DisplayName("有摘要无消费观测 → digest 落行、consumption null 如实；生产无评分侧真值 → 忠实性面 NO_REQUIRED_FACTS 合理 NA")
    void recordDriftSummaryWithoutConsumption() {
        Capturing cap = new Capturing();
        cap.summary = new SingleCaseScorer.DriftSummaryRow("正常摘要正文", DIGEST);

        scorer(cap).recordDrift(UUID.randomUUID(), golden, 1, UUID.randomUUID(),
                UUID.randomUUID());

        assertThat(cap.rows).hasSize(1);
        List<Object> row = cap.rows.getFirst();
        assertThat(row.get(5)).isEqualTo(DIGEST);        // summary_digest 落行
        assertThat(row.get(6)).isEqualTo("null");        // consumption 无观测不编造
        String checks = (String) row.get(7);
        assertThat(checks)
                .contains("NO_REQUIRED_FACTS")
                .contains("BEHAVIOR_UNOBSERVED")
                .doesNotContain("\"status\":\"FAIL\"")
                .doesNotContain("\"status\":\"PASS\"");
    }

    @Test
    @DisplayName("有摘要+消费观测 → consumption 五件如实落 json（consumed null 透传不猜）")
    void recordDriftConsumptionFacePersisted() {
        Capturing cap = new Capturing();
        cap.summary = new SingleCaseScorer.DriftSummaryRow("正常摘要正文", DIGEST);
        cap.consumption = new ContextDriftInput.ConsumptionFace("SHADOW_GENERATE",
                true, false, null, "policy-digest-1");

        scorer(cap).recordDrift(UUID.randomUUID(), golden, 1, UUID.randomUUID(),
                UUID.randomUUID());

        assertThat(cap.rows).hasSize(1);
        String face = (String) cap.rows.getFirst().get(6);
        assertThat(face)
                .contains("\"mode\":\"SHADOW_GENERATE\"")
                .contains("\"summaryCommitted\":true")
                .contains("\"consumerInvoked\":false")
                .contains("\"consumed\":null")
                .contains("\"policyDigest\":\"policy-digest-1\"");
    }

    @Test
    @DisplayName("投影读失败 → ERROR 行（六检查 TRACE_READ_ERROR），digest 不出数，不冒充零问题通过")
    void recordDriftReadFailureLandsErrorRow() {
        Capturing cap = new Capturing();
        cap.summary = new SingleCaseScorer.DriftSummaryRow("正文", DIGEST);
        cap.failReads = true;

        scorer(cap).recordDrift(UUID.randomUUID(), golden, 1, UUID.randomUUID(),
                UUID.randomUUID());

        assertThat(cap.rows).hasSize(1);
        List<Object> row = cap.rows.getFirst();
        assertThat(row.get(5)).isEqualTo("null");
        assertThat(row.get(6)).isEqualTo("null");
        String checks = (String) row.get(7);
        assertThat(checks).contains("\"status\":\"ERROR\"").contains("TRACE_READ_ERROR")
                .doesNotContain("\"status\":\"PASS\"");
        assertThat((String) row.get(9)).contains("TRACE_READ_ERROR");
    }

    @Test
    @DisplayName("落库失败不回滚评分主链（fail-soft：异常吞掉，缺席=未评如实）")
    void recordDriftInsertFailureDoesNotPropagate() {
        Capturing cap = new Capturing();
        cap.summary = new SingleCaseScorer.DriftSummaryRow("正文", DIGEST);
        cap.failInsert = true;

        assertThatCode(() -> scorer(cap).recordDrift(UUID.randomUUID(), golden, 1,
                UUID.randomUUID(), UUID.randomUUID())).doesNotThrowAnyException();
        assertThat(cap.rows).isEmpty();
    }
}
