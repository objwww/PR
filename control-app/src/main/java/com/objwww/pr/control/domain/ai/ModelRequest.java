package com.objwww.pr.control.domain.ai;

import java.time.Duration;
import java.util.Objects;

/**
 * 模型调用请求（domain 值对象）：不含任何供应商概念（I-领域纯净）。
 * maxTokens 是本次调用的 completion 预算，timeout 是单次调用硬超时。
 */
public record ModelRequest(String prompt, int maxTokens, Duration timeout) {

    public ModelRequest {
        if (prompt == null || prompt.isBlank()) {
            throw new IllegalArgumentException("prompt 不能为空");
        }
        if (maxTokens <= 0) {
            throw new IllegalArgumentException("maxTokens 必须为正: " + maxTokens);
        }
        Objects.requireNonNull(timeout, "timeout");
        if (timeout.isNegative() || timeout.isZero()) {
            throw new IllegalArgumentException("timeout 必须为正: " + timeout);
        }
    }
}
