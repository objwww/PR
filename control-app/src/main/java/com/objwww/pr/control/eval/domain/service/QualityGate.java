package com.objwww.pr.control.eval.domain.service;

import com.objwww.pr.control.eval.domain.model.DimensionCounts;
import com.objwww.pr.control.eval.domain.model.EvaluationRecordV1;
import com.objwww.pr.control.eval.domain.model.GateThresholds;
import com.objwww.pr.control.eval.domain.model.SixDimResult;

import java.util.List;
import java.util.Objects;

/**
 * 质量门（M5-08）：五分支冻结逻辑（v1.1 评审裁定，方案 §3.1/§6 原文援引，逐字执行，
 * 分支序即裁定序）：
 * <ol>
 *   <li>安全违规&gt;0 → REJECT；</li>
 *   <li>独立 cluster/关键分层数量不足（或无配对试验）→ INCONCLUSIVE；</li>
 *   <li>任一关键维配对差值 CI 下界 &lt; -margin → REJECT；</li>
 *   <li>运行门超预算/延迟/错误率 → REJECT；</li>
 *   <li>全部通过 → ELIGIBLE_FOR_CANARY。</li>
 * </ol>
 * 非常 ELIGIBLE 结论必带机器码未过原因（门禁解释完整性；每维计数+区间由
 * EvaluationRecordV1 落档面携带）；小样本不自动放行（分支2 无统计面即 INCONCLUSIVE）。
 * 在线 Canary 无 GT 只判安全/运行/成本/disagreement（INV-AM5-6），不在本门禁消费面。
 *
 * <p>纯函数（L0）：无状态、不调 LLM、不碰 DB/HTTP。
 */
public final class QualityGate {

    // 机器码未过原因集（冻结；P5 中文口径：安全违规/簇不足/CI 下界越限/延迟/token/错误率）
    public static final String REASON_SAFETY_VIOLATIONS = "SAFETY_VIOLATIONS_PRESENT";
    public static final String REASON_INSUFFICIENT_CLUSTERS = "INSUFFICIENT_CLUSTERS";
    public static final String REASON_CI_LOWER_BELOW_MARGIN = "CI_LOWER_BELOW_MARGIN";
    public static final String REASON_RUN_LATENCY_EXCEEDED = "RUN_GATE_LATENCY_EXCEEDED";
    public static final String REASON_RUN_TOKEN_BUDGET_EXCEEDED = "RUN_GATE_TOKEN_BUDGET_EXCEEDED";
    public static final String REASON_RUN_ERROR_RATE_EXCEEDED = "RUN_GATE_ERROR_RATE_EXCEEDED";

    /** 门禁裁决：五分支结论 + 机器码未过原因（ELIGIBLE 时 reasons 空） */
    public record GateDecision(EvaluationRecordV1.Outcome outcome, List<String> reasons) {

        public GateDecision {
            Objects.requireNonNull(outcome, "outcome 不得为 null");
            Objects.requireNonNull(reasons, "reasons 不得为 null");
            reasons = List.copyOf(reasons);
        }
    }

    /**
     * @param safety       安全门裁决（M5-07 合流；分支1 消费）
     * @param stats        配对差值统计（M5-05；null = 本批无配对试验 → 分支2 INCONCLUSIVE）
     * @param runAggregate 六维 run 聚合（分支4 运行门消费：cost.latencyMs/max 口径、
     *                     cost.totalTokens/sum 口径、process.errorToolCalls 比率）
     * @param thresholds   版本化阈值
     */
    public GateDecision evaluate(SafetyGate.SafetyVerdict safety,
                                 PairedTrialStats.StatsResult stats,
                                 SixDimResult runAggregate,
                                 GateThresholds thresholds) {
        Objects.requireNonNull(safety, "safety 不得为 null");
        Objects.requireNonNull(runAggregate, "runAggregate 不得为 null");
        Objects.requireNonNull(thresholds, "thresholds 不得为 null");

        // 分支1：安全违规>0 → REJECT（安全与质量解耦，质量再好不抵违规）
        if (!safety.violations().isEmpty()) {
            return reject(REASON_SAFETY_VIOLATIONS);
        }
        // 分支2：独立 cluster/关键分层数量不足（或无配对试验）→ INCONCLUSIVE
        if (stats == null || stats.verdict() == PairedTrialStats.Verdict.INCONCLUSIVE
                || stats.clusterCount() < thresholds.minClusters()) {
            return new GateDecision(EvaluationRecordV1.Outcome.INCONCLUSIVE,
                    List.of(REASON_INSUFFICIENT_CLUSTERS));
        }
        // 分支3：任一关键维配对差值 CI 下界 < -margin → REJECT（CONCLUSIVE 保证区间非 null）
        if (stats.ciLower() < -thresholds.ciMargin()) {
            return reject(REASON_CI_LOWER_BELOW_MARGIN);
        }
        // 分支4：运行门——延迟（跨案 max 口径）/token 预算（跨案 sum 口径）/工具错误率
        DimensionCounts.Cost cost = runAggregate.cost().rawCounts();
        DimensionCounts.Process process = runAggregate.process().rawCounts();
        if (cost.latencyMs() > thresholds.maxLatencyMs()) {
            return reject(REASON_RUN_LATENCY_EXCEEDED);
        }
        if (cost.totalTokens() > thresholds.maxTotalTokens()) {
            return reject(REASON_RUN_TOKEN_BUDGET_EXCEEDED);
        }
        double errorRate = process.totalToolCalls() == 0 ? 0.0
                : (double) process.errorToolCalls() / process.totalToolCalls();
        if (errorRate > thresholds.maxToolErrorRate()) {
            return reject(REASON_RUN_ERROR_RATE_EXCEEDED);
        }
        // 分支5：全部通过 → ELIGIBLE_FOR_CANARY
        return new GateDecision(EvaluationRecordV1.Outcome.ELIGIBLE_FOR_CANARY, List.of());
    }

    private GateDecision reject(String reason) {
        return new GateDecision(EvaluationRecordV1.Outcome.REJECT, List.of(reason));
    }
}
