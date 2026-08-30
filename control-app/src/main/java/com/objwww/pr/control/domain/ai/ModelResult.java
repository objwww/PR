package com.objwww.pr.control.domain.ai;

import java.util.Objects;

/**
 * 模型调用结果：正文 + token 用量 + 实际命中的模型（落账 review_run/step_attempt 用）。
 */
public record ModelResult(String content, TokenUsage tokenUsage, String actualModel) {

    public ModelResult {
        Objects.requireNonNull(content, "content");
        Objects.requireNonNull(tokenUsage, "tokenUsage");
        Objects.requireNonNull(actualModel, "actualModel");
    }
}
