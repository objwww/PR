package com.objwww.pr.shared;

import java.time.Instant;
import java.util.Objects;

/** HTTP 响应归一后的限流处置指令；普通 403 明确不是限流。 */
public sealed interface RetryDirective permits RetryDirective.HonorRetryAfter,
        RetryDirective.SecondaryLimitBackoff, RetryDirective.NotRateLimited {

    record HonorRetryAfter(long seconds) implements RetryDirective {
        public HonorRetryAfter {
            if (seconds <= 0) {
                throw new IllegalArgumentException("seconds 必须 > 0");
            }
        }
    }

    record SecondaryLimitBackoff() implements RetryDirective {
    }

    record NotRateLimited() implements RetryDirective {
    }

    /** 429 与 rate-limit 403 分流；其他响应（含普通 403）均为 NotRateLimited。 */
    static RetryDirective from(TypedResponse response, Instant now) {
        Objects.requireNonNull(response, "response");
        Objects.requireNonNull(now, "now");
        if (response.status() == 429) {
            return response.retryAfterSeconds() != null
                    ? new HonorRetryAfter(response.retryAfterSeconds())
                    : new SecondaryLimitBackoff();
        }
        if (response.status() == 403 && Long.valueOf(0L).equals(response.rateLimitRemaining())) {
            if (response.retryAfterSeconds() != null) {
                return new HonorRetryAfter(response.retryAfterSeconds());
            }
            if (response.rateLimitResetEpochSec() != null) {
                long seconds = response.rateLimitResetEpochSec() - now.getEpochSecond();
                if (seconds > 0) {
                    return new HonorRetryAfter(seconds);
                }
            }
            return new SecondaryLimitBackoff();
        }
        return new NotRateLimited();
    }

    default boolean isRateLimited() {
        return !(this instanceof NotRateLimited);
    }
}
