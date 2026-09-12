package com.objwww.pr.control.alert.domain.tool;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * MC24/P0-2 查询规范化器边界面：白名单内（字符串值空白折叠）必折叠、白名单外
 * （大小写/数字格式/非字符串值）刻意不动——折叠面扩大 = "同一查询"误判面扩大。
 */
class ArgsNormalizerTest {

    @Test
    @DisplayName("白名单内：trim + 连续空白（含换行/制表）折叠为单空格")
    void whitespaceVariantsFoldToSameForm() {
        assertThat(ArgsNormalizer.normalize(Map.of("query", " up\n")))
                .isEqualTo(ArgsNormalizer.normalize(Map.of("query", "up")));
        assertThat(ArgsNormalizer.normalize(Map.of("query", "up\t\tand\tdeployment")))
                .isEqualTo(Map.of("query", "up and deployment"));
        assertThat(ArgsNormalizer.normalize(Map.of("q", "  a   b  "))).isEqualTo(Map.of("q", "a b"));
    }

    @Test
    @DisplayName("白名单外：大小写不折叠（PromQL/日志查询大小写敏感）")
    void caseIsNeverFolded() {
        assertThat(ArgsNormalizer.normalize(Map.of("q", "GET")))
                .isNotEqualTo(ArgsNormalizer.normalize(Map.of("q", "get")));
    }

    @Test
    @DisplayName("白名单外：数字串格式不折叠（'1.0' ≠ '1.00' 按原文比较）")
    void numberStringFormatIsNeverFolded() {
        assertThat(ArgsNormalizer.normalize(Map.of("threshold", "1.0")))
                .isNotEqualTo(ArgsNormalizer.normalize(Map.of("threshold", "1.00")));
    }

    @Test
    @DisplayName("递归面：嵌套 Map/List 全层折叠；非字符串值原样")
    void foldsRecursivelyThroughMapsAndLists() {
        Map<String, Object> raw = Map.of("filter", List.of(
                Map.of("service", " checkout "), 3, true));
        @SuppressWarnings("unchecked")
        Map<String, Object> out = (Map<String, Object>) ArgsNormalizer.normalize(raw);
        @SuppressWarnings("unchecked")
        List<Object> filter = (List<Object>) out.get("filter");
        assertThat(filter.get(0)).isEqualTo(Map.of("service", "checkout"));
        assertThat(filter.get(1)).isEqualTo(3);
        assertThat(filter.get(2)).isEqualTo(true);
    }

    @Test
    @DisplayName("白名单外：null 透传（null 与空容器统一不属于空白折叠白名单）")
    void nullArgsPassThroughUnchanged() {
        assertThat(ArgsNormalizer.normalize(null)).isNull();
    }

    @Test
    @DisplayName("ActionDigest 接入面：空白变体 args → 同 digest；语义变体 → 必变")
    void actionDigestIsWhitespaceInvariantButSemanticSensitive() {
        ActionEnvelope cleanEnvelope = new ActionEnvelope("rca", "prometheus.instant",
                "1.0.0", "schema-hash", Map.of("query", "up"),
                "2026-09-10T00:00:00Z/2026-09-10T00:05:00Z", null);
        ActionEnvelope messyEnvelope = new ActionEnvelope("rca", "prometheus.instant",
                "1.0.0", "schema-hash", Map.of("query", " up \n"),
                "2026-09-10T00:00:00Z/2026-09-10T00:05:00Z", null);
        ActionEnvelope differentEnvelope = new ActionEnvelope("rca", "prometheus.instant",
                "1.0.0", "schema-hash", Map.of("query", "up and away"),
                "2026-09-10T00:00:00Z/2026-09-10T00:05:00Z", null);

        String clean = ActionDigest.of(cleanEnvelope);
        assertThat(ActionDigest.of(messyEnvelope))
                .as("同参数换措辞 = 同一语义身份（MC24 去重锚）").isEqualTo(clean);
        assertThat(ActionDigest.of(differentEnvelope))
                .as("语义不同的查询必须不同指纹").isNotEqualTo(clean);
    }
}
