package com.objwww.pr.control.it;

import com.objwww.pr.control.infrastructure.persistence.PostgresConfigBundleRepository;
import com.objwww.pr.control.infrastructure.persistence.PostgresReleaseAssetRepository;
import com.objwww.pr.control.release.application.ConfigBundleService;
import com.objwww.pr.control.release.domain.model.ConfigBundle;
import com.objwww.pr.control.release.domain.model.ReleaseAsset;
import com.objwww.pr.control.release.domain.model.ReleaseManifest;
import com.objwww.pr.control.release.domain.repository.ConfigBundleRepository;
import com.objwww.pr.control.release.domain.repository.ReleaseAssetRepository;
import com.objwww.pr.shared.Digest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * EN-01 release_asset 真 PG 组件测试（V60；增强线方案 §8.3 IT 面）：
 * ①(kind,digest) 唯一 = 注册幂等锚 ②内容寻址 round-trip（写读一致、篡改即新身份）
 * ③同 digest 异 kind = 两行 ④服务层依赖闭包（P04：引用缺失发布拒绝零落库、
 * 注册齐发布通过、激活指针可落位）⑤V60 授权面（control_app 只 select,insert）。
 * 本机无 Docker 自动跳过（真证据待 195 窗统一补——L1 排队规约 §6.1）。
 */
class PostgresReleaseAssetRepositoryTest extends PostgresITBase {

    private ReleaseAssetRepository assets;
    private ConfigBundleService service;

    @BeforeEach
    void setUp() {
        // V24 两表不在基座 TRUNCATE 清单（AM5 惯例自清）；release_asset 在清单（V60）
        adminJdbc.sql("DELETE FROM config_bundle_active").update();
        adminJdbc.sql("DELETE FROM config_bundle").update();
        adminJdbc.sql("INSERT INTO config_bundle_active (id) VALUES (1)").update();

        assets = new PostgresReleaseAssetRepository(controlDataSource());
        service = new ConfigBundleService(
                new PostgresConfigBundleRepository(controlDataSource()), assets);
    }

    private static Map<String, Object> promptAssetContent() {
        Map<String, Object> content = new LinkedHashMap<>();
        content.put("asset_id", "primary-investigator");
        content.put("messages_template", "调查 {{service}} 窗口 {{window}}");
        content.put("variables_schema", List.of("service", "window"));
        return content;
    }

    private static Map<String, Object> manifestOf(String promptHex, String skillHex,
            String toolHex) {
        Map<String, Object> manifest = new LinkedHashMap<>();
        manifest.put("schema_version", ReleaseManifest.SCHEMA_VERSION);
        manifest.put("roles", Map.of("primary", promptHex));
        manifest.put("skills", List.of(skillHex));
        manifest.put("tool_schemas", List.of(toolHex));
        manifest.put("model_routing", Map.of());
        manifest.put("rag_corpus", Map.of());
        manifest.put("context_rules", Map.of());
        manifest.put("harness_compat", List.of("r7-v2.1"));
        return manifest;
    }

    /** 三种 kind 形状皆合法的混合内容（跨 kind 同 digest 双行的测法载体） */
    private static Map<String, Object> mixedShapeContent() {
        Map<String, Object> content = new LinkedHashMap<>();
        content.put("messages_template", "调查 {{service}}");
        content.put("variables_schema", List.of("service"));
        content.put("body", "有界步骤。");
        content.put("manifest", Map.of("steps", List.of("查指标")));
        content.put("schema", Map.of("type", "object"));
        return content;
    }

    @Test
    @DisplayName("注册幂等锚：(kind,digest) 唯一——同内容重发 insert=false；同 digest 异 kind 两行")
    void insertIdempotentPerKindDigest() {
        ReleaseAsset asset = ReleaseAsset.of(ReleaseAsset.KIND_PROMPT,
                promptAssetContent(), "op-1", Instant.now());

        assertThat(assets.insert(asset)).isTrue();
        assertThat(assets.insert(ReleaseAsset.of(ReleaseAsset.KIND_PROMPT,
                promptAssetContent(), "op-2", Instant.now()))).isFalse();
        assertThat(count("release_asset")).isEqualTo(1);

        ReleaseAsset sameDigestOtherKind = ReleaseAsset.of(ReleaseAsset.KIND_TOOL_SCHEMA,
                mixedShapeContent(), "op-1", Instant.now());
        ReleaseAsset promptOfMixed = ReleaseAsset.of(ReleaseAsset.KIND_PROMPT,
                mixedShapeContent(), "op-1", Instant.now());
        assertThat(sameDigestOtherKind.assetDigest()).isEqualTo(promptOfMixed.assetDigest());
        assertThat(assets.insert(sameDigestOtherKind)).isTrue();
        assertThat(assets.insert(promptOfMixed)).isTrue();
        assertThat(count("release_asset")).isEqualTo(3);
        assertThat(assets.findByDigest(ReleaseAsset.KIND_TOOL_SCHEMA,
                sameDigestOtherKind.assetDigest())).isPresent();
    }

    @Test
    @DisplayName("round-trip：写读一致（content 深冻结面逐键相等）；读路径重过域校验")
    void writeReadRoundTrip() {
        ReleaseAsset asset = ReleaseAsset.of(ReleaseAsset.KIND_PROMPT,
                promptAssetContent(), "op-1", Instant.now());
        assets.insert(asset);

        ReleaseAsset loaded = assets
                .findByDigest(ReleaseAsset.KIND_PROMPT, asset.assetDigest()).orElseThrow();
        assertThat(loaded.kind()).isEqualTo(ReleaseAsset.KIND_PROMPT);
        assertThat(loaded.assetDigest()).isEqualTo(asset.assetDigest());
        assertThat(loaded.createdBy()).isEqualTo("op-1");
        assertThat(loaded.content()).isEqualTo(asset.content());
        assertThat(loaded.content().get("variables_schema"))
                .isEqualTo(List.of("service", "window"));
    }

    @Test
    @DisplayName("P04 闭包（真 PG）：引用缺失 → 发布拒绝零落库零指针；注册齐 → 发布+激活全链通过")
    void manifestClosureFlow() {
        String ghostSkill = "bb".repeat(32);
        Map<String, Object> leaking = new LinkedHashMap<>();
        leaking.put("policy_version", "policy-2026-09");
        leaking.put("release_manifest", manifestOf("aa".repeat(32), ghostSkill,
                "cc".repeat(32)));

        // 闭包失败：发布拒绝、bundle 与资产零落库、指针保持未激活
        assertThatIllegalArgumentException()
                .isThrownBy(() -> service.publish(leaking, "op-1"))
                .withMessageContaining("依赖闭包");
        assertThat(count("config_bundle")).isZero();
        assertThat(count("release_asset")).isZero();
        assertThat(service.activePointer()).isEmpty();

        // 注册齐三只资产 → 发布通过 → 激活落位
        Digest prompt = service.registerAsset(ReleaseAsset.KIND_PROMPT,
                promptAssetContent(), "op-1").assetDigest();
        Digest skill = service.registerAsset(ReleaseAsset.KIND_SKILL,
                Map.of("body", "有界步骤。", "manifest", Map.of("steps", List.of("查指标"))),
                "op-1").assetDigest();
        Digest tool = service.registerAsset(ReleaseAsset.KIND_TOOL_SCHEMA,
                Map.of("schema", Map.of("type", "object")), "op-1").assetDigest();

        Map<String, Object> content = new LinkedHashMap<>();
        content.put("policy_version", "policy-2026-09");
        content.put("release_manifest", manifestOf(prompt.hex(), skill.hex(), tool.hex()));
        ConfigBundleService.PublishResult published = service.publish(content, "op-1");
        assertThat(published.replayed()).isFalse();

        ConfigBundleRepository.ActivePointer pointer =
                service.activate(published.bundleDigest(), "op-1").moved()
                        ? service.activePointer().orElseThrow()
                        : null;
        assertThat(pointer).isNotNull();
        assertThat(pointer.bundleDigest()).isEqualTo(published.bundleDigest());
    }

    @Test
    @DisplayName("V60 授权面：control_app 对 release_asset 只 select,insert（UPDATE/DELETE 拒绝）")
    void controlAppGrantsAreAppendOnly() {
        ReleaseAsset asset = ReleaseAsset.of(ReleaseAsset.KIND_PROMPT,
                promptAssetContent(), "op-1", Instant.now());
        assets.insert(asset);

        String digest = asset.assetDigest().hex();
        assertThatThrownBy(() -> controlJdbc.sql(
                        "UPDATE release_asset SET created_by = 'tampered' WHERE asset_digest = :d")
                .param("d", digest).update())
                .isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> controlJdbc.sql(
                        "DELETE FROM release_asset WHERE asset_digest = :d")
                .param("d", digest).update())
                .isInstanceOf(DataAccessException.class);
        assertThat(count("release_asset")).isEqualTo(1);
    }
}
