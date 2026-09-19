package com.objwww.pr.control.infrastructure.persistence;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/** FUP-04 锚点容差（纯函数面）：skew=0 行为不变；skew>0 回看容差秒；负值钳 0。 */
class PostgresRcaRunResolverTest {

    private static final Instant ANCHOR = Instant.parse("2026-09-19T01:16:32Z");

    @Test
    @DisplayName("skew=0（默认）：锚点原样使用——行为不变")
    void zeroSkewKeepsAnchor() {
        assertThat(PostgresRcaRunResolver.sinceFor(ANCHOR, 0)).isEqualTo(ANCHOR);
    }

    @Test
    @DisplayName("skew>0：匹配窗回看容差秒（早铸 run 可被匹配）")
    void skewWidensWindowBackwards() {
        assertThat(PostgresRcaRunResolver.sinceFor(ANCHOR, 60))
                .isEqualTo(ANCHOR.minusSeconds(60));
    }

    @Test
    @DisplayName("负容差钳到 0（不前移锚点）")
    void negativeSkewClampedToZero() {
        assertThat(PostgresRcaRunResolver.sinceFor(ANCHOR, -30)).isEqualTo(ANCHOR);
    }
}
