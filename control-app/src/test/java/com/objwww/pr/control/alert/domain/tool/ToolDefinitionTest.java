package com.objwww.pr.control.alert.domain.tool;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * ToolDefinition 契约穷举单测（AM4 M4-13）：schema_hash 稳定（字段序无关）、
 * 非法定义拒绝（risk 缺省从严、additionalProperties=false 默认/显式 true 拒绝）。
 */
class ToolDefinitionTest {

    private static Map<String, Object> querySchema() {
        Map<String, Object> properties = Map.of(
                "query", Map.of("type", "string"),
                "limit", Map.of("type", "integer"));
        return Map.of("type", "object", "properties", properties);
    }

    private static ToolDefinition definition(Map<String, Object> schema) {
        return new ToolDefinition("prometheus.query", "1.0.0", schema,
                ToolRisk.R0, 5_000, 262_144);
    }

    @Test
    void utT01_法定定义被接受_字段回读一致() {
        ToolDefinition d = definition(querySchema());
        assertThat(d.name()).isEqualTo("prometheus.query");
        assertThat(d.version()).isEqualTo("1.0.0");
        assertThat(d.risk()).isEqualTo(ToolRisk.R0);
        assertThat(d.timeoutMillis()).isEqualTo(5_000);
        assertThat(d.resultLimitBytes()).isEqualTo(262_144);
    }

    @Test
    void utT02_risk缺省从严_R3() {
        ToolDefinition d = new ToolDefinition("logs.tail", "1.0.0", querySchema(),
                null, 1_000, 1_024);
        assertThat(d.risk()).isEqualTo(ToolRisk.R3);
    }

    @Test
    void utT03_schemaHash对字段序无关且稳定() {
        Map<String, Object> reordered = Map.of(
                "properties", Map.of(
                        "limit", Map.of("type", "integer"),
                        "query", Map.of("type", "string")),
                "type", "object");
        assertThat(definition(querySchema()).schemaHash())
                .isEqualTo(definition(reordered).schemaHash())
                .hasSize(64)
                .matches(hash -> hash.chars().allMatch(c ->
                        (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f')));
    }

    @Test
    void utT04_schema内容变_hash必变_其余字段变_hash不变() {
        ToolDefinition base = definition(querySchema());
        Map<String, Object> changed = Map.of(
                "type", "object",
                "properties", Map.of(
                        "query", Map.of("type", "string"),
                        "limit", Map.of("type", "integer"),
                        "extra", Map.of("type", "boolean")));
        assertThat(new ToolDefinition("prometheus.query", "1.0.0", changed,
                ToolRisk.R0, 5_000, 262_144).schemaHash())
                .isNotEqualTo(base.schemaHash());
        // timeout/resultLimit/risk/name/version 都不参与 schema_hash
        assertThat(new ToolDefinition("other.tool", "9.9.9", querySchema(),
                ToolRisk.R2, 1, 1).schemaHash()).isEqualTo(base.schemaHash());
    }

    @Test
    void utT05_additionalProperties缺省归一false_显式true拒绝() {
        Map<String, Object> normalized = definition(querySchema()).schema();
        assertThat(normalized.get("additionalProperties")).isEqualTo(false);
        Map<String, Object> explicit = Map.of(
                "type", "object", "properties", querySchema().get("properties"),
                "additionalProperties", true);
        assertThatThrownBy(() -> definition(explicit))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("additionalProperties");
        Map<String, Object> declared = Map.of(
                "type", "object", "properties", querySchema().get("properties"),
                "additionalProperties", false);
        assertThat(definition(declared).schema().get("additionalProperties")).isEqualTo(false);
    }

    @Test
    void utT06_非法schema形状拒绝() {
        List<Map<String, Object>> bad = List.of(
                // 无 type=object
                Map.of("properties", Map.of()),
                // properties 缺失
                Map.of("type", "object"),
                // properties 非映射
                Map.of("type", "object", "properties", List.of()),
                // 属性定义缺 type
                Map.of("type", "object", "properties", Map.of("q", Map.of())),
                // 属性 type 非法
                Map.of("type", "object", "properties", Map.of("q", Map.of("type", "float128"))));
        for (Map<String, Object> schema : bad) {
            assertThatThrownBy(() -> definition(schema))
                    .as("schema 应被拒绝: %s", schema.keySet())
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void utT07_非法定义字段拒绝() {
        Map<String, Object> ok = querySchema();
        assertThatThrownBy(() -> new ToolDefinition("", "1.0.0", ok, ToolRisk.R0, 1, 1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ToolDefinition("Bad Name", "1.0.0", ok, ToolRisk.R0, 1, 1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ToolDefinition("tool", " ", ok, ToolRisk.R0, 1, 1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ToolDefinition("tool", "1.0.0", ok, ToolRisk.R0, 0, 1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ToolDefinition("tool", "1.0.0", ok, ToolRisk.R0, 1, 0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ToolDefinition("tool", "1.0.0", null, ToolRisk.R0, 1, 1))
                .isInstanceOf(IllegalArgumentException.class);
        // risk 全集冻结校验（R0~R3 可执行集由 ToolPolicy 判定）
        assertThat(java.util.Arrays.stream(ToolRisk.values()).map(Enum::name).collect(Collectors.toSet()))
                .containsExactlyInAnyOrder("R0", "R1", "R2", "R3");
    }
}
