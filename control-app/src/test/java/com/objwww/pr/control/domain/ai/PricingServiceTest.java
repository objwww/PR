package com.objwww.pr.control.domain.ai;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * PricingService（§4.8）：cost = (prompt×in + completion×out)/1000，单次除法向零取整；
 * usage 缺失/无单价配置/算术溢出 → NOT_PRICED（fail-closed 不抛）；结果携带价格快照。
 */
class PricingServiceTest {

    private static final PricingService.PriceEntry PRICE =
            new PricingService.PriceEntry("price-2026-01", "CNY", 2_000, 4_000);

    private static PricingService serviceWith(PricingService.PriceEntry entry) {
        return new PricingService(Map.of("model-a", entry));
    }

    @Test
    void formulaIsPromptTimesInPlusCompletionTimesOutDividedBy1000() {
        // (1500×2000 + 500×4000)/1000 = (3_000_000 + 2_000_000)/1000 = 5000 micros
        CostCalculation cost = serviceWith(PRICE)
                .calculate("model-a", new TokenUsage(1500, 500, 2000), false);
        assertThat(cost.priced()).isTrue();
        assertThat(cost.costMicros()).isEqualTo(5_000L);
    }

    @Test
    void divisionIsSingleAndTruncatesTowardZero() {
        // 分项预除会得 0+0=0；单次除法 (600+600)/1000=1 —— 向零取整只发生一次
        PricingService s = serviceWith(new PricingService.PriceEntry("v", "CNY", 1, 1));
        CostCalculation cost = s.calculate("model-a", new TokenUsage(600, 600, 1200), false);
        assertThat(cost.costMicros()).isEqualTo(1L);
    }

    @Test
    void usageMissingIsNotPricedEvenWithUsagePresent() {
        CostCalculation cost = serviceWith(PRICE)
                .calculate("model-a", new TokenUsage(100, 100, 200), true);
        assertThat(cost).isEqualTo(CostCalculation.NOT_PRICED);
        assertThat(cost.priced()).isFalse();
    }

    @Test
    void nullUsageIsNotPriced() {
        assertThat(serviceWith(PRICE).calculate("model-a", null, false))
                .isEqualTo(CostCalculation.NOT_PRICED);
    }

    /**
     * R4/BA-115：模型无价目配置 ≠ usage 缺失——"有 usage 无价"落 UNPRICED
     * （pricingVersion='unpriced'，钱数仍 null 不猜），账面可与 usage_missing 区分；
     * NOT_PRICED（全 null）只留给 usage 缺失/溢出。
     */
    @Test
    void missingPriceConfigIsUnpricedWithUsagePresent() {
        CostCalculation cost = serviceWith(PRICE)
                .calculate("model-unknown", new TokenUsage(1, 1, 2), false);
        assertThat(cost.priced()).as("钱数仍缺省不猜").isFalse();
        assertThat(cost.costMicros()).isNull();
        assertThat(cost.currency()).isNull();
        assertThat(cost.pricingVersion()).as("显式 unpriced 语义").isEqualTo("unpriced");
        assertThat(cost.inputPriceMicrosPer1k()).isNull();
        assertThat(cost.outputPriceMicrosPer1k()).isNull();
        // 空价格表（含 null 入参）同语义
        CostCalculation empty = new PricingService(null)
                .calculate("model-a", new TokenUsage(1, 1, 2), false);
        assertThat(empty.pricingVersion()).isEqualTo("unpriced");
        assertThat(empty.costMicros()).isNull();
    }

    /** 双零价目：配置装配面（M3）已过滤不进单价表；若直达服务，0 token 成本 0 属定价事实（行为保持） */
    @Test
    void zeroPriceEntryYieldsZeroCostAsPricingFact() {
        PricingService s = serviceWith(new PricingService.PriceEntry("v", "CNY", 0, 0));
        CostCalculation cost = s.calculate("model-a", new TokenUsage(100, 100, 200), false);
        assertThat(cost.priced()).isTrue();
        assertThat(cost.costMicros()).isZero();
    }

    @Test
    void arithmeticOverflowIsNotPricedAndDoesNotThrow() {
        PricingService s = serviceWith(new PricingService.PriceEntry("v", "CNY", 2, 0));
        TokenUsage huge = new TokenUsage(Long.MAX_VALUE, 0, Long.MAX_VALUE);
        assertThatCode(() -> {
            CostCalculation cost = s.calculate("model-a", huge, false);
            assertThat(cost).isEqualTo(CostCalculation.NOT_PRICED);
        }).doesNotThrowAnyException();
    }

    @Test
    void resultCarriesPricingSnapshot() {
        CostCalculation cost = serviceWith(PRICE)
                .calculate("model-a", new TokenUsage(100, 50, 150), false);
        assertThat(cost.pricingVersion()).isEqualTo("price-2026-01");
        assertThat(cost.currency()).isEqualTo("CNY");
        assertThat(cost.inputPriceMicrosPer1k()).isEqualTo(2_000L);
        assertThat(cost.outputPriceMicrosPer1k()).isEqualTo(4_000L);
    }
}
