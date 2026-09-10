package com.objwww.pr.control.alert.domain.tool;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 工具参数校验穷举单测（AM4 M4-15，GX-4 评审收紧）：
 * additionalProperties=false——<b>未声明字段直接拒绝</b>（禁静默裁字段，防两请求裁后撞 digest）；
 * required 在场、类型匹配；校验通过后才 canonicalize + digest。
 */
class ToolArgsValidatorTest {

    private static ToolDefinition schema(Map<String, Object> properties, List<String> required) {
        Map<String, Object> schema = required.isEmpty()
                ? Map.of("type", "object", "properties", properties)
                : Map.of("type", "object", "properties", properties, "required", required);
        return new ToolDefinition("prometheus.query", "1.0.0", schema, ToolRisk.R0,
                5_000, 1_024);
    }

    @Test
    void utV01_声明参数齐全且类型匹配_通过() {
        ToolDefinition d = schema(Map.of(
                "query", Map.of("type", "string"),
                "limit", Map.of("type", "integer")), List.of("query"));
        assertThatCode(() -> ToolArgsValidator.validate(d,
                Map.of("query", "up", "limit", 10))).doesNotThrowAnyException();
    }

    @Test
    void utV02_未声明字段直接拒绝_禁止静默裁剪() {
        ToolDefinition d = schema(Map.of("query", Map.of("type", "string")), List.of());
        assertThatThrownBy(() -> ToolArgsValidator.validate(d,
                Map.of("query", "up", "request_id", "noise")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("request_id")
                .hasMessageContaining("未声明");
    }

    @Test
    void utV03_required缺失拒绝() {
        ToolDefinition d = schema(Map.of("query", Map.of("type", "string")), List.of("query"));
        assertThatThrownBy(() -> ToolArgsValidator.validate(d, Map.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("query")
                .hasMessageContaining("required");
    }

    @Test
    void utV04_类型不匹配拒绝() {
        ToolDefinition d = schema(Map.of(
                "q", Map.of("type", "string"),
                "n", Map.of("type", "number"),
                "b", Map.of("type", "boolean"),
                "arr", Map.of("type", "array"),
                "obj", Map.of("type", "object"),
                "i", Map.of("type", "integer")), List.of());
        assertThatThrownBy(() -> ToolArgsValidator.validate(d, Map.of("q", 1)))
                .hasMessageContaining("q");
        assertThatThrownBy(() -> ToolArgsValidator.validate(d, Map.of("n", "x")))
                .hasMessageContaining("n");
        assertThatThrownBy(() -> ToolArgsValidator.validate(d, Map.of("b", "true")))
                .hasMessageContaining("b");
        assertThatThrownBy(() -> ToolArgsValidator.validate(d, Map.of("arr", Map.of())))
                .hasMessageContaining("arr");
        assertThatThrownBy(() -> ToolArgsValidator.validate(d, Map.of("obj", List.of())))
                .hasMessageContaining("obj");
        assertThatThrownBy(() -> ToolArgsValidator.validate(d, Map.of("i", 1.5)))
                .hasMessageContaining("i");
        // 整数维度的合法形（Integer/Long）与浮点 number 均放行
        assertThatCode(() -> ToolArgsValidator.validate(d, Map.of("i", 10L, "n", 1.5)))
                .doesNotThrowAnyException();
    }

    @Test
    void utV05_nullargs视同空参_无required即通过_有required仍拒() {
        ToolDefinition noRequired = schema(Map.of("q", Map.of("type", "string")), List.of());
        assertThatCode(() -> ToolArgsValidator.validate(noRequired, null))
                .doesNotThrowAnyException();
        ToolDefinition withRequired = schema(Map.of("q", Map.of("type", "string")),
                List.of("q"));
        assertThatThrownBy(() -> ToolArgsValidator.validate(withRequired, null))
                .hasMessageContaining("q");
    }

    @Test
    void utV06_maxLength超限拒绝_界内放行() {
        // EX-A4a（F17）：schema 关键字收紧——string 长度上限
        ToolDefinition d = schema(Map.of(
                "query", Map.of("type", "string", "maxLength", 5)), List.of("query"));
        assertThatCode(() -> ToolArgsValidator.validate(d, Map.of("query", "up")))
                .doesNotThrowAnyException();
        assertThatThrownBy(() -> ToolArgsValidator.validate(d, Map.of("query", "123456")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("query")
                .hasMessageContaining("maxLength");
    }

    @Test
    void utV07_pattern不匹配拒绝_全匹配放行() {
        // EX-A4a（F17）：epoch 秒/step 形状约束（MetricsAgent schema 同款 pattern）
        ToolDefinition d = schema(Map.of(
                "start", Map.of("type", "string", "pattern", "^\\d{1,10}$"),
                "step", Map.of("type", "string", "pattern", "^\\d{1,4}(ms|s|m|h)$")),
                List.of());
        assertThatCode(() -> ToolArgsValidator.validate(d,
                Map.of("start", "1757059200", "step", "30s")))
                .doesNotThrowAnyException();
        // 负数 epoch / 非法 step 单位 / 部分 matches 也不行（find→matches 全匹配语义）
        assertThatThrownBy(() -> ToolArgsValidator.validate(d,
                Map.of("start", "-1757059200", "step", "30s")))
                .hasMessageContaining("start");
        assertThatThrownBy(() -> ToolArgsValidator.validate(d,
                Map.of("start", "1", "step", "30x")))
                .hasMessageContaining("step");
    }
}
