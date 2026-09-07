package com.objwww.pr.control.eval.domain.model;

/**
 * 质量门阈值（M5-08；版本化——阈值随 ConfigBundle 走（M5-09），version 即
 * bundle 内阈值集的版本锚，落档进 EvaluationRecordV1 供复现门禁结论）。
 * 零框架（L0：am5DatasetDomainZeroFrameworkDependency 面）。
 *
 * <p>阈值语义（五分支冻结逻辑的参数面，分支本身在 QualityGate）：
 * <ul>
 *   <li>{@code ciMargin}：配对差值 CI 下界容差（分支3：ciLower &lt; -ciMargin → REJECT）；</li>
 *   <li>{@code minClusters}：独立 cluster 下限（分支2，与 PairedTrialStats.MIN_CLUSTERS
 *       同源语义，门面可收紧不可放宽到统计面下限之下——统计面自会 INCONCLUSIVE）；</li>
 *   <li>{@code maxLatencyMs}/{@code maxTotalTokens}/{@code maxToolErrorRate}：运行门三面
 *       （分支4：延迟/token 预算/工具错误率）。</li>
 * </ul>
 *
 * @param version         阈值集版本（blank 拒绝——版本化硬约束）
 * @param ciMargin        CI 下界容差 ≥ 0
 * @param minClusters     独立 cluster 下限 ≥ 1
 * @param maxLatencyMs    运行门延迟上限（毫秒，&gt;0；聚合口径 = 跨案 max）
 * @param maxTotalTokens  运行门 token 预算上限（&gt;0；聚合口径 = 跨案 sum）
 * @param maxToolErrorRate 工具错误率上限（0..1；零调用记 0 不触发）
 */
public record GateThresholds(String version,
                             double ciMargin,
                             int minClusters,
                             long maxLatencyMs,
                             long maxTotalTokens,
                             double maxToolErrorRate) {

    public GateThresholds {
        if (version == null || version.isBlank()) {
            throw new IllegalArgumentException("阈值集版本不得为 blank（版本化硬约束）");
        }
        if (ciMargin < 0) {
            throw new IllegalArgumentException("CI 容差不得为负: " + ciMargin);
        }
        if (minClusters < 1) {
            throw new IllegalArgumentException("cluster 下限不得 < 1: " + minClusters);
        }
        if (maxLatencyMs <= 0) {
            throw new IllegalArgumentException("延迟上限必须 > 0: " + maxLatencyMs);
        }
        if (maxTotalTokens <= 0) {
            throw new IllegalArgumentException("token 预算必须 > 0: " + maxTotalTokens);
        }
        if (maxToolErrorRate < 0 || maxToolErrorRate > 1) {
            throw new IllegalArgumentException("错误率上限必须在 [0,1]: " + maxToolErrorRate);
        }
    }
}
