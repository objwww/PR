package com.objwww.pr.control.domain.ai;

import java.util.Map;
import java.util.Objects;

/**
 * 成本估算（§4.8，纯领域件，零框架注解——AFT-24）：
 *
 * <pre>
 * cost_micros = (prompt_tokens × input_price_micros_per_1k
 *              + completion_tokens × output_price_micros_per_1k) / 1000
 * </pre>
 *
 * <p>规则：long 精确算术（溢出 → fail-closed 返回 {@link CostCalculation#NOT_PRICED}）；
 * 除法向零取整（单次除法，不分项预除）；每条调用落当时生效的单价快照
 * （pricing_version + currency + 两个单价），配置改价后历史账单不被重新解释（I32/ST-61）；
 * usage 缺失 → NOT_PRICED（全 null）；单价未配置 → UNPRICED（pricing_version='unpriced'，
 * cost 仍 null 不造数——R4/BA-115：两态账面可区分）。
 */
public final class PricingService {

    /** 一个模型的单价表条目（配置铸造，不可变） */
    public record PriceEntry(String pricingVersion, String currency,
                             long inputMicrosPer1k, long outputMicrosPer1k) {
        public PriceEntry {
            Objects.requireNonNull(pricingVersion, "pricingVersion");
            Objects.requireNonNull(currency, "currency");
            if (inputMicrosPer1k < 0 || outputMicrosPer1k < 0) {
                throw new IllegalArgumentException("单价不能为负");
            }
        }
    }

    private final Map<String, PriceEntry> prices; // key = requestedModel

    public PricingService(Map<String, PriceEntry> prices) {
        this.prices = prices == null ? Map.of() : Map.copyOf(prices);
    }

    /**
     * 计算一次成功调用的成本。usage 缺失/算术溢出 → {@link CostCalculation#NOT_PRICED}
     * （全 null）；模型无单价配置 → {@link CostCalculation#UNPRICED}
     * （pricingVersion='unpriced'，钱数仍 null——R4：有 usage 无价与 usage 缺失账面可区分）。
     */
    public CostCalculation calculate(String requestedModel, TokenUsage usage, boolean usageMissing) {
        Objects.requireNonNull(requestedModel, "requestedModel");
        if (usageMissing || usage == null) {
            return CostCalculation.NOT_PRICED;
        }
        PriceEntry price = prices.get(requestedModel);
        if (price == null) {
            return CostCalculation.UNPRICED;
        }
        try {
            long inputCost = Math.multiplyExact(usage.promptTokens(), price.inputMicrosPer1k());
            long outputCost = Math.multiplyExact(usage.completionTokens(), price.outputMicrosPer1k());
            long costMicros = Math.addExact(inputCost, outputCost) / 1000;
            return new CostCalculation(costMicros, price.currency(), price.pricingVersion(),
                    price.inputMicrosPer1k(), price.outputMicrosPer1k());
        } catch (ArithmeticException overflow) {
            return CostCalculation.NOT_PRICED; // fail-closed：不写回绕值
        }
    }
}
