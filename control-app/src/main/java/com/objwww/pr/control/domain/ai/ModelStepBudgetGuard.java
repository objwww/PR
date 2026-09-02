package com.objwww.pr.control.domain.ai;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

/**
 * Step 级预算门卫（§4.4/附录 B）：纯领域组件，零框架依赖。
 *
 * <p>M0 既有 ModelBudgetGuard 退役并入本类：单次 maxTokens 校验 + post-call usage 校验
 * 合并为统一的四预算体系。
 *
 * <p>预算状态生命周期：每次 ModelGateway.complete() 新建一份（键=invocationId），
 * attempt 重跑 = 新预算。
 */
public final class ModelStepBudgetGuard {

    private final int maxPhysicalCallsPerStep;
    private final int maxPromptTokensPerCall;
    private final int maxCompletionTokensPerCall;
    private final int maxTotalTokensPerStep;
    private final Duration gatewayTotalDeadline;
    private final Duration inlineRetryMaxDelay;

    // 状态（每次 complete() 新建）
    private int physicalCallsUsed = 0;
    private int stepTotalTokensUsed = 0;
    private final Instant startedAt;

    public ModelStepBudgetGuard(
            int maxPhysicalCallsPerStep,
            int maxPromptTokensPerCall,
            int maxCompletionTokensPerCall,
            int maxTotalTokensPerStep,
            Duration gatewayTotalDeadline,
            Duration inlineRetryMaxDelay,
            Instant startedAt
    ) {
        if (maxPhysicalCallsPerStep <= 0) {
            throw new IllegalArgumentException("maxPhysicalCallsPerStep must be positive: " + maxPhysicalCallsPerStep);
        }
        if (maxPromptTokensPerCall <= 0) {
            throw new IllegalArgumentException("maxPromptTokensPerCall must be positive: " + maxPromptTokensPerCall);
        }
        if (maxCompletionTokensPerCall <= 0) {
            throw new IllegalArgumentException("maxCompletionTokensPerCall must be positive: " + maxCompletionTokensPerCall);
        }
        if (maxTotalTokensPerStep <= 0) {
            throw new IllegalArgumentException("maxTotalTokensPerStep must be positive: " + maxTotalTokensPerStep);
        }
        Objects.requireNonNull(gatewayTotalDeadline, "gatewayTotalDeadline");
        Objects.requireNonNull(inlineRetryMaxDelay, "inlineRetryMaxDelay");
        Objects.requireNonNull(startedAt, "startedAt");

        this.maxPhysicalCallsPerStep = maxPhysicalCallsPerStep;
        this.maxPromptTokensPerCall = maxPromptTokensPerCall;
        this.maxCompletionTokensPerCall = maxCompletionTokensPerCall;
        this.maxTotalTokensPerStep = maxTotalTokensPerStep;
        this.gatewayTotalDeadline = gatewayTotalDeadline;
        this.inlineRetryMaxDelay = inlineRetryMaxDelay;
        this.startedAt = startedAt;
    }

    /**
     * 预估 prompt tokens（调用前预检）。
     */
    public void checkPromptEstimate(int estimatedPromptTokens) {
        if (estimatedPromptTokens > maxPromptTokensPerCall) {
            throw new ModelBudgetExceededException(
                    "Estimated prompt tokens " + estimatedPromptTokens
                            + " exceeds max-prompt-tokens-per-call " + maxPromptTokensPerCall);
        }
    }

    /**
     * 检查剩余物理调用预算（G1 闸门）。
     */
    public boolean hasRemainingCalls() {
        return physicalCallsUsed < maxPhysicalCallsPerStep;
    }

    /**
     * 记录一次物理调用开始（失败调用也计数）。
     */
    public void recordCallStarted() {
        physicalCallsUsed++;
    }

    /**
     * post-call 结算：累计 usage，检查是否超 Step 总预算。
     *
     * @param usage 实际用量
     * @throws ModelBudgetExceededException 超 Step 总预算（usage 已落账，结果丢弃）
     */
    public void recordUsageAndCheck(TokenUsage usage) {
        Objects.requireNonNull(usage, "usage");
        stepTotalTokensUsed += usage.totalTokens();

        if (stepTotalTokensUsed > maxTotalTokensPerStep) {
            throw new ModelBudgetExceededException(
                    "Step total tokens " + stepTotalTokensUsed
                            + " exceeds max-total-tokens-per-step " + maxTotalTokensPerStep);
        }
    }

    /**
     * 检查剩余 deadline（G2 闸门）。
     */
    public Duration remainingDeadline(Instant now) {
        Objects.requireNonNull(now, "now");
        Duration elapsed = Duration.between(startedAt, now);
        Duration remaining = gatewayTotalDeadline.minus(elapsed);
        return remaining.isNegative() ? Duration.ZERO : remaining;
    }

    /**
     * 判断等待时长是否超过 inline 上限（G3 闸门）。
     */
    public boolean exceedsInlineDelay(Duration delay) {
        return delay.compareTo(inlineRetryMaxDelay) > 0;
    }

    public int maxCompletionTokensPerCall() {
        return maxCompletionTokensPerCall;
    }

    public int physicalCallsUsed() {
        return physicalCallsUsed;
    }

    public int stepTotalTokensUsed() {
        return stepTotalTokensUsed;
    }
}
