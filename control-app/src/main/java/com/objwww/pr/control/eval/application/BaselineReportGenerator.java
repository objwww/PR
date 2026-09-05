package com.objwww.pr.control.eval.application;

import com.objwww.pr.control.eval.domain.EvalCaseResult;
import com.objwww.pr.control.eval.domain.EvalRun;
import com.objwww.pr.control.eval.domain.EvalRunMetadata;
import com.objwww.pr.control.eval.domain.GoldenCase;
import com.objwww.pr.control.eval.domain.ScenarioMetrics;
import com.objwww.pr.shared.Digest;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 基线报告生成器（M3-18）：批量评测终局的唯一汇总面——原始 TP/FP/FN + 三指标
 * 分子分母（不只存小数）+ UNRESOLVED 单列合理拒答率 + 失败样本原文 + 全配置版本。
 * 输出 canonical JSON（固定字段序）+ 内容摘要（跨批次 diff 的锚，重跑一致面）。
 */
public final class BaselineReportGenerator {

    private static final com.fasterxml.jackson.databind.ObjectMapper JSON =
            new com.fasterxml.jackson.databind.ObjectMapper();

    /** 报告体（canonical 字段序即 record 分量序；digest 以此 JSON 计算） */
    public record BaselineReport(Map<String, Object> body, String canonicalJson,
                                 Digest reportDigest) {
    }

    public BaselineReport generate(EvalRunMetadata metadata,
                                   List<GoldenCase> scenarios,
                                   List<ScenarioMetrics.ScenarioScore> scores,
                                   List<CaseFailure> failures,
                                   EvalRun.SymptomCounts symptomCounts,
                                   Instant finishedAt) {
        ScenarioMetrics.Snapshot snapshot = ScenarioMetrics.of(scores);

        Map<String, Object> body = new java.util.LinkedHashMap<>();
        body.put("report_schema_version", 1);
        body.put("finished_at", finishedAt.toString());
        body.put("config", metadata.canonicalMap());
        body.put("scenarios", scenarios.stream()
                .map(g -> Map.of("scenario_id", g.scenarioId(), "driver", String.valueOf(g.driver()),
                        "chaos_family", String.valueOf(g.chaosFamily())))
                .toList());
        body.put("raw_counts", Map.of(
                "total", snapshot.total(),
                "decidable", snapshot.decidable(),
                "hits", snapshot.hits(),
                "unresolved", snapshot.unresolved(),
                "structure_rejected", snapshot.structureRejected(),
                "timeout_or_absent", snapshot.timeoutOrAbsent(),
                "tp", symptomCounts.truePositives(),
                "fp", symptomCounts.falsePositives(),
                "fn", symptomCounts.falseNegatives()));
        body.put("metrics", Map.of(
                "coverage", snapshot.coverage(),
                "conditional_accuracy", snapshot.conditionalAccuracy(),
                "end_to_end_hit_rate", snapshot.endToEndHitRate(),
                "unresolved_rate", snapshot.unresolvedRate()));
        body.put("failures", failures.stream().map(CaseFailure::toMap).toList());

        String canonical = toJson(body);
        return new BaselineReport(Map.copyOf(body), canonical,
                Digest.sha256Of(canonical));
    }

    private static String toJson(Map<String, Object> body) {
        try {
            return JSON.writeValueAsString(body);
        } catch (Exception e) {
            throw new IllegalStateException("基线报告序列化失败", e);
        }
    }

    /** 失败样本原文（M-04 原值；M3-18 报告逐条引用） */
    public record CaseFailure(String scenarioId, int roundNo, String verdict,
                              String failureSampleJson) {

        public CaseFailure {
            Objects.requireNonNull(scenarioId);
            Objects.requireNonNull(verdict);
            Objects.requireNonNull(failureSampleJson);
        }

        private Map<String, Object> toMap() {
            return Map.of("scenario_id", scenarioId, "round", roundNo,
                    "verdict", verdict, "failure_sample", failureSampleJson);
        }
    }
}
