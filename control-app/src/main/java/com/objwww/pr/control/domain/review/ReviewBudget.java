package com.objwww.pr.control.domain.review;

import java.time.Duration;
import java.util.Objects;

/**
 * 单次评审的确定性预算（§6.6）：文件数/总字节上限决定截断点，
 * maxCompletionTokens + timeout 是单次模型调用的硬约束（ModelBudgetGuard 执行）。
 * 预算截断必须记数（ReviewOutcome.truncatedFiles），不允许"悄悄不看"（§3 ReviewAgentLoop）。
 */
public record ReviewBudget(int maxFiles, long maxBytes, int maxCompletionTokens, Duration timeout) {

    /** M0 默认值：64 文件 / 512KB / 8K completion / 120s */
    public static final ReviewBudget DEFAULT =
            new ReviewBudget(64, 512L * 1024, 8_000, Duration.ofSeconds(120));

    public ReviewBudget {
        if (maxFiles <= 0) {
            throw new IllegalArgumentException("maxFiles 必须为正: " + maxFiles);
        }
        if (maxBytes <= 0) {
            throw new IllegalArgumentException("maxBytes 必须为正: " + maxBytes);
        }
        if (maxCompletionTokens <= 0) {
            throw new IllegalArgumentException("maxCompletionTokens 必须为正: " + maxCompletionTokens);
        }
        Objects.requireNonNull(timeout, "timeout");
        if (timeout.isNegative() || timeout.isZero()) {
            throw new IllegalArgumentException("timeout 必须为正: " + timeout);
        }
    }
}
