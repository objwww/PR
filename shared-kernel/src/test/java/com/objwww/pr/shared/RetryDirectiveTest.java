package com.objwww.pr.shared;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;

class RetryDirectiveTest {

    private static final Instant NOW = Instant.parse("2026-08-31T00:00:00Z");

    @Test
    void parsesRetryAfterSecondsAndHttpDate() {
        assertEquals(120L, RetryAfterParser.parseSeconds("120", NOW));
        assertEquals(60L, RetryAfterParser.parseSeconds(
                "Mon, 31 Aug 2026 00:01:00 GMT", NOW));
        assertNull(RetryAfterParser.parseSeconds("bad", NOW));
        assertNull(RetryAfterParser.parseSeconds("0", NOW));
    }

    @Test
    void distinguishes429RateLimit403AndOrdinary403() {
        RetryDirective retryAfter = RetryDirective.from(
                TypedResponse.ofStatus(429).withRateLimitHeaders(90L, null, null), NOW);
        assertEquals(90L, assertInstanceOf(
                RetryDirective.HonorRetryAfter.class, retryAfter).seconds());

        RetryDirective reset = RetryDirective.from(
                TypedResponse.ofStatus(403).withRateLimitHeaders(null, 0L,
                        NOW.getEpochSecond() + 70), NOW);
        assertEquals(70L, assertInstanceOf(
                RetryDirective.HonorRetryAfter.class, reset).seconds());

        assertInstanceOf(RetryDirective.SecondaryLimitBackoff.class,
                RetryDirective.from(TypedResponse.ofStatus(429), NOW));
        assertInstanceOf(RetryDirective.NotRateLimited.class,
                RetryDirective.from(TypedResponse.ofStatus(403), NOW));
    }
}
