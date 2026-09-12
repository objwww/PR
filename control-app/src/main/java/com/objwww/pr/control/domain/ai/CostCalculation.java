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
    /** 不估算（R-M4：token 计数照常，成本缺省）——usage 缺失/算术溢出，全 null */
    public static final CostCalculation NOT_PRICED = new CostCalculation(null, null, null, null, null);

    /**
     * 有 usage 但模型无价目（R4/BA-115）：pricing_version='unpriced' 显式落账，
     * 钱数仍 NULL（红线：不填 0、不编价格）——账面与 usage_missing（NOT_PRICED）可区分，
     * 不复刻供应商"未定价=花费0"的账目缺陷（litellm issue #35525 同族）。
     */
    public static final CostCalculation UNPRICED = new CostCalculation(null, null, "unpriced", null, null);

    public boolean priced() {
        return costMicros != null;
    }
}
