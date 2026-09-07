package com.objwww.pr.control.release.domain.model;

import com.objwww.pr.shared.Digest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

/**
 * M5-09 ConfigBundle 域模型 UT：不可变配置束——bundle_digest = canonical content
 * 的 sha256（键序无关）；密钥材料键名 fail-closed 拒绝（INV-AM5-5）；策略版本字段必带
 * （本期不引入 OPA/WAITING_APPROVAL，v1.1 裁定）；content 深冻结（外部改写不回流）。
 */
class ConfigBundleTest {

    private static Map<String, Object> content() {
        Map<String, Object> thresholds = new LinkedHashMap<>();
        thresholds.put("version", "gate-thresholds-v1");
        thresholds.put("ciMargin", 0.05);
        Map<String, Object> content = new LinkedHashMap<>();
        content.put("policy_version", "policy-2026-09");
        content.put("prompt_version", "holmes-prompt-v7");
        content.put("thresholds", thresholds);
        return content;
    }

    private static ConfigBundle bundle(Map<String, Object> content) {
        return ConfigBundle.of(content, "release-operator", Instant.parse("2026-09-08T00:00:00Z"));
    }

    @Test
    @DisplayName("发布形态：digest = canonical content 的 sha256（64 位小写 hex），策略版本字段可读")
    void publishShapeComputesCanonicalDigest() {
        ConfigBundle b = bundle(content());
        assertThat(b.bundleDigest()).isEqualTo(
                Digest.sha256Of(com.objwww.pr.control.alert.domain.tool.InternalCanonicalJsonV1
                        .canonicalize(content())));
        assertThat(b.bundleDigest().hex()).hasSize(64);
        assertThat(b.policyVersion()).isEqualTo("policy-2026-09");
        assertThat(b.revision()).isEqualTo(1L);
        assertThat(b.createdBy()).isEqualTo("release-operator");
        assertThat(b.id()).isNotNull();
    }

    @Test
    @DisplayName("canonical 稳定：同内容异键序/异嵌套构造序 → 同一 digest（重跑一致锚）")
    void digestStableAcrossKeyOrder() {
        Map<String, Object> reordered = new LinkedHashMap<>();
        Map<String, Object> thresholds = new LinkedHashMap<>();
        thresholds.put("ciMargin", 0.05);
        thresholds.put("version", "gate-thresholds-v1");
        reordered.put("thresholds", thresholds);
        reordered.put("prompt_version", "holmes-prompt-v7");
        reordered.put("policy_version", "policy-2026-09");
        assertThat(bundle(reordered).bundleDigest()).isEqualTo(bundle(content()).bundleDigest());
    }

    @Test
    @DisplayName("不可变：构造后外部改写 content 映射不回流 bundle（深冻结）")
    void contentIsFrozenAgainstExternalMutation() {
        Map<String, Object> raw = content();
        ConfigBundle b = bundle(raw);
        Digest before = b.bundleDigest();
        raw.put("prompt_version", "tampered");
        ((Map<String, Object>) raw.get("thresholds")).put("ciMargin", 9.99);
        assertThat(b.policyVersion()).isEqualTo("policy-2026-09");
        assertThat(((Map<String, Object>) b.content().get("thresholds")).get("ciMargin"))
                .isEqualTo(0.05);
        assertThat(b.bundleDigest()).isEqualTo(before);
        // 深冻结面 = unmodifiableMap：任何写路径 UnsupportedOperationException 拒绝
        assertThatExceptionOfType(UnsupportedOperationException.class)
                .isThrownBy(() -> b.content().put("x", "y"));
    }

    @Test
    @DisplayName("INV-AM5-5：密钥材料键名（api_key/secret/password/bearer/authorization/privateKey）fail-closed 拒绝")
    void secretMaterialKeysRejected() {
        for (String key : List.of("api_key", "API_KEY", "openai_apikey", "db_password",
                "bearerToken", "Authorization", "client_secret", "privateKey")) {
            Map<String, Object> leaked = new LinkedHashMap<>(content());
            leaked.put(key, "material");
            assertThatIllegalArgumentException()
                    .as("键 %s 必须拒绝", key)
                    .isThrownBy(() -> bundle(leaked))
                    .withMessageContaining("密钥");
        }
        Map<String, Object> nested = new LinkedHashMap<>(content());
        nested.put("routing", Map.of("llm_api_key", "material"));
        assertThatIllegalArgumentException().isThrownBy(() -> bundle(nested));
    }

    @Test
    @DisplayName("策略版本必带：缺 policy_version 键拒绝构造（v1.1：仅保留策略版本字段）")
    void policyVersionIsRequired() {
        Map<String, Object> missing = new LinkedHashMap<>(content());
        missing.remove("policy_version");
        assertThatIllegalArgumentException().isThrownBy(() -> bundle(missing))
                .withMessageContaining("policy_version");
        Map<String, Object> blank = new LinkedHashMap<>(content());
        blank.put("policy_version", " ");
        assertThatIllegalArgumentException().isThrownBy(() -> bundle(blank));
    }

    @Test
    @DisplayName("构造校验：content 空/非对象成员、createdBy blank、revision<1、digest null 各自拒绝")
    void constructionContracts() {
        assertThatNullPointerException()
                .isThrownBy(() -> new ConfigBundle(UUID.randomUUID(), null, 1L,
                        content(), "op", Instant.now()));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> ConfigBundle.of(Map.of(), "op", Instant.now()));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> ConfigBundle.of(content(), " ", Instant.now()));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new ConfigBundle(UUID.randomUUID(),
                        Digest.sha256Of("x"), 0L, content(), "op", Instant.now()));
    }
}
