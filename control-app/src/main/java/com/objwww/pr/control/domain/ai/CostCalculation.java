package com.objwww.pr.control.domain.ai;

/**
 * 一次调用的成本计算结果（§4.8）：成本 + 当时生效的价格快照。
 * 全 null = 不估算（无单价配置 / usage 缺失 / 算术溢出——fail-closed，绝不写负数或回绕值）。
 */
public record CostCalculation(
        Long costMicros,
        String currency,
        String pricingVersion,
        Long inputPriceMicrosPer1k,
        Long outputPriceMicrosPer1k
) {
    /** 不估算（R-M4：token 计数照常，成本缺省） */
    public static final CostCalculation NOT_PRICED = new CostCalculation(null, null, null, null, null);

    public boolean priced() {
        return costMicros != null;
    }
}
