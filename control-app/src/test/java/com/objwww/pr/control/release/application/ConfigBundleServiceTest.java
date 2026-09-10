package com.objwww.pr.control.release.application;

import com.objwww.pr.control.release.domain.model.ConfigBundle;
import com.objwww.pr.control.release.domain.model.ReleaseAsset;
import com.objwww.pr.control.release.domain.model.ReleaseManifest;
import com.objwww.pr.control.release.domain.repository.ConfigBundleRepository;
import com.objwww.pr.control.release.domain.repository.ReleaseAssetRepository;
import com.objwww.pr.control.release.application.ConfigBundleService.ActivationResult;
import com.objwww.pr.control.release.application.ConfigBundleService.PublishResult;
import com.objwww.pr.shared.Digest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

/**
 * M5-09 ConfigBundleService 编排 UT：发布幂等（digest 唯一 = 内容级幂等锚）、
 * 激活单事务原子（pointer CAS 无半激活态）、回滚 = pointer 指回且不改历史行
 * （INV-AM5-5）、未激活态首激活 CAS（expectedCurrent = null）。
 */
class ConfigBundleServiceTest {

    private final InMemoryBundles repository = new InMemoryBundles();
    private final InMemoryAssets assets = new InMemoryAssets();
    private final ConfigBundleService service = new ConfigBundleService(repository, assets);

    /** 测试内存认账面：记录 CAS 调用与行改写面（历史行零改写可断言）；EX-B1 记变更事实 */
    static final class InMemoryBundles implements ConfigBundleRepository {
        final List<ConfigBundle> rows = new ArrayList<>();
        final List<String> casCalls = new ArrayList<>();
        /** EX-B1：随 moved=true 落档的变更事实（5 参 activate 命中时记录） */
        final List<ConfigBundleRepository.ActivationFact> changeFacts = new ArrayList<>();
        long revisionSeq = 0;
        Digest active;
        Instant activatedAt;
        String activatedBy;

        @Override
        public long nextRevision() {
            return ++revisionSeq;
        }

        @Override
        public boolean insert(ConfigBundle bundle) {
            if (findByDigest(bundle.bundleDigest()).isPresent()) {
                return false;
            }
            rows.add(bundle);
            return true;
        }

        @Override
        public Optional<ConfigBundle> findByDigest(Digest digest) {
            return rows.stream().filter(b -> b.bundleDigest().equals(digest)).findFirst();
        }

        @Override
        public Optional<Digest> activeDigest() {
            return Optional.ofNullable(active);
        }

        @Override
        public Optional<ConfigBundleRepository.ActivePointer> findActivePointer() {
            return active == null ? Optional.empty()
                    : Optional.of(new ConfigBundleRepository.ActivePointer(
                            active, findByDigest(active).orElseThrow().revision(), activatedAt));
        }

        @Override
        public boolean activate(Digest toDigest, Digest expectedCurrent, String by, Instant at) {
            casCalls.add((expectedCurrent == null ? "null" : expectedCurrent.hex())
                    + "->" + toDigest.hex());
            if ((active == null && expectedCurrent != null)
                    || (active != null && !active.equals(expectedCurrent))) {
                return false;
            }
            active = toDigest;
            activatedAt = at;
            activatedBy = by;
            return true;
        }

        @Override
        public boolean activate(Digest toDigest, Digest expectedCurrent, String by, Instant at,
                ConfigBundleRepository.ActivationFact fact) {
            boolean moved = activate(toDigest, expectedCurrent, by, at);
            if (moved && fact != null) {
                changeFacts.add(fact);
            }
            return moved;
        }
    }

    private static Map<String, Object> content(String promptVersion) {
        Map<String, Object> content = new LinkedHashMap<>();
        content.put("policy_version", "policy-2026-09");
        content.put("prompt_version", promptVersion);
        return content;
    }

    // ---------------------------------------------------------------- 用例面

    @Test
    @DisplayName("发布：digest 按内容派生、revision 递增；同内容重发 = 幂等重放（同 digest 同 revision）")
    void publishIsIdempotentByDigest() {
        PublishResult first = service.publish(content("v7"), "release-operator");
        PublishResult replay = service.publish(content("v7"), "release-operator");
        PublishResult second = service.publish(content("v8"), "release-operator");

        assertThat(first.replayed()).isFalse();
        assertThat(replay.replayed()).isTrue();
        assertThat(replay.bundleDigest()).isEqualTo(first.bundleDigest());
        assertThat(replay.revision()).isEqualTo(first.revision());
        assertThat(second.bundleDigest()).isNotEqualTo(first.bundleDigest());
        assertThat(second.revision()).isEqualTo(first.revision() + 1);
        assertThat(repository.rows).hasSize(2);
    }

    @Test
    @DisplayName("激活：未激活态首激活（expectedCurrent=null CAS）→ 指针落位；重复激活同 digest = 幂等重放")
    void activateMovesPointerAtomically() {
        Digest d1 = service.publish(content("v7"), "op").bundleDigest();
        Digest d2 = service.publish(content("v8"), "op").bundleDigest();

        ActivationResult first = service.activate(d1, "release-operator");
        assertThat(first.moved()).isTrue();
        assertThat(repository.active).isEqualTo(d1);
        assertThat(repository.activatedBy).isEqualTo("release-operator");
        assertThat(repository.casCalls).containsExactly("null->" + d1.hex());

        ActivationResult replay = service.activate(d1, "release-operator");
        assertThat(replay.moved()).isFalse();
        assertThat(replay.activeDigest()).isEqualTo(d1);
        assertThat(repository.casCalls).hasSize(1);

        ActivationResult second = service.activate(d2, "release-operator");
        assertThat(second.moved()).isTrue();
        assertThat(repository.active).isEqualTo(d2);
    }

    @Test
    @DisplayName("激活冲突：CAS expected 不匹配（并发竞争败者）→ moved=false 且指针不动")
    void activateCasConflictLeavesPointerUntouched() {
        Digest d1 = service.publish(content("v7"), "op").bundleDigest();
        Digest d2 = service.publish(content("v8"), "op").bundleDigest();
        service.activate(d1, "op");

        Digest staleExpected = null;
        boolean moved = repository.activate(d2, staleExpected, "intruder", Instant.now());
        assertThat(moved).isFalse();
        assertThat(repository.active).isEqualTo(d1);
    }

    @Test
    @DisplayName("激活未知 digest → IAE（先验证目标存在，不盲移指针）")
    void activateUnknownDigestRejected() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> service.activate(Digest.sha256Of("ghost"), "op"));
    }

    @Test
    @DisplayName("回滚：pointer 指回旧 digest 且历史行零改写（行集合与 digest 集不变）")
    void rollbackRewindsPointerWithoutTouchingHistory() {
        Digest d1 = service.publish(content("v7"), "op").bundleDigest();
        Digest d2 = service.publish(content("v8"), "op").bundleDigest();
        service.activate(d1, "op");
        service.activate(d2, "op");
        List<Digest> historyBefore = repository.rows.stream()
                .map(ConfigBundle::bundleDigest).toList();

        ActivationResult rollback = service.rollback(d1, "release-operator");

        assertThat(rollback.moved()).isTrue();
        assertThat(repository.active).isEqualTo(d1);
        assertThat(repository.rows).hasSize(2);
        assertThat(repository.rows.stream().map(ConfigBundle::bundleDigest).toList())
                .containsExactlyElementsOf(historyBefore);
    }

    @Test
    @DisplayName("回滚到当前激活 digest = 幂等重放（零 CAS 调用）；回滚未知 digest → IAE")
    void rollbackReplayAndUnknownTarget() {
        Digest d1 = service.publish(content("v7"), "op").bundleDigest();
        service.activate(d1, "op");

        ActivationResult replay = service.rollback(d1, "op");
        assertThat(replay.moved()).isFalse();
        assertThat(repository.casCalls).hasSize(1);
        assertThatIllegalArgumentException()
                .isThrownBy(() -> service.rollback(Digest.sha256Of("ghost"), "op"));
    }

    @Test
    @DisplayName("active 视图：未激活 → 空；激活后返回 digest/revision/activatedAt 三件")
    void activePointerView() {
        assertThat(service.activePointer()).isEmpty();
        Digest d1 = service.publish(content("v7"), "op").bundleDigest();
        service.activate(d1, "op");
        Optional<ConfigBundleRepository.ActivePointer> view = service.activePointer();
        assertThat(view).isPresent();
        assertThat(view.get().bundleDigest()).isEqualTo(d1);
        assertThat(view.get().revision()).isEqualTo(1L);
        assertThat(view.get().activatedAt()).isNotNull();
    }

    // --------------------------------------------- EX-B1 变更事实面（与生效同事务）

    @Test
    @DisplayName("EX-B1：激活/回滚各随 moved 指针产出变更事实——ACTIVATE 零 rollbackOf，ROLLBACK 携回滚前 digest")
    void activateAndRollbackCarryChangeFacts() {
        Digest d1 = service.publish(content("v7"), "op").bundleDigest();
        Digest d2 = service.publish(content("v8"), "op").bundleDigest();

        service.activate(d1, "op");
        service.activate(d2, "op");
        ActivationResult rollback = service.rollback(d1, "op");
        assertThat(rollback.moved()).isTrue();

        assertThat(repository.changeFacts).hasSize(3);
        ConfigBundleRepository.ActivationFact first = repository.changeFacts.get(0);
        assertThat(first.action()).isEqualTo("ACTIVATE");
        assertThat(first.rollbackOf()).isNull();
        ConfigBundleRepository.ActivationFact rb = repository.changeFacts.get(2);
        assertThat(rb.action()).isEqualTo("ROLLBACK");
        assertThat(rb.rollbackOf()).isEqualTo(d2);
        for (ConfigBundleRepository.ActivationFact fact : repository.changeFacts) {
            assertThat(fact.service()).isEqualTo(ConfigBundleService.CHANGE_SERVICE);
            assertThat(fact.environment()).isEqualTo(ConfigBundleService.CHANGE_ENVIRONMENT);
        }
    }

    @Test
    @DisplayName("EX-B1：幂等重放（重复激活/回滚到当前）零 CAS 零变更事实；CAS 败者零事实")
    void replayAndCasLoserProduceNoChangeFact() {
        Digest d1 = service.publish(content("v7"), "op").bundleDigest();
        Digest d2 = service.publish(content("v8"), "op").bundleDigest();
        service.activate(d1, "op");

        service.activate(d1, "op");
        service.rollback(d1, "op");

        assertThat(repository.changeFacts).hasSize(1);
        assertThat(repository.casCalls).hasSize(1);

        // CAS 败者（expected 漂移）：5 参走到仓储 false 分支，事务内零事实
        boolean moved = repository.activate(d2, null, "intruder",
                Instant.now(), new ConfigBundleRepository.ActivationFact(
                        "ACTIVATE", "x", "y", null));
        assertThat(moved).isFalse();
        assertThat(repository.changeFacts).hasSize(1);
    }

    // ------------------------------------------- EN-01 资产注册与依赖闭包（P04 面）

    /** 测试内存认账面（资产）：(kind,digest) 唯一，digest 内容寻址幂等 */
    static final class InMemoryAssets implements ReleaseAssetRepository {
        final List<ReleaseAsset> rows = new ArrayList<>();

        @Override
        public boolean insert(ReleaseAsset asset) {
            if (findByDigest(asset.kind(), asset.assetDigest()).isPresent()) {
                return false;
            }
            rows.add(asset);
            return true;
        }

        @Override
        public java.util.Optional<ReleaseAsset> findByDigest(String kind, Digest digest) {
            return rows.stream()
                    .filter(a -> a.kind().equals(kind) && a.assetDigest().equals(digest))
                    .findFirst();
        }
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

    private static Map<String, Object> promptAssetContent() {
        Map<String, Object> content = new LinkedHashMap<>();
        content.put("messages_template", "调查 {{service}}");
        content.put("variables_schema", List.of("service"));
        return content;
    }

    private static Map<String, Object> skillAssetContent() {
        Map<String, Object> content = new LinkedHashMap<>();
        content.put("body", "有界步骤调查。");
        content.put("manifest", Map.of("steps", List.of("查指标")));
        return content;
    }

    private static Map<String, Object> toolSchemaContent() {
        Map<String, Object> content = new LinkedHashMap<>();
        content.put("schema", Map.of("type", "object"));
        return content;
    }

    @Test
    @DisplayName("EN-01/P04：manifest 引用未注册资产 → 发布拒绝且零落库零指针移动（依赖闭包校验）")
    void publishWithMissingReferencedAssetRejected() {
        Digest prompt = service.registerAsset(ReleaseAsset.KIND_PROMPT,
                promptAssetContent(), "op").assetDigest();
        Map<String, Object> content = new LinkedHashMap<>();
        content.put("policy_version", "policy-2026-09");
        content.put("release_manifest", manifestOf(prompt.hex(),
                "bb".repeat(32), "cc".repeat(32)));

        assertThatIllegalArgumentException()
                .isThrownBy(() -> service.publish(content, "op"))
                .withMessageContaining("依赖闭包");

        assertThat(repository.rows).isEmpty();
        assertThat(repository.activeDigest()).isEmpty();
    }

    @Test
    @DisplayName("EN-01：先注册全部引用资产 → 发布通过；重发同内容幂等（闭包只在首发校验）")
    void registerAssetsThenPublishSucceedsAndReplays() {
        Digest prompt = service.registerAsset(ReleaseAsset.KIND_PROMPT,
                promptAssetContent(), "op").assetDigest();
        Digest skill = service.registerAsset(ReleaseAsset.KIND_SKILL,
                skillAssetContent(), "op").assetDigest();
        Digest tool = service.registerAsset(ReleaseAsset.KIND_TOOL_SCHEMA,
                toolSchemaContent(), "op").assetDigest();
        Map<String, Object> content = new LinkedHashMap<>();
        content.put("policy_version", "policy-2026-09");
        content.put("release_manifest", manifestOf(prompt.hex(), skill.hex(), tool.hex()));

        PublishResult first = service.publish(content, "op");
        assertThat(first.replayed()).isFalse();
        assertThat(repository.rows).hasSize(1);

        PublishResult replay = service.publish(content, "op");
        assertThat(replay.replayed()).isTrue();
        assertThat(repository.rows).hasSize(1);
    }

    @Test
    @DisplayName("EN-01：资产注册按 (kind,digest) 幂等重放；内容变更新行（同 kind）")
    void registerAssetIdempotentPerKind() {
        ConfigBundleService.AssetPublishResult first = service.registerAsset(
                ReleaseAsset.KIND_PROMPT, promptAssetContent(), "op");
        ConfigBundleService.AssetPublishResult replay = service.registerAsset(
                ReleaseAsset.KIND_PROMPT, promptAssetContent(), "op");
        ConfigBundleService.AssetPublishResult changed = service.registerAsset(
                ReleaseAsset.KIND_PROMPT,
                withEntry(promptAssetContent(), "messages_template", "排查 {{service}}"), "op");

        assertThat(first.replayed()).isFalse();
        assertThat(replay.replayed()).isTrue();
        assertThat(replay.assetDigest()).isEqualTo(first.assetDigest());
        assertThat(changed.replayed()).isFalse();
        assertThat(changed.assetDigest()).isNotEqualTo(first.assetDigest());
        assertThat(assets.rows).hasSize(2);
    }

    private static Map<String, Object> withEntry(Map<String, Object> base, String key,
            Object value) {
        Map<String, Object> copy = new LinkedHashMap<>(base);
        copy.put(key, value);
        return copy;
    }

    @Test
    @DisplayName("EN-01：无 manifest 段的存量内容不受闭包校验影响（P09 新旧共存面）")
    void manifestlessPublishUnaffected() {
        PublishResult result = service.publish(content("v7"), "op");
        assertThat(result.replayed()).isFalse();
        assertThat(assets.rows).isEmpty();
    }
}
