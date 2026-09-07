package com.objwww.pr.control.alert.application.tool;

import com.objwww.pr.control.alert.domain.tool.ToolDefinition;
import com.objwww.pr.control.alert.domain.tool.ToolRisk;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * ToolRegistry 穷举单测（AM4 M4-14）：启动期重名/版本冲突 fail-fast（含同名同版本
 * 不同 schema_hash）、构造后不可变、清单顺序稳定。
 */
class ToolRegistryTest {

    private static ToolRegistry.Registration registration(String name, String version) {
        ToolDefinition d = new ToolDefinition(name, version,
                Map.of("type", "object",
                        "properties", Map.of("q", Map.of("type", "string"))),
                ToolRisk.R0, 1_000, 1_024);
        return new ToolRegistry.Registration(d, execution -> new byte[1]);
    }

    @Test
    void utR01_法定注册可查询_清单序稳定() {
        ToolRegistry registry = new ToolRegistry(List.of(
                registration("b.tool", "1.0.0"),
                registration("a.tool", "1.0.0")));
        assertThat(registry.find("a.tool", "1.0.0")).isPresent();
        assertThat(registry.find("a.tool", "2.0.0")).isEmpty();
        assertThat(registry.all()).extracting(r -> r.definition().name())
                .containsExactly("a.tool", "b.tool"); // 字典序稳定
    }

    @Test
    void utR02_同名同版本冲突_启动期拒绝_即使schema相同() {
        assertThatThrownBy(() -> new ToolRegistry(List.of(
                registration("a.tool", "1.0.0"),
                registration("a.tool", "1.0.0"))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("重名");
    }

    @Test
    void utR03_同名同版本不同schema_hash_启动失败() {
        ToolDefinition v1 = new ToolDefinition("a.tool", "1.0.0",
                Map.of("type", "object",
                        "properties", Map.of("q", Map.of("type", "string"))),
                ToolRisk.R0, 1_000, 1_024);
        ToolDefinition v2DifferentSchema = new ToolDefinition("a.tool", "1.0.0",
                Map.of("type", "object",
                        "properties", Map.of("q", Map.of("type", "string"),
                                "extra", Map.of("type", "boolean"))),
                ToolRisk.R0, 1_000, 1_024);
        assertThat(v1.schemaHash()).isNotEqualTo(v2DifferentSchema.schemaHash());
        assertThatThrownBy(() -> new ToolRegistry(List.of(
                new ToolRegistry.Registration(v1, execution -> new byte[1]),
                new ToolRegistry.Registration(v2DifferentSchema, execution -> new byte[1]))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("schema_hash");
    }

    @Test
    void utR04_同名不同版本共存_空注册表拒绝() {
        assertThat(new ToolRegistry(List.of(
                registration("a.tool", "1.0.0"),
                registration("a.tool", "2.0.0"))).all()).hasSize(2);
        assertThatThrownBy(() -> new ToolRegistry(List.of()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("空工具注册表");
    }
}
