package com.objwww.pr.control.domain.ai;

/**
 * 单次调用 token 预算硬上限守卫（§6.6：超了 Step 失败，不降级安全步骤）。
 * 两道检查：① 调用前——请求申请的 maxTokens 不得超过硬上限；② 调用后——实际 completion
 * 用量不得超过该次请求预算。M3 模型治理（成本账本/熔断）之前的 M0 最小形态。
 */
public final class ModelBudgetGuard {

    /** 默认单次调用 completion 硬上限（M0 经验值；配置化属接线任务） */
    public static final int DEFAULT_HARD_MAX_TOKENS = 32_000;

    private final int hardMaxTokens;

    public ModelBudgetGuard() {
        this(DEFAULT_HARD_MAX_TOKENS);
    }

    public ModelBudgetGuard(int hardMaxTokens) {
        if (hardMaxTokens <= 0) {
            throw new IllegalArgumentException("hardMaxTokens 必须为正: " + hardMaxTokens);
        }
        this.hardMaxTokens = hardMaxTokens;
    }

    public int hardMaxTokens() {
        return hardMaxTokens;
    }

    /** 调用前校验：申请预算超硬上限即拒 */
    public void validate(ModelRequest request) {
        if (request.maxTokens() > hardMaxTokens) {
            throw new ModelBudgetExceededException(
                    "请求 maxTokens=" + request.maxTokens() + " 超过单次硬上限 " + hardMaxTokens);
        }
    }

    /** 调用后校验：实际 completion 用量超该次预算即判违约（结果应丢弃，Step FAILED） */
    public void checkUsage(ModelRequest request, TokenUsage usage) {
        if (usage.completionTokens() > request.maxTokens()) {
            throw new ModelBudgetExceededException(
                    "实际 completion=" + usage.completionTokens()
                            + " 超过本次预算 " + request.maxTokens());
        }
    }
}
