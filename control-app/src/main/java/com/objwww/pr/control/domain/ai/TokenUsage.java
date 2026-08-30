package com.objwww.pr.control.domain.ai;

/** token 用量（prompt / completion / total），落 step_attempt / 账本 payload 用 */
public record TokenUsage(long promptTokens, long completionTokens, long totalTokens) {

    public TokenUsage {
        if (promptTokens < 0 || completionTokens < 0 || totalTokens < 0) {
            throw new IllegalArgumentException("token 用量不能为负");
        }
    }
}
