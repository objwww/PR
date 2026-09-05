package com.objwww.pr.control.alert.domain.tool;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * UT-AM4-15a：InternalCanonicalJsonV1——字段顺序无关 / 嵌套 / 数值归一 / 范围字段变化摘要必变。
 * 自研内部算法（internal-v1），不断言 RFC 8785/JCS 兼容性。
 */
class InternalCanonicalJsonV1Test {

    @Test
    void versionConstantIsInternalV1() {
        assertThat(InternalCanonicalJsonV1.VERSION).isEqualTo("internal-v1");
    }

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

        assertThat(InternalCanonicalJsonV1.canonicalize(a))
                .isEqualTo(InternalCanonicalJsonV1.canonicalize(b))
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

        assertThat(InternalCanonicalJsonV1.canonicalize(outer))
                .isEqualTo("{\"list\":[{\"x\":2,\"y\":1},\"s\"],"
                        + "\"outer\":{\"a\":null,\"b\":[3,1,2]}}");
    }

    @Test
    void numbersAreNormalized() {
        assertThat(InternalCanonicalJsonV1.canonicalize(1)).isEqualTo("1");
        assertThat(InternalCanonicalJsonV1.canonicalize(1L)).isEqualTo("1");
        assertThat(InternalCanonicalJsonV1.canonicalize(1.0)).isEqualTo("1");
        assertThat(InternalCanonicalJsonV1.canonicalize(new java.math.BigDecimal("1.00"))).isEqualTo("1");
        assertThat(InternalCanonicalJsonV1.canonicalize(0.0001)).isEqualTo("0.0001");
        assertThat(InternalCanonicalJsonV1.canonicalize(-0.0)).isEqualTo("0");
        assertThat(InternalCanonicalJsonV1.canonicalize(0)).isEqualTo("0");
    }

    @Test
    void digestIsStableAndOrderIndependent() {
        Map<String, Object> a = Map.of("from", "now-1h", "to", "now", "step", "15s");
        Map<String, Object> b = new LinkedHashMap<>();
        b.put("step", "15s");
        b.put("to", "now");
        b.put("from", "now-1h");
        assertThat(InternalCanonicalJsonV1.sha256(a))
                .isEqualTo(InternalCanonicalJsonV1.sha256(b));
    }

    @Test
    void timeRangeFieldChangeMustChangeDigest() {
        // 范围字段变化 → 摘要必变，禁近似伪造（INV-AM4-6 前置）
        Map<String, Object> base = Map.of("query", "up", "from", "2026-09-05T00:00:00Z");
        Map<String, Object> shifted = Map.of("query", "up", "from", "2026-09-05T01:00:00Z");
        assertThat(InternalCanonicalJsonV1.sha256(base))
                .isNotEqualTo(InternalCanonicalJsonV1.sha256(shifted));
    }

    @Test
    void stringEscapingIsCanonical() {
        assertThat(InternalCanonicalJsonV1.canonicalize("a\"b\\c\n"))
                .isEqualTo("\"a\\\"b\\\\c\\n\"");
        assertThat(InternalCanonicalJsonV1.canonicalize("\u0001"))
                .isEqualTo("\"\\u0001\"");
    }

    @Test
    void unsupportedTypesAreRejected() {
        assertThatThrownBy(() -> InternalCanonicalJsonV1.canonicalize(new int[]{1}))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> InternalCanonicalJsonV1.canonicalize(Map.of(1, "x")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> InternalCanonicalJsonV1.canonicalize(new Object()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void emptyStructures() {
        assertThat(InternalCanonicalJsonV1.canonicalize(Map.of())).isEqualTo("{}");
        assertThat(InternalCanonicalJsonV1.canonicalize(List.of())).isEqualTo("[]");
        assertThat(InternalCanonicalJsonV1.canonicalize(null)).isEqualTo("null");
    }
}
