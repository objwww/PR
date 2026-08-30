package com.objwww.pr.publisher.domain.service;

import java.time.Instant;

/**
 * 重试退避（EX-01，B13 半步）：指数退避 30s 起、倍增、封顶 10 分钟。
 * 纯函数，attempt 为"已完成的失败次数"（首次失败 = 1）。
 */
public final class RetryBackoff {

    static final long BASE_SECONDS = 30;
    static final long MAX_SECONDS = 600;
    private static final int MAX_SHIFT = 5;

    public Instant nextAttemptAt(int failedAttempts, Instant now) {
        if (failedAttempts < 1) {
            throw new IllegalArgumentException("failedAttempts 必须 >= 1: " + failedAttempts);
        }
        long delay = Math.min(BASE_SECONDS << Math.min(failedAttempts - 1, MAX_SHIFT), MAX_SECONDS);
        return now.plusSeconds(delay);
    }
}
