package com.objwww.pr.control.release.application;

import com.objwww.pr.control.release.domain.model.ConfigBundle;
import com.objwww.pr.control.release.domain.model.ReleaseAsset;
import com.objwww.pr.control.release.domain.model.ReleaseManifest;
import com.objwww.pr.control.release.domain.model.ReleaseQualification;
import com.objwww.pr.control.release.domain.repository.ConfigBundleRepository;
import com.objwww.pr.control.release.domain.repository.ReleaseAssetRepository;
import com.objwww.pr.control.release.domain.repository.ReleaseQualificationRepository;
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
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

/**
 * M5-09 ConfigBundleService 编排 UT：发布幂等（digest 唯一 = 内容级幂等锚）、
 * 激活单事务原子（pointer CAS 无半激活态）、回滚 = pointer 指回且不改历史行
 * （INV-AM5-5）、未激活态首激活 CAS（expectedRevision=0 约定）。
 *
 * <p>EN-01：发布组合携带 release_manifest 段时依赖闭包校验（P04）。
 * <p>EN-02：activate/rollback 统一过发布资格门（PASS 未撤销才可动，S09/P07/P11），
 * 携带客户端 expected active revision（服务端不悄悄替换用户预期，P06）。
 */
class ConfigBundleServiceTest {

    private final InMemoryBundles repository = new InMemoryBundles();
    private final InMemoryAssets assets = new InMemoryAssets();
    private final InMemoryQualifications qualifications = new InMemoryQualifications();
    private final ConfigBundleService service =
            new ConfigBundleService(repository, assets, qualifications);

    /** 测试内存认账面：记录 CAS 调用与行改写面（历史行零改写可断言）；EX-B1 记变更事实 */
    static final class InMemoryBundles implements ConfigBundleRepository {
        final List<ConfigBundle> rows = new ArrayList<>();
        final List<String> casCalls = new ArrayList<>();
        /** EX-B1：随 moved=true 落档的变更事实（资格化激活命中时记录） */
        final List<ConfigBundleRepository.ActivationFact> changeFacts = new ArrayList<>();
        long revisionSeq = 0;
        Digest active;
        long activeRevision;
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
                            active, activeRevision, activatedAt));
        }

        @Override
        public boolean activateQualified(Digest toDigest, long expectedActiveRevision,
                String by, Instant at) {
            return activateQualified(toDigest, expectedActiveRevision, by, at, null);
        }

        /** EN-02 资格化激活（内存面）：expectedRevision=0 匹配未激活态；否则精确匹配在位 revision */
        @Override
        public boolean activateQualified(Digest toDigest, long expectedActiveRevision,
                String by, Instant at, ConfigBundleRepository.ActivationFact fact) {
            casCalls.add(expectedActiveRevision + "->" + toDigest.hex());
            boolean expectationMet = active == null
                    ? expectedActiveRevision == 0
                    : activeRevision == expectedActiveRevision;
            if (!expectationMet) {
                return false;
            }
            active = toDigest;
            activeRevision = findByDigest(toDigest).orElseThrow().revision();
            activatedAt = at;
            activatedBy = by;
            if (fact != null) {
                changeFacts.add(fact);
            }
            return true;
        }
    }

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

    /** 测试内存认账面（资格）：撤销 = 行替换为 revoked 副本（与 PG UPDATE 语义对齐） */
    static final class InMemoryQualifications implements ReleaseQualificationRepository {
        final List<ReleaseQualification> rows = new ArrayList<>();

        @Override
        public boolean insert(ReleaseQualification qualification) {
            rows.add(qualification);
            return true;
        }

        @Override
        public Optional<ReleaseQualification> findUnrevokedFor(Digest candidate) {
            return rows.stream()
                    .filter(q -> q.candidateDigest().equals(candidate) && q.revokedAt() == null)
                    .reduce((first, second) -> second);
        }

        @Override
        public boolean revoke(UUID id, String by, String reason, Instant at) {
            for (int i = 0; i < rows.size(); i++) {
                ReleaseQualification row = rows.get(i);
                if (row.id().equals(id)) {
                    rows.set(i, row.revoked(by, reason, at));
                    return true;
                }
            }
            return false;
        }
    }

    private static Map<String, Object> content(String promptVersion) {
        Map<String, Object> content = new LinkedHashMap<>();
        content.put("policy_version", "policy-2026-09");
        content.put("prompt_version", promptVersion);
        return content;
    }

    /** 助手：发布并通过资格门（PASS 证明），返回 digest */
    private Digest publishQualified(String promptVersion) {
        Digest digest = service.publish(content(promptVersion), "op").bundleDigest();
        service.grantQualification(digest, null, "ef".repeat(32),
                "runner-v1", "grader-v1", "PASS", "MATCHED", "scope:alert", "grader-1");
        return digest;
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
    @DisplayName("EN-02 激活：资格门后指针落位（expectedRevision=0=未激活约定）；重复激活当前 = 幂等重放")
    void activateMovesPointerAtomically() {
        Digest d1 = publishQualified("v7");
        Digest d2 = publishQualified("v8");

        ActivationResult first = service.activate(d1, 0L, "release-operator");
        assertThat(first.moved()).isTrue();
        assertThat(repository.active).isEqualTo(d1);
        assertThat(repository.activatedBy).isEqualTo("release-operator");
        assertThat(repository.casCalls).containsExactly("0->" + d1.hex());

        ActivationResult replay = service.activate(d1, 0L, "release-operator");
        assertThat(replay.moved()).isFalse();
        assertThat(replay.activeDigest()).isEqualTo(d1);
        assertThat(repository.casCalls).hasSize(1);

        ActivationResult second = service.activate(d2, 1L, "release-operator");
        assertThat(second.moved()).isTrue();
        assertThat(repository.active).isEqualTo(d2);
    }

    @Test
    @DisplayName("EN-02/P06：expected revision 陈旧（并发竞争败者）→ moved=false 带最新投影且指针不动")
    void staleExpectedRevisionLosesCasWithLatestProjection() {
        Digest d1 = publishQualified("v7");
        Digest d2 = publishQualified("v8");
        service.activate(d1, 0L, "op");
        long staleExpectation = 0L;

        ActivationResult loser = service.activate(d2, staleExpectation, "op-2");
        assertThat(loser.moved()).isFalse();
        assertThat(loser.activeDigest()).isEqualTo(d1);
        assertThat(repository.active).isEqualTo(d1);

        ActivationResult winner = service.activate(d2, 1L, "op-2");
        assertThat(winner.moved()).isTrue();
        assertThat(repository.active).isEqualTo(d2);
    }

    @Test
    @DisplayName("EN-02/S09：无资格 → QUALIFICATION_ABSENT；FAIL/INCONCLUSIVE 证明（即使 MATCHED）→ QUALITY_NOT_PASS；指针零变化")
    void gateRejectsUnqualifiedAndFailedProofs() {
        Digest absent = service.publish(content("v7"), "op").bundleDigest();
        assertThatIllegalStateException()
                .isThrownBy(() -> service.activate(absent, 0L, "op"))
                .withMessageContaining("QUALIFICATION_ABSENT");

        Digest failed = service.publish(content("v8"), "op").bundleDigest();
        service.grantQualification(failed, null, "ef".repeat(32), "runner-v1",
                "grader-v1", "FAIL", "MATCHED", "scope", "grader-1");
        assertThatIllegalStateException()
                .isThrownBy(() -> service.activate(failed, 0L, "op"))
                .withMessageContaining("QUALITY_NOT_PASS");

        Digest inconclusive = service.publish(content("v9"), "op").bundleDigest();
        service.grantQualification(inconclusive, null, "ef".repeat(32), "runner-v1",
                "grader-v1", "INCONCLUSIVE", "UNKNOWN", "scope", "grader-1");
        assertThatIllegalStateException()
                .isThrownBy(() -> service.activate(inconclusive, 0L, "op"))
                .withMessageContaining("QUALITY_NOT_PASS");

        assertThat(repository.active).isNull();
        assertThat(repository.changeFacts).isEmpty();
    }

    @Test
    @DisplayName("EN-02/P07：证明撤销后再激活 → 拒绝（陈旧资格不背书）；在位指针不被追溯降级")
    void revokedQualificationBlocksActivation() {
        Digest d = publishQualified("v7");
        service.activate(d, 0L, "op");

        ReleaseQualification proof =
                qualifications.findUnrevokedFor(d).orElseThrow();
        service.revokeQualification(proof.id(), "safety-officer", "基线漂移");

        Digest d2 = publishQualified("v8");
        ReleaseQualification proof2 = qualifications.findUnrevokedFor(d2).orElseThrow();
        service.revokeQualification(proof2.id(), "safety-officer", "紧急撤回");
        assertThatIllegalStateException()
                .isThrownBy(() -> service.activate(d2, 1L, "op"))
                .withMessageContaining("QUALIFICATION_ABSENT");
        // 已在位指针不被资格撤销追溯降级（撤权阻止下一动作资格，§九）
        assertThat(repository.active).isEqualTo(d);
    }

    @Test
    @DisplayName("EN-02/P11：回滚目标同样过资格门——无证明回滚拒绝，不能因'回滚'绕过有效性")
    void rollbackTargetAlsoRequiresQualification() {
        Digest d1 = publishQualified("v7");
        Digest d2 = publishQualified("v8");
        service.activate(d1, 0L, "op");
        service.activate(d2, 1L, "op");

        Digest unqualified = service.publish(content("v6"), "op").bundleDigest();
        assertThatIllegalStateException()
                .isThrownBy(() -> service.rollback(unqualified, 2L, "op"))
                .withMessageContaining("QUALIFICATION_ABSENT");

        ActivationResult rollback = service.rollback(d1, 2L, "op");
        assertThat(rollback.moved()).isTrue();
        assertThat(repository.active).isEqualTo(d1);
    }

    @Test
    @DisplayName("EN-02：资格授予要求候选已发布（未知 digest → IAE）；撤销未知 id → false")
    void grantRequiresPublishedCandidate() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> service.grantQualification(Digest.sha256Of("ghost"),
                        null, "ef".repeat(32), "runner-v1", "grader-v1",
                        "PASS", "MATCHED", "scope", "grader-1"));
        assertThat(service.revokeQualification(UUID.randomUUID(), "o", "r")).isFalse();
    }

    @Test
    @DisplayName("激活未知 digest → IAE（先验证目标存在，不盲移指针）")
    void activateUnknownDigestRejected() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> service.activate(Digest.sha256Of("ghost"), 0L, "op"));
    }

    @Test
    @DisplayName("回滚：pointer 指回旧 digest 且历史行零改写（行集合与 digest 集不变）")
    void rollbackRewindsPointerWithoutTouchingHistory() {
        Digest d1 = publishQualified("v7");
        Digest d2 = publishQualified("v8");
        service.activate(d1, 0L, "op");
        service.activate(d2, 1L, "op");
        List<Digest> historyBefore = repository.rows.stream()
                .map(ConfigBundle::bundleDigest).toList();

        ActivationResult rollback = service.rollback(d1, 2L, "release-operator");

        assertThat(rollback.moved()).isTrue();
        assertThat(repository.active).isEqualTo(d1);
        assertThat(repository.rows).hasSize(2);
        assertThat(repository.rows.stream().map(ConfigBundle::bundleDigest).toList())
                .containsExactlyElementsOf(historyBefore);
    }

    @Test
    @DisplayName("回滚到当前激活 digest = 幂等重放（零 CAS 调用）；回滚未知 digest → IAE")
    void rollbackReplayAndUnknownTarget() {
        Digest d1 = publishQualified("v7");
        service.activate(d1, 0L, "op");

        ActivationResult replay = service.rollback(d1, 0L, "op");
        assertThat(replay.moved()).isFalse();
        assertThat(repository.casCalls).hasSize(1);
        assertThatIllegalArgumentException()
                .isThrownBy(() -> service.rollback(Digest.sha256Of("ghost"), 0L, "op"));
    }

    @Test
    @DisplayName("active 视图：未激活 → 空；激活后返回 digest/revision/activatedAt 三件")
    void activePointerView() {
        assertThat(service.activePointer()).isEmpty();
        Digest d1 = publishQualified("v7");
        service.activate(d1, 0L, "op");
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
        Digest d1 = publishQualified("v7");
        Digest d2 = publishQualified("v8");

        service.activate(d1, 0L, "op");
        service.activate(d2, 1L, "op");
        ActivationResult rollback = service.rollback(d1, 2L, "op");
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
    @DisplayName("EX-B1：幂等重放零 CAS 零变更事实；CAS 败者零事实")
    void replayAndCasLoserProduceNoChangeFact() {
        Digest d1 = publishQualified("v7");
        Digest d2 = publishQualified("v8");
        service.activate(d1, 0L, "op");

        service.activate(d1, 0L, "op");
        service.rollback(d1, 0L, "op");

        assertThat(repository.changeFacts).hasSize(1);
        assertThat(repository.casCalls).hasSize(1);

        // CAS 败者（expected 漂移）：资格化激活走到仓储 false 分支，事务内零事实
        boolean moved = repository.activateQualified(d2, 0L, "intruder",
                Instant.now(), new ConfigBundleRepository.ActivationFact(
                        "ACTIVATE", "x", "y", null));
        assertThat(moved).isFalse();
        assertThat(repository.changeFacts).hasSize(1);
    }

    // ------------------------------------------- EN-01 资产注册与依赖闭包（P04 面）

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

    @Test
    @DisplayName("EN-01：无 manifest 段的存量内容不受闭包校验影响（P09 新旧共存面）")
    void manifestlessPublishUnaffected() {
        PublishResult result = service.publish(content("v7"), "op");
        assertThat(result.replayed()).isFalse();
        assertThat(assets.rows).isEmpty();
    }

    private static Map<String, Object> withEntry(Map<String, Object> base, String key,
            Object value) {
        Map<String, Object> copy = new LinkedHashMap<>(base);
        copy.put(key, value);
        return copy;
    }
}
