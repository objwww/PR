package com.objwww.pr.control.alert.domain.tool;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * UT-AM4-15a：CanonicalJson——字段顺序无关 / 嵌套 / 数值归一 / 范围字段变化摘要必变。
 */
class CanonicalJsonTest {

    @Test
    void fieldOrderDoesNotChangeCanonicalForm() {
        Map<String, Object> a = new LinkedHashMap<>();
        a.put("z", 1);
        a.put("a", "x");
        a.put("m", true);
        Map<String, Object> b = new LinkedHashMap<>();
        b.put("m", true);
        b.put("a", "x");
        b.put("z", 1);

        assertThat(CanonicalJson.canonicalize(a))
                .isEqualTo(CanonicalJson.canonicalize(b))
                .isEqualTo("{\"a\":\"x\",\"m\":true,\"z\":1}");
    }

    @Test
    void nestedStructuresAreSortedRecursively() {
        Map<String, Object> inner = new LinkedHashMap<>();
        inner.put("b", List.of(3, 1, 2)); // 数组保序（语义有序）
        inner.put("a", null);
        Map<String, Object> outer = new LinkedHashMap<>();
        outer.put("outer", inner);
        outer.put("list", List.of(Map.of("y", 1, "x", 2), "s"));

        assertThat(CanonicalJson.canonicalize(outer))
                .isEqualTo("{\"list\":[{\"x\":2,\"y\":1},\"s\"],"
                        + "\"outer\":{\"a\":null,\"b\":[3,1,2]}}");
    }

    @Test
    void numbersAreNormalized() {
        assertThat(CanonicalJson.canonicalize(1)).isEqualTo("1");
        assertThat(CanonicalJson.canonicalize(1L)).isEqualTo("1");
        assertThat(CanonicalJson.canonicalize(1.0)).isEqualTo("1");
        assertThat(CanonicalJson.canonicalize(new java.math.BigDecimal("1.00"))).isEqualTo("1");
        assertThat(CanonicalJson.canonicalize(0.0001)).isEqualTo("0.0001");
        assertThat(CanonicalJson.canonicalize(-0.0)).isEqualTo("0");
        assertThat(CanonicalJson.canonicalize(0)).isEqualTo("0");
    }

    @Test
    void digestIsStableAndOrderIndependent() {
        Map<String, Object> a = Map.of("from", "now-1h", "to", "now", "step", "15s");
        Map<String, Object> b = new LinkedHashMap<>();
        b.put("step", "15s");
        b.put("to", "now");
        b.put("from", "now-1h");
        assertThat(CanonicalJson.sha256(a)).isEqualTo(CanonicalJson.sha256(b));
    }

    @Test
    void timeRangeFieldChangeMustChangeDigest() {
        // 范围字段（timeRange/scope 语义）变化 → 摘要必变，禁近似伪造（INV-AM4-6 前置）
        Map<String, Object> base = Map.of("query", "up", "from", "2026-09-05T00:00:00Z");
        Map<String, Object> shifted = Map.of("query", "up", "from", "2026-09-05T01:00:00Z");
        assertThat(CanonicalJson.sha256(base)).isNotEqualTo(CanonicalJson.sha256(shifted));
    }

    @Test
    void stringEscapingIsCanonical() {
        assertThat(CanonicalJson.canonicalize("a\"b\\c\n")).isEqualTo("\"a\\\"b\\\\c\\n\"");
        assertThat(CanonicalJson.canonicalize("\u0001")).isEqualTo("\"\\u0001\"");
    }

    @Test
    void unsupportedTypesAreRejected() {
        assertThatThrownBy(() -> CanonicalJson.canonicalize(new int[]{1}))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> CanonicalJson.canonicalize(Map.of(1, "x")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> CanonicalJson.canonicalize(new Object()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void emptyStructures() {
        assertThat(CanonicalJson.canonicalize(Map.of())).isEqualTo("{}");
        assertThat(CanonicalJson.canonicalize(List.of())).isEqualTo("[]");
        assertThat(CanonicalJson.canonicalize(null)).isEqualTo("null");
    }
}
