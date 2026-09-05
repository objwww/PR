package com.objwww.pr.control.alert.domain.tool;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * UT-AM4-15b：ActionDigest——sha256(toolName + canonicalJson(args) + scope) 纯函数。
 */
class ActionDigestTest {

    private static Map<String, Object> args() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("query", "rate(http_errors_total[5m])");
        m.put("from", "2026-09-05T00:00:00Z");
        return m;
    }

    @Test
    void sameInputsSameDigest() {
        assertThat(ActionDigest.of("prometheus_query", args(), "incident-1"))
                .isEqualTo(ActionDigest.of("prometheus_query", args(), "incident-1"));
    }

    @Test
    void argFieldOrderDoesNotChangeDigest() {
        Map<String, Object> reordered = new LinkedHashMap<>();
        reordered.put("from", "2026-09-05T00:00:00Z");
        reordered.put("query", "rate(http_errors_total[5m])");
        assertThat(ActionDigest.of("prometheus_query", reordered, "incident-1"))
                .isEqualTo(ActionDigest.of("prometheus_query", args(), "incident-1"));
    }

    @Test
    void anySegmentChangeChangesDigest() {
        String base = ActionDigest.of("prometheus_query", args(), "incident-1");
        assertThat(ActionDigest.of("prometheus_query_range", args(), "incident-1"))
                .as("toolName 变化").isNotEqualTo(base);
        Map<String, Object> changedArgs = new LinkedHashMap<>(args());
        changedArgs.put("query", "rate(http_errors_total[15m])");
        assertThat(ActionDigest.of("prometheus_query", changedArgs, "incident-1"))
                .as("args 变化").isNotEqualTo(base);
        assertThat(ActionDigest.of("prometheus_query", args(), "incident-2"))
                .as("scope 变化").isNotEqualTo(base);
    }

    @Test
    void digestIs64LowerHex() {
        assertThat(ActionDigest.of("t", Map.of(), "s")).matches("[0-9a-f]{64}");
    }

    @Test
    void blankToolNameOrScopeRejected() {
        assertThatThrownBy(() -> ActionDigest.of("", args(), "s"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ActionDigest.of("t", args(), null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void concatenationBoundaryIsUnambiguous() {
        // "ab"+"c" 与 "a"+"bc" 不得碰撞（分隔符纪律）
        assertThat(ActionDigest.of("ab", Map.of(), "c"))
                .isNotEqualTo(ActionDigest.of("a", Map.of(), "bc"));
    }
}
