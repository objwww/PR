package com.objwww.pr.publisher.domain.service;

import com.objwww.pr.shared.RetryAfterParser;
import com.objwww.pr.shared.RetryDirective;
import com.objwww.pr.shared.TypedResponse;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * EX-20/EX-25（M2 方案 §11/L4，I23）publisher 侧收口：Retry-After 解析边界值 +
 * 限流 403 无 reset 的下限路径。
 *
 * <p>主干覆盖映射（已存在，不重复）：秒/HTTP-date 解析与 429/限流 403/普通 403 三分流见
 * shared-kernel {@code RetryDirectiveTest}；指数退避/60s 下限/15min clamp 见
 * {@link RetryBackoffTest}；适配器 HTTP 边界解析见 {@code GitHubWriteAdapterHeadersTest}。
 */
class RateLimitBackoffEdgeTest {

    private static final Instant NOW = Instant.parse("2026-08-31T00:00:00Z");
    private final RetryBackoff backoff = new RetryBackoff();

    @Test
    void parserBoundaryValuesAllTreatedAsNoHeader() {
        // EX-20：零值/负值/非法/过去时一律归 null（= 无头语义）；超 long 溢出不得崩溃
        assertNull(RetryAfterParser.parseSeconds("0", NOW));
        assertNull(RetryAfterParser.parseSeconds("-30", NOW));
        assertNull(RetryAfterParser.parseSeconds("not-a-number", NOW));
        assertNull(RetryAfterParser.parseSeconds("  ", NOW));
        // 过去时 HTTP-date（恰为 now 与明显过去各一条）
        assertNull(RetryAfterParser.parseSeconds("Mon, 31 Aug 2026 00:00:00 GMT", NOW));
        assertNull(RetryAfterParser.parseSeconds("Sun, 31 Aug 2025 00:00:00 GMT", NOW));
        // 超 Long 上限的数字串：Long.parseLong 溢出 → 回落 date 解析失败 → null，不抛异常
        assertNull(RetryAfterParser.parseSeconds("99999999999999999999", NOW));
    }

    @Test
    void headerless429FallsTo60sFloorAndInvalidEqualsHeaderless() {
        // EX-20：429 无头 → SecondaryLimitBackoff → 调度下限 60s（不早于 now+60）
        RetryDirective headerless = RetryDirective.from(TypedResponse.ofStatus(429), NOW);
        assertInstanceOf(RetryDirective.SecondaryLimitBackoff.class, headerless);
        assertEquals(NOW.plusSeconds(60), backoff.nextAttemptAt(1, NOW, headerless));
        // 非法头 = 无头：解析归 null 后走同一 from() 分支，指令对象同型
        assertInstanceOf(RetryDirective.SecondaryLimitBackoff.class,
                RetryDirective.from(TypedResponse.ofStatus(429)
                        .withRateLimitHeaders(
                                RetryAfterParser.parseSeconds("bogus", NOW), null, null), NOW));
    }

    @Test
    void oversizedRetryAfterClampsTo15minWithoutOverflow() {
        // EX-20：超大值 clamp 15min；Long.MAX_VALUE 不溢出不崩溃
        assertEquals(NOW.plusSeconds(900), backoff.nextAttemptAt(1, NOW,
                new RetryDirective.HonorRetryAfter(3600)));
        assertEquals(NOW.plusSeconds(900), backoff.nextAttemptAt(1, NOW,
                new RetryDirective.HonorRetryAfter(Long.MAX_VALUE)));
        // 小头不压制指数下限：max(30s 指数, 5s 头) = 30s，仍不早于 now+retryAfter
        assertEquals(NOW.plusSeconds(30), backoff.nextAttemptAt(1, NOW,
                new RetryDirective.HonorRetryAfter(5)));
    }

    @Test
    void rateLimited403WithoutUsableResetFallsTo60sFloor() {
        // EX-25：限流 403（x-ratelimit-remaining: 0）无 Retry-After 且无 reset → 60s 下限
        RetryDirective noReset = RetryDirective.from(
                TypedResponse.ofStatus(403).withRateLimitHeaders(null, 0L, null), NOW);
        assertInstanceOf(RetryDirective.SecondaryLimitBackoff.class, noReset);
        assertEquals(NOW.plusSeconds(60), backoff.nextAttemptAt(1, NOW, noReset));
        // reset 在过去 → 同样落 60s 下限，不得给出负/零退避
        RetryDirective pastReset = RetryDirective.from(TypedResponse.ofStatus(403)
                .withRateLimitHeaders(null, 0L, NOW.getEpochSecond() - 10), NOW);
        assertInstanceOf(RetryDirective.SecondaryLimitBackoff.class, pastReset);
        // 对照：remaining > 0 的 403 是普通 403 → NotRateLimited 权限路径，不退避
        assertInstanceOf(RetryDirective.NotRateLimited.class,
                RetryDirective.from(TypedResponse.ofStatus(403)
                        .withRateLimitHeaders(null, 500L, null), NOW));
    }
}
