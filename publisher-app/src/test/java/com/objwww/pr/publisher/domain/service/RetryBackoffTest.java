package com.objwww.pr.publisher.domain.service;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class RetryBackoffTest {

    private final RetryBackoff backoff = new RetryBackoff();
    private final Instant now = Instant.parse("2026-01-01T00:00:00Z");

    @Test
    void exponentialWithCap() {
        assertEquals(now.plusSeconds(30), backoff.nextAttemptAt(1, now));
        assertEquals(now.plusSeconds(60), backoff.nextAttemptAt(2, now));
        assertEquals(now.plusSeconds(120), backoff.nextAttemptAt(3, now));
        // 封顶 10 分钟
        assertEquals(now.plusSeconds(600), backoff.nextAttemptAt(10, now));
        assertEquals(now.plusSeconds(600), backoff.nextAttemptAt(100, now));
    }

    @Test
    void invalidAttemptRejected() {
        assertThrows(IllegalArgumentException.class, () -> backoff.nextAttemptAt(0, now));
    }
}
