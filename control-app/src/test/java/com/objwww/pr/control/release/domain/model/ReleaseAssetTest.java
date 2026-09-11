package com.objwww.pr.control.release.domain.model;

import com.objwww.pr.shared.Digest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

/**
 * EN-01 发布资产 UT：身份=内容 canonical digest（内容寻址，篡改即新身份——S10 锚）、
 * 密钥键名 fail-closed（INV-AM5-5 同源）、PROMPT 模板占位符 ⊆ variables_schema
 * （P02 缺变量拒绝）、SKILL/TOOL_SCHEMA 最小形状。
 */
class ReleaseAssetTest {

    private static final Instant NOW = Instant.parse("2026-09-11T00:00:00Z");

    private static Map<String, Object> promptContent() {
        Map<String, Object> content = new LinkedHashMap<>();
        content.put("asset_id", "primary-investigator");
        content.put("display_version", "v7");
        content.put("messages_template",
                "你是值班调查员。服务 {{service}} 窗口 {{window}}。{{evidence}}");
        content.put("variables_schema", List.of("service", "window", "evidence"));
        return content;
    }

    private static ReleaseAsset asset(String kind, Map<String, Object> content) {
        return ReleaseAsset.of(kind, content, "release-operator", NOW);
    }

    @Test
    @DisplayName("digest 身份：同内容同 digest、一字之差新 digest（内容寻址，P01/S10 锚）")
    void digestIsContentAddressed() {
        ReleaseAsset first = asset(ReleaseAsset.KIND_PROMPT, promptContent());
        ReleaseAsset same = asset(ReleaseAsset.KIND_PROMPT, promptContent());
        Map<String, Object> edited = promptContent();
        edited.put("messages_template", "你是值班调查员。服务 {{service}} 窗口 {{window}}。");
        ReleaseAsset changed = asset(ReleaseAsset.KIND_PROMPT, edited);

        assertThat(same.assetDigest()).isEqualTo(first.assetDigest());
        assertThat(changed.assetDigest()).isNotEqualTo(first.assetDigest());
        assertThat(first.assetDigest().hex()).hasSize(64);
    }

    @Test
    @DisplayName("P02 缺变量：模板占位符未在 variables_schema 声明 → 注册拒绝")
    void undeclaredTemplateVariableRejected() {
        Map<String, Object> content = promptContent();
        content.put("variables_schema", List.of("service", "window"));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> asset(ReleaseAsset.KIND_PROMPT, content))
                .withMessageContaining("evidence");
    }

    @Test
    @DisplayName("P02 额外声明变量合法（只拒缺不拒多）；无占位符模板合法")
    void extraDeclaredVariablesAllowed() {
        Map<String, Object> superset = promptContent();
        superset.put("variables_schema", List.of("service", "window", "evidence", "extra"));
        assertThat(asset(ReleaseAsset.KIND_PROMPT, superset).assetDigest()).isNotNull();

        Map<String, Object> plain = promptContent();
        plain.put("messages_template", "没有任何占位符的模板");
        assertThat(asset(ReleaseAsset.KIND_PROMPT, plain).assetDigest()).isNotNull();
    }

    @Test
    @DisplayName("PROMPT 形状：messages_template 必带非 blank、variables_schema 必为非空字符串列表")
    void promptShapeContract() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> asset(ReleaseAsset.KIND_PROMPT,
                        with(promptContent(), "messages_template", " ")))
                .withMessageContaining("messages_template");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> asset(ReleaseAsset.KIND_PROMPT,
                        with(promptContent(), "variables_schema", List.of())))
                .withMessageContaining("variables_schema");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> asset(ReleaseAsset.KIND_PROMPT,
                        with(promptContent(), "variables_schema", "service")))
                .withMessageContaining("variables_schema");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> asset(ReleaseAsset.KIND_PROMPT,
                        with(promptContent(), "variables_schema", List.of(42))))
                .withMessageContaining("variables_schema");
    }

    @Test
    @DisplayName("INV-AM5-5 同源：资产内容密钥键名（api_key/secret/…）fail-closed 拒绝")
    void secretKeysRejected() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> asset(ReleaseAsset.KIND_PROMPT,
                        with(promptContent(), "llm_api_key", "material")))
                .withMessageContaining("密钥");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> asset(ReleaseAsset.KIND_SKILL,
                        with(skillContent(), "manifest", Map.of("steps", List.of(),
                                "nested_secret", "x"))))
                .withMessageContaining("密钥");
    }

    @Test
    @DisplayName("SKILL 形状：body 非 blank + manifest 非空映射；resources（可选）必须 64hex 列表")
    void skillShapeContract() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> asset(ReleaseAsset.KIND_SKILL,
                        with(skillContent(), "body", "")))
                .withMessageContaining("body");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> asset(ReleaseAsset.KIND_SKILL,
                        with(skillContent(), "manifest", Map.of())))
                .withMessageContaining("manifest");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> asset(ReleaseAsset.KIND_SKILL,
                        with(skillContent(), "resources", List.of("短"))))
                .withMessageContaining("resources");
        assertThat(asset(ReleaseAsset.KIND_SKILL, skillContent()).assetDigest()).isNotNull();
    }

    @Test
    @DisplayName("TOOL_SCHEMA 形状：schema 必为非空映射（工具 JSON schema 本体）")
    void toolSchemaShapeContract() {
        Map<String, Object> content = new LinkedHashMap<>();
        content.put("schema", Map.of("type", "object"));
        assertThat(asset(ReleaseAsset.KIND_TOOL_SCHEMA, content).assetDigest()).isNotNull();
        assertThatIllegalArgumentException()
                .isThrownBy(() -> asset(ReleaseAsset.KIND_TOOL_SCHEMA,
                        with(content, "schema", Map.of())))
                .withMessageContaining("schema");
    }

    @Test
    @DisplayName("parent_digest 可选：缺席合法；在场必须 64hex（血缘可追溯，显示版本仅标签）")
    void parentDigestContract() {
        assertThat(asset(ReleaseAsset.KIND_PROMPT, promptContent()).assetDigest()).isNotNull();
        Map<String, Object> withParent = promptContent();
        withParent.put("parent_digest", "ee".repeat(32));
        assertThat(asset(ReleaseAsset.KIND_PROMPT, withParent).assetDigest()).isNotNull();
        Map<String, Object> badParent = promptContent();
        badParent.put("parent_digest", "nothex");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> asset(ReleaseAsset.KIND_PROMPT, badParent))
                .withMessageContaining("parent_digest");
    }

    @Test
    @DisplayName("深冻结：构造后外部改写不回流；content 写路径 UnsupportedOperationException")
    void contentIsFrozen() {
        Map<String, Object> raw = promptContent();
        ReleaseAsset a = asset(ReleaseAsset.KIND_PROMPT, raw);
        Digest before = a.assetDigest();
        raw.put("messages_template", "tampered");
        assertThat(a.content().get("messages_template")).isNotEqualTo("tampered");
        assertThat(a.assetDigest()).isEqualTo(before);
        assertThatExceptionOfType(UnsupportedOperationException.class)
                .isThrownBy(() -> a.content().put("x", "y"));
    }

    @Test
    @DisplayName("kind 契约：未知 kind / createdBy blank → 拒绝")
    void kindAndOperatorContract() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> asset("NOTEBOOK", promptContent()))
                .withMessageContaining("kind");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> ReleaseAsset.of(ReleaseAsset.KIND_PROMPT,
                        promptContent(), " ", NOW))
                .withMessageContaining("createdBy");
    }

    private static Map<String, Object> skillContent() {
        Map<String, Object> content = new LinkedHashMap<>();
        content.put("body", "按有界步骤调查数据库连接池耗尽。");
        content.put("manifest", Map.of(
                "selector", Map.of("alertname", "db_pool_exhausted"),
                "steps", List.of("查指标", "查日志"),
                "budget", Map.of("max_tool_calls", 12)));
        return content;
    }

    private static Map<String, Object> with(Map<String, Object> base, String key, Object value) {
        Map<String, Object> copy = new LinkedHashMap<>(base);
        copy.put(key, value);
        return copy;
    }
}
