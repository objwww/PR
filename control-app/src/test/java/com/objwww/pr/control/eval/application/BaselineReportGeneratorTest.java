package com.objwww.pr.control.eval.application;

import com.objwww.pr.control.alert.domain.model.TypedRootCause;
import com.objwww.pr.control.eval.application.BaselineReportGenerator.CaseFailure;
import com.objwww.pr.control.eval.domain.EvalRun;
import com.objwww.pr.control.eval.domain.EvalRunMetadata;
import com.objwww.pr.control.eval.domain.GoldenCase;
import com.objwww.pr.control.eval.domain.ScenarioMetrics;
import com.objwww.pr.shared.Digest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * M3-18 基线报告：canonical JSON 固定字段序 → digest 稳定（重跑一致锚）且逐项敏感
 * （任何评分/失败/配置差异都暴露）；raw_counts 携带分子分母原始计数、UNRESOLVED
 * 单列不并入分母、失败样本原文逐条引用。
 */
class BaselineReportGeneratorTest {

    private static final TypedRootCause CAUSE =
            new TypedRootCause("payment", "BUSINESS_ERROR_RATE", "PAYMENT_CHARGE_FAILURE");

    private static final Instant FINISHED = Instant.parse("2026-01-01T01:00:00Z");

    private static EvalRunMetadata metadata() {
        return new EvalRunMetadata(1, "eval-ds-it", Digest.sha256Of("registry"), 1,
                "deepseek-v3", "am3-rca-v2", Digest.sha256Of("prompt"),
                Digest.sha256Of("tools"), null, null, null, null, null, "fp-x",
                Digest.sha256Of("rules"), "scenario-driver-v1");
    }

    private static GoldenCase golden(String scenarioId) {
        return new GoldenCase(scenarioId, "n-" + scenarioId, "FlagdScenarioDriver", null,
                "payment", CAUSE, List.of("checkout"), new GoldenCase.Timing(5, 10, 10, 10, 5));
    }

    private static ScenarioMetrics.ScenarioScore hit(String scenarioId) {
        return new ScenarioMetrics.ScenarioScore(scenarioId,
                ScenarioMetrics.ScoringVerdict.DECIDABLE, true);
    }

    private static ScenarioMetrics.ScenarioScore miss(String scenarioId) {
        return new ScenarioMetrics.ScenarioScore(scenarioId,
                ScenarioMetrics.ScoringVerdict.DECIDABLE, false);
    }

    private static ScenarioMetrics.ScenarioScore unresolved(String scenarioId) {
        return new ScenarioMetrics.ScenarioScore(scenarioId,
                ScenarioMetrics.ScoringVerdict.UNRESOLVED, false);
    }

    @Test
    @DisplayName("同输入重跑 digest 一致（64 位）；报告体含固定字段序的 config 与场景清单")
    void digestStableAcrossIdenticalBatches() {
        BaselineReportGenerator generator = new BaselineReportGenerator();
        List<ScenarioMetrics.ScenarioScore> scores = List.of(hit("S1"), miss("S1"));
        List<CaseFailure> failures = List.of();

        BaselineReportGenerator.BaselineReport first = generator.generate(metadata(),
                List.of(golden("S1")), scores, failures,
                new EvalRun.SymptomCounts(1, 0, 1), FINISHED);
        BaselineReportGenerator.BaselineReport second = generator.generate(metadata(),
                List.of(golden("S1")), scores, failures,
                new EvalRun.SymptomCounts(1, 0, 1), FINISHED);

        assertThat(first.reportDigest().value())
                .isEqualTo(second.reportDigest().value())
                .hasSize(64);
        assertThat(first.canonicalJson()).contains("\"report_schema_version\":1");
        assertThat(first.canonicalJson()).contains("\"scenario_driver_version\"");
        assertThat((List<?>) first.body().get("scenarios")).hasSize(1);
    }

    @Test
    @DisplayName("逐项敏感：评分/失败样本/症状计数任一变化 → digest 变化")
    void digestSensitiveToAnyChange() {
        BaselineReportGenerator generator = new BaselineReportGenerator();
        List<GoldenCase> scenarios = List.of(golden("S1"));
        BaselineReportGenerator.BaselineReport baseline = generator.generate(metadata(),
                scenarios, List.of(hit("S1")), List.of(),
                new EvalRun.SymptomCounts(1, 0, 0), FINISHED);

        BaselineReportGenerator.BaselineReport flipped = generator.generate(metadata(),
                scenarios, List.of(miss("S1")), List.of(),
                new EvalRun.SymptomCounts(0, 0, 1), FINISHED);
        BaselineReportGenerator.BaselineReport withFailure = generator.generate(metadata(),
                scenarios, List.of(hit("S1")), List.of(new CaseFailure("S1", 1,
                        "RECOVERY", "{\"reason\":\"alerts_still_firing\"}")),
                new EvalRun.SymptomCounts(1, 0, 0), FINISHED);

        assertThat(flipped.reportDigest().value())
                .isNotEqualTo(baseline.reportDigest().value());
        assertThat(withFailure.reportDigest().value())
                .isNotEqualTo(baseline.reportDigest().value());
    }

    @Test
    @DisplayName("raw_counts 与 metrics 同源快照：UNRESOLVED 单列、不进条件准确率分母")
    void rawCountsAndMetricsCarryNumeratorsAndDenominators() {
        BaselineReportGenerator generator = new BaselineReportGenerator();
        // 2 可判定（1 命中）+ 1 合理拒答：coverage=2/3，conditional=1/2，e2e=1/3，unresolved=1/3
        List<ScenarioMetrics.ScenarioScore> scores =
                List.of(hit("S1"), miss("S1"), unresolved("S1"));

        BaselineReportGenerator.BaselineReport report = generator.generate(metadata(),
                List.of(golden("S1")), scores, List.of(),
                new EvalRun.SymptomCounts(1, 0, 1), FINISHED);

        @SuppressWarnings("unchecked")
        Map<String, Object> rawCounts = (Map<String, Object>) report.body().get("raw_counts");
        assertThat(rawCounts)
                .containsEntry("total", 3)
                .containsEntry("decidable", 2)
                .containsEntry("hits", 1)
                .containsEntry("unresolved", 1)
                .containsEntry("structure_rejected", 0)
                .containsEntry("timeout_or_absent", 0)
                .containsEntry("tp", 1)
                .containsEntry("fp", 0)
                .containsEntry("fn", 1);

        ScenarioMetrics.Snapshot snapshot = ScenarioMetrics.of(scores);
        @SuppressWarnings("unchecked")
        Map<String, Object> metrics = (Map<String, Object>) report.body().get("metrics");
        assertThat(metrics)
                .containsEntry("coverage", snapshot.coverage())
                .containsEntry("conditional_accuracy", snapshot.conditionalAccuracy())
                .containsEntry("end_to_end_hit_rate", snapshot.endToEndHitRate())
                .containsEntry("unresolved_rate", snapshot.unresolvedRate());
        assertThat((Double) metrics.get("coverage")).isEqualTo(2.0 / 3);
        assertThat((Double) metrics.get("conditional_accuracy")).isEqualTo(0.5);
        assertThat((Double) metrics.get("end_to_end_hit_rate")).isEqualTo(1.0 / 3);
        assertThat((Double) metrics.get("unresolved_rate")).isEqualTo(1.0 / 3);
    }

    @Test
    @DisplayName("失败样本原文逐条引用（场景/轮次/裁决/样本串原样）")
    void failuresCarriedVerbatim() {
        BaselineReportGenerator generator = new BaselineReportGenerator();
        CaseFailure failure = new CaseFailure("S1", 2, "RECOVERY",
                "{\"reason\":\"alerts_still_firing\",\"detail\":\"ArenaDuplicateOrders\"}");

        BaselineReportGenerator.BaselineReport report = generator.generate(metadata(),
                List.of(golden("S1")), List.of(hit("S1")), List.of(failure),
                new EvalRun.SymptomCounts(1, 0, 0), FINISHED);

        assertThat((List<?>) report.body().get("failures")).hasSize(1);
        assertThat(report.canonicalJson())
                .contains("\"scenario_id\":\"S1\"")
                .contains("\"round\":2")
                .contains("\"verdict\":\"RECOVERY\"")
                .contains("alerts_still_firing");
    }

    @Test
    @DisplayName("全空批次零分母不炸：指标全 0.0 且 digest 稳定（§6.4 零分母约定）")
    void emptyBatchYieldsZeroDenominatorZeros() {
        BaselineReportGenerator generator = new BaselineReportGenerator();

        BaselineReportGenerator.BaselineReport report = generator.generate(metadata(),
                List.of(), List.of(), List.of(),
                new EvalRun.SymptomCounts(0, 0, 0), FINISHED);

        @SuppressWarnings("unchecked")
        Map<String, Object> metrics = (Map<String, Object>) report.body().get("metrics");
        assertThat((Double) metrics.get("coverage")).isZero();
        assertThat((Double) metrics.get("conditional_accuracy")).isZero();
        assertThat((Double) metrics.get("end_to_end_hit_rate")).isZero();
        assertThat(report.reportDigest().value()).hasSize(64);
    }
}
