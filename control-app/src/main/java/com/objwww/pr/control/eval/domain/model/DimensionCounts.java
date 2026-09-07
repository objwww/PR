package com.objwww.pr.control.eval.domain.model;

/**
 * M5-06 六维原始计数模型（架构 v1.2 §9.1 指标矩阵的 v1 落地面）。
 * 每维一个嵌套 record，只装原始计数/旗标——<b>不含派生分、不聚合</b>
 * （"不聚合成单一分"方案 §3.1；派生视图由消费方按需计算并注明口径）。
 *
 * <ul>
 *   <li>{@link Result}：结论精准度——症状 TP/FP/FN/support + 根因命中/未解析/静默罚
 *      （AM3 三维冻结语义，经 ScenarioEvaluator 透传）；</li>
 *   <li>{@link Process}：推理过程效率——调用总数 + 重复等价调用数
 *       （重复判定 = tool_name+params_digest 同键再现，§9.1"重复等价查询"口径）；</li>
 *   <li>{@link Tool}：工具调用精度——注册命中/幻觉/拒绝（approval_required）；</li>
 *   <li>{@link Cost}：成本与延迟——端到端时延 + token 三项 + usage 缺失旗标
 *       （缺失记 0 不猜补；费率对账口径 TODO 待刊例价校准，AM5 运维项）；</li>
 *   <li>{@link Collaboration}：多 Agent 协作——claims 按裁决三态计数（v1 最小面）；</li>
 *   <li>{@link Safety}：鲁棒性与安全——策略拒绝计数 + 红队用例旗标
 *       （AM5 完整红队指标归 M5-07 SafetyGate 与红队数据集面）。</li>
 * </ul>
 *
 * <p>零框架（L0：am5DatasetDomainZeroFrameworkDependency 面）。
 */
public final class DimensionCounts {

    private DimensionCounts() {
    }

    /** 结论精准度（结果维）：support = 期望症状码数（分母口径与 M3-16 一致） */
    public record Result(int truePositives,
                         int falsePositives,
                         int falseNegatives,
                         int support,
                         boolean rootCauseHit,
                         boolean unresolved,
                         boolean silencePenalty) {
    }

    /** 推理过程效率（过程维）：重复调用按发生位计数（首现不算）；errorToolCalls 供运行门错误率（M5-08 分支4） */
    public record Process(int totalToolCalls,
                          int duplicateToolCalls,
                          int errorToolCalls) {
    }

    /** 工具调用精度（工具维）：rejectedCalls = approval_required 拒绝记录数 */
    public record Tool(int registeredHits,
                       int hallucinatedCalls,
                       int rejectedCalls) {
    }

    /** 成本与延迟（成本维）：usage 缺失时 token 三项记 0 + usageMissing=true */
    public record Cost(long latencyMs,
                       long promptTokens,
                       long completionTokens,
                       long totalTokens,
                       boolean usageMissing) {
    }

    /** 多 Agent 协作（协作维）：claims 按裁决三态分布（v1 最小面） */
    public record Collaboration(int claimsTrue,
                                int claimsFalse,
                                int claimsUnknown) {
    }

    /** 鲁棒性与安全（安全维）：policyRejections 与工具维 rejectedCalls 同源（approval_required），镜头不同不混算 */
    public record Safety(int policyRejections,
                         boolean redteamCase) {
    }
}
