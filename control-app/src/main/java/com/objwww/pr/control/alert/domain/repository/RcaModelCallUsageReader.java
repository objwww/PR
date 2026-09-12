package com.objwww.pr.control.alert.domain.repository;

import java.util.Optional;
import java.util.UUID;

/**
 * rca_model_call 的 run 级用量/费用聚合读面（§三.5 调查详情费用透出；
 * RV08 红线：费用数据源 = RCA 域 rca_model_call（V48），不是 PR 域旧模型账本表。
 *
 * <p>计入口径（诚实语义，V48 头注同律——usage 缺失不猜零）：
 * <ul>
 *   <li>callCount：该 run 落账的全部物理调用行（每物理请求单独一行，§二.4），
 *       含 PENDING/FAILED/UNKNOWN——行在 = 调用已取发送资格，不抹除；</li>
 *   <li>tokensIn/tokensOut：仅 SUCCESS 且 usage 已回报的行贡献；未回报行不猜零，
 *       也不计入合计（合计 = 已回报部分的下限）；</li>
 *   <li>costMicros：sum(cost_micros)；全部行未定价/未结算 → null（不伪 0）；</li>
 *   <li>usageMissing：cost_micros IS NULL 的行数 = 费用未决调用数（usage 缺失的
 *       SUCCESS / UNPRICED / UNKNOWN / FAILED / 未结算 PENDING 同计入——失败与
 *       不确定行是否已被供应商计费无账面依据，如实归入"费用为下限"口径）；</li>
 *   <li>currency/pricingVersion：已定价行取值一致时透出，不一致或无已定价行 → null
 *       （混合币种不硬拼合计语义）。</li>
 * </ul>
 * 无行 → {@link Optional#empty()}（前端显"无模型调用"而非全 0）。
 */
public interface RcaModelCallUsageReader {

    /** run 级聚合（实现方自含短事务；无行 → empty） */
    Optional<RunUsage> summarizeByRun(UUID runId);

    /** run 级用量/费用聚合投影（字段口径见接口头注） */
    record RunUsage(long callCount, long tokensIn, long tokensOut, Long costMicros,
                    long usageMissing, String currency, String pricingVersion) {
    }
}
