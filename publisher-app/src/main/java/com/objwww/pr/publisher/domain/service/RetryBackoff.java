package com.objwww.pr.publisher.domain.service;

import com.objwww.pr.shared.RetryDirective;

import java.time.Instant;
import java.util.Objects;

/**
 * 重试退避（EX-01，B13 半步）：指数退避 30s 起、倍增、封顶 10 分钟。
 * 纯函数，attempt 为"已完成的失败次数"（首次失败 = 1）。
 */
public final class RetryBackoff {

    static final long BASE_SECONDS = 30;
    static final long MAX_SECONDS = 600;
    static final long SECONDARY_LIMIT_MIN_SECONDS = 60;
    static final long RETRY_AFTER_MAX_SECONDS = 900;
    private static final int MAX_SHIFT = 5;

    public Instant nextAttemptAt(int failedAttempts, Instant now) {
        return nextAttemptAt(failedAttempts, now, new RetryDirective.NotRateLimited());
    }

    public Instant nextAttemptAt(int failedAttempts, Instant now, RetryDirective directive) {
        if (failedAttempts < 1) {
            throw new IllegalArgumentException("failedAttempts 必须 >= 1: " + failedAttempts);
        }
        Objects.requireNonNull(now, "now");
        Objects.requireNonNull(directive, "directive");
        long delay = Math.min(BASE_SECONDS << Math.min(failedAttempts - 1, MAX_SHIFT), MAX_SECONDS);
        if (directive instanceof RetryDirective.HonorRetryAfter honor) {
            delay = Math.max(delay, Math.min(honor.seconds(), RETRY_AFTER_MAX_SECONDS));
        } else if (directive instanceof RetryDirective.SecondaryLimitBackoff) {
            delay = Math.max(delay, SECONDARY_LIMIT_MIN_SECONDS);
        }
        return now.plusSeconds(delay);
    }
}
