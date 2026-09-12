package com.objwww.pr.control.alert.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 证据包 JSON 编解码器 L0（BA-22 去 Jackson 清账）：JSON 文本→中立树逐型断言、
 * 数字归一钉（1.0→"1"，InternalCanonicalJsonV1 契约）、键序字典序钉；解析失败
 * IllegalArgumentException（消息进 Validator 拒绝链）。
 */
class EvidencePackageJsonCodecTest {

    @Test
    @DisplayName("文本→中立树逐型：对象/数组/字符串/整数/浮点/布尔/null")
    void readTreeTypeByType() throws Exception {
        Object tree = EvidencePackageJsonCodec.readTree(
                "{\"s\":\"文本\",\"i\":2,\"f\":1.5,\"b\":true,\"n\":null,"
                        + "\"arr\":[\"a\",2,false,null],\"obj\":{\"k\":\"v\"}}");

        assertThat(tree).isInstanceOf(Map.class);
        Map<?, ?> map = (Map<?, ?>) tree;
        assertThat(map.get("s")).isEqualTo("文本");
        assertThat(map.get("i")).isEqualTo(2);
        assertThat(map.get("f")).isEqualTo(1.5);
        assertThat(map.get("b")).isEqualTo(true);
        assertThat(map.get("n")).isNull();
        assertThat(map.get("arr")).isEqualTo(java.util.Arrays.asList("a", 2, false, null));
        assertThat(map.get("obj")).isEqualTo(Map.of("k", "v"));
        // 零 Jackson 类型渗透：全部 java.util/java.lang
        assertThat(map.values().stream().filter(java.util.Map.class::isInstance).count()).isEqualTo(1);
    }

    @Test
    @DisplayName("toMap 桥：JsonNode 持有方（eval/release 应用层）→ 中立树")
    void toMapBridgeFromJsonNode() throws Exception {
        var node = new ObjectMapper().readTree("{\"a\":[1,{\"b\":\"c\"}]}");
        Map<String, Object> neutral = EvidencePackageJsonCodec.toMap(node);
        assertThat(neutral).isEqualTo(Map.of("a", List.of(1, Map.of("b", "c"))));
    }

    @Test
    @DisplayName("写出契约：字典序键序 + 数字归一（1.0→\"1\"）+ 无空白")
    void writeCanonicalContract() {
        var neutral = new java.util.LinkedHashMap<String, Object>();
        neutral.put("z", 1);
        neutral.put("a", 1.0);
        neutral.put("m", Map.of("k", List.of("x", "y")));

        assertThat(EvidencePackageJsonCodec.writeCanonical(neutral))
                .isEqualTo("{\"a\":1,\"m\":{\"k\":[\"x\",\"y\"]},\"z\":1}");
    }

    @Test
    @DisplayName("解析失败：IllegalArgumentException 且消息带 'JSON 解析失败'（拒绝原因链契约）")
    void parseFailureContract() {
        assertThatThrownBy(() -> EvidencePackageJsonCodec.readTree("{broken"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("JSON 解析失败");
    }
}
