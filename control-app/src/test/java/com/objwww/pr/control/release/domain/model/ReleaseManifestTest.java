package com.objwww.pr.control.release.domain.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

/**
 * EN-01 release_manifest typed 段 UT（增强线方案 §8.2 数据契约）：发布组合 8 成员
 * 结构校验 fail-closed——schema_version 固定、角色→Prompt 映射必带、Skill/工具 schema
 * 引用为 64 位 hex、自由段（模型路由/RAG 语料/上下文规则）必须为映射、Harness 兼容
 * 范围非空。引用是否存在（依赖闭包）归服务层（需查资产仓储），本层只锁形状。
 */
class ReleaseManifestTest {

    private static final String PROMPT_DIGEST = "aa".repeat(32);
    private static final String SKILL_DIGEST = "bb".repeat(32);
    private static final String TOOL_DIGEST = "cc".repeat(32);

    private static Map<String, Object> raw() {
        Map<String, Object> manifest = new LinkedHashMap<>();
        manifest.put("schema_version", ReleaseManifest.SCHEMA_VERSION);
        manifest.put("roles", Map.of("primary", PROMPT_DIGEST));
        manifest.put("skills", List.of(SKILL_DIGEST));
        manifest.put("tool_schemas", List.of(TOOL_DIGEST));
        manifest.put("model_routing", Map.of("model", "glm-4-flash", "temperature", 0.2));
        manifest.put("rag_corpus", Map.of("runbook", "runbook-catalog-v1"));
        manifest.put("context_rules", Map.of("max_tool_results", 20));
        manifest.put("harness_compat", List.of("r7-v2.1"));
        return manifest;
    }

    private static ReleaseManifest parse(Map<String, Object> raw) {
        return ReleaseManifest.fromContent(Map.of("policy_version", "p", "release_manifest", raw))
                .orElseThrow();
    }

    @Test
    @DisplayName("合法 manifest 解析：8 成员各自就位（typed 视图）")
    void validManifestParses() {
        ReleaseManifest m = parse(raw());
        assertThat(m.schemaVersion()).isEqualTo(ReleaseManifest.SCHEMA_VERSION);
        assertThat(m.roles()).containsEntry("primary", PROMPT_DIGEST);
        assertThat(m.skills()).containsExactly(SKILL_DIGEST);
        assertThat(m.toolSchemas()).containsExactly(TOOL_DIGEST);
        assertThat(m.modelRouting()).containsEntry("model", "glm-4-flash");
        assertThat(m.ragCorpus()).containsEntry("runbook", "runbook-catalog-v1");
        assertThat(m.contextRules()).containsEntry("max_tool_results", 20);
        assertThat(m.harnessCompat()).containsExactly("r7-v2.1");
    }

    @Test
    @DisplayName("无 release_manifest 键 → empty（存量 bundle 兼容，P09 新旧共存面）")
    void absentKeyParsesEmpty() {
        assertThat(ReleaseManifest.fromContent(Map.of("policy_version", "p"))).isEmpty();
    }

    @Test
    @DisplayName("schema_version 缺失/非固定值 → 拒绝（版本协商锚，禁静默换 schema）")
    void schemaVersionIsPinned() {
        Map<String, Object> missing = raw();
        missing.remove("schema_version");
        assertThatIllegalArgumentException().isThrownBy(() -> parse(missing))
                .withMessageContaining("schema_version");
        Map<String, Object> wrong = raw();
        wrong.put("schema_version", "release-manifest.v2");
        assertThatIllegalArgumentException().isThrownBy(() -> parse(wrong))
                .withMessageContaining("schema_version");
    }

    @Test
    @DisplayName("roles 契约：缺失/空映射/非映射/role 键 blank/值非 64hex → 各自拒绝")
    void rolesContract() {
        Map<String, Object> missing = raw();
        missing.remove("roles");
        assertThatIllegalArgumentException().isThrownBy(() -> parse(missing))
                .withMessageContaining("roles");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> parse(withEntry(raw(), "roles", Map.of())))
                .withMessageContaining("roles");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> parse(withEntry(raw(), "roles", "primary")))
                .withMessageContaining("roles");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> parse(withEntry(raw(), "roles", Map.of(" ", PROMPT_DIGEST))))
                .withMessageContaining("roles");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> parse(withEntry(raw(), "roles", Map.of("primary", "zz"))))
                .withMessageContaining("roles");
    }

    @Test
    @DisplayName("skills/tool_schemas 契约：必须为列表且元素 64hex；空列表合法（允许零 Skill 组合）")
    void skillAndToolContract() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> parse(withEntry(raw(), "skills", "not-a-list")))
                .withMessageContaining("skills");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> parse(withEntry(raw(), "skills", List.of("短"))))
                .withMessageContaining("skills");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> parse(withEntry(raw(), "tool_schemas", List.of(42))))
                .withMessageContaining("tool_schemas");
        assertThat(parse(withEntry(raw(), "skills", List.of())).skills()).isEmpty();
    }

    @Test
    @DisplayName("自由段契约：model_routing/rag_corpus/context_rules 必须为映射（可空映射）")
    void freeSectionsMustBeMaps() {
        for (String key : List.of("model_routing", "rag_corpus", "context_rules")) {
            assertThatIllegalArgumentException()
                    .as("段 %s 非映射必须拒绝", key)
                    .isThrownBy(() -> parse(withEntry(raw(), key, "bogus")))
                    .withMessageContaining(key);
        }
        assertThat(parse(withEntry(raw(), "model_routing", Map.of())).modelRouting()).isEmpty();
    }

    @Test
    @DisplayName("harness_compat 契约：非空列表且元素非 blank（Harness 兼容范围必声明）")
    void harnessCompatContract() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> parse(withEntry(raw(), "harness_compat", List.of())))
                .withMessageContaining("harness_compat");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> parse(withEntry(raw(), "harness_compat", List.of(" "))))
                .withMessageContaining("harness_compat");
    }

    @Test
    @DisplayName("release_manifest 段本身非映射 → 拒绝（深冻结前形状闸）")
    void manifestSectionMustBeMap() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> ReleaseManifest.fromContent(
                        Map.of("policy_version", "p", "release_manifest", "text")))
                .withMessageContaining("release_manifest");
    }

    private static Map<String, Object> withEntry(Map<String, Object> base, String key,
            Object value) {
        Map<String, Object> copy = new LinkedHashMap<>(base);
        copy.put(key, value);
        return copy;
    }
}
