package com.objwww.pr.control.release.application;

import com.objwww.pr.control.release.domain.model.ConfigBundle;
import com.objwww.pr.control.release.domain.model.ReleaseAsset;
import com.objwww.pr.control.release.domain.model.SkillCandidate;
import com.objwww.pr.control.release.domain.model.SkillRunBinding;
import com.objwww.pr.control.release.domain.repository.ConfigBundleRepository;
import com.objwww.pr.control.release.domain.repository.SkillCandidateRepository;
import com.objwww.pr.control.release.domain.repository.SkillRunBindingRepository;
import com.objwww.pr.shared.Digest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Skill 生产选择面（CL-05 持久绑定，§4.1/§4.2）：每 (run, role, epoch) 选择为
 * 持久事实——重启不漂移、发布不追溯、NONE 钉版、RETIRED 消费阻断、冻结允许集
 * （release_manifest.skills ∩ ACTIVE，不用全局 ACTIVE 冒充）、并发双首选取一、
 * 热切预生成。S06/S07 selector 语义不变。零远端。
 */
class SkillSelectionServiceTest {

    private MemBindings bindings;
    private MemCandidates candidates;
    private SkillCandidateServiceTest.MemAssets assets;
    private MemBundles bundles;
    /** 允许集（bundle manifest.skills 内容；activate 自动登记） */
    private final List<String> allowSet = new ArrayList<>();

    @BeforeEach
    void setUp() {
        bindings = new MemBindings();
        candidates = new MemCandidates();
        assets = new SkillCandidateServiceTest.MemAssets();
        bundles = new MemBundles();
    }

    private SkillSelectionService service() {
        return new SkillSelectionService(bindings, candidates, assets, bundles,
                () -> Instant.parse("2026-09-13T00:00:00Z"));
    }

    private static final String RELEASE = "f".repeat(64);

    private SkillSelectionService.SkillView select(SkillSelectionService svc,
        UUID runId, String alertname, String serviceName) {
        return svc.selectPinned(runId, "primary", 0L, RELEASE, alertname, serviceName);
    }

    /** 注册 ACTIVE 候选（资产+候选行+允许集），返回候选名 */
    private String activate(String name, List<String> alertnames, List<String> services,
            List<String> tools) {
        Map<String, Object> content = Map.of(
                "body", name + " 调查方法正文",
                "manifest", Map.of("alertnames", alertnames, "services", services,
                        "steps", List.of("step-1"), "tools", tools));
        ReleaseAsset asset = ReleaseAsset.of("SKILL", content, "test",
                Instant.now());
        assets.insert(asset);
        allowSet.add(asset.assetDigest().hex());
        bundles.register(RELEASE, allowSet);
        SkillCandidate row = new SkillCandidate(UUID.randomUUID(), name,
                UUID.randomUUID(), "a".repeat(64),
                SkillCandidate.VERIFIED, asset.assetDigest().hex(),
                SkillCandidate.ST_ACTIVE, null, "curator",
                "operator", Instant.now(), null, null, null,
                Instant.now(), Instant.now());
        candidates.rows.add(row);
        return name;
    }

    /** 候选状态推进（保留其余字段） */
    private void retireOrDeprecate(String name, String status) {
        candidates.rows.stream().filter(r -> r.name().equals(name)).findFirst()
                .ifPresent(row -> candidates.update(new SkillCandidate(row.id(),
                        row.name(), row.sourceRunId(), row.sourceDigest(),
                        row.verificationStatus(), row.assetDigest(), status, null,
                        row.proposedBy(), row.activatedBy(), row.activatedAt(),
                        status.equals(SkillCandidate.ST_RETIRED) ? "ops" : null,
                        status.equals(SkillCandidate.ST_RETIRED) ? Instant.now() : null,
                        status.equals(SkillCandidate.ST_RETIRED) ? "紧急撤销" : null,
                        row.createdAt(), Instant.now())));
    }

    @Test
    @DisplayName("S06/S07：允许集内双维命中选一；冲突字典序；未命中钉 NONE")
    void matchConflictAndMiss() {
        activate("zeta", List.of("JvmHeapHigh"), List.of("svc-a"), List.of("prom"));
        activate("alpha", List.of("JvmHeapHigh"), List.of("svc-a"), List.of("prom"));

        SkillSelectionService.SkillView hit = select(service(),
                UUID.randomUUID(), "JvmHeapHigh", "svc-a");
        assertThat(hit.present()).isTrue();
        assertThat(hit.name()).isEqualTo("alpha");
        assertThat(hit.body()).contains("alpha");
        assertThat(hit.steps()).containsExactly("step-1");

        UUID missRun = UUID.randomUUID();
        assertThat(select(service(), missRun, "Other", "svc-a").present()).isFalse();
        assertThat(bindings.rows.keySet().stream().filter(k ->
                        k.contains(missRun.toString())).count())
                .as("未命中同样持久化 NONE（钉空不追溯）").isEqualTo(1);
    }

    @Test
    @DisplayName("S11 持久钉版：重启（新实例）不漂移；发布 v2/退场 v1 不切换；新 run 用 v2")
    void restartAndPublishDoNotDrift() {
        UUID runId = UUID.randomUUID();
        activate("v1", List.of("JvmHeapHigh"), List.of("svc-a"), List.of("prom"));
        assertThat(select(service(), runId, "JvmHeapHigh", "svc-a").name())
                .isEqualTo("v1");

        // 运行中发布 v2 并退场 v1（ACTIVE→DEPRECATED）；重启 = 新服务实例
        activate("v2", List.of("JvmHeapHigh"), List.of("svc-a"), List.of("prom"));
        retireOrDeprecate("v1", SkillCandidate.ST_DEPRECATED);
        assertThat(select(service(), runId, "JvmHeapHigh", "svc-a").name())
                .as("旧 run 持久钉 v1，重启/发布不切换（DEPRECATED 不破坏老绑定）")
                .isEqualTo("v1");
        assertThat(select(service(), UUID.randomUUID(), "JvmHeapHigh", "svc-a").name())
                .as("新 run 按新集合选 v2").isEqualTo("v2");
    }

    @Test
    @DisplayName("NONE 钉版不追溯：钉空后发布匹配 Skill 不影响该 run")
    void nonePinSurvivesPublish() {
        UUID missRun = UUID.randomUUID();
        assertThat(select(service(), missRun, "DiskFull", "svc-b").present()).isFalse();
        activate("late", List.of("DiskFull"), List.of("svc-b"), List.of("prom"));
        assertThat(select(service(), missRun, "DiskFull", "svc-b").present())
                .as("发布不追溯影响已钉空 run").isFalse();
    }

    @Test
    @DisplayName("RETIRED 紧急撤销阻断既有绑定消费（不静默换替补）")
    void retiredBlocksConsumption() {
        UUID runId = UUID.randomUUID();
        activate("v1", List.of("JvmHeapHigh"), List.of("svc-a"), List.of("prom"));
        activate("spare", List.of("JvmHeapHigh"), List.of("svc-a"), List.of("prom"));
        assertThat(select(service(), runId, "JvmHeapHigh", "svc-a").name())
                .isEqualTo("spare");

        retireOrDeprecate("spare", SkillCandidate.ST_RETIRED);
        assertThat(select(service(), runId, "JvmHeapHigh", "svc-a").present())
                .as("RETIRED 阻断后续消费且不换 v1 替补").isFalse();
        assertThat(bindings.rows.get(missKey(runId)).assetDigest())
                .as("绑定历史不改写（撤销走消费阻断，不动绑定行）").isNotNull();
    }

    @Test
    @DisplayName("冻结允许集：全局 ACTIVE 但不在 release_manifest.skills 内不入选")
    void frozenAllowSetOnly() {
        Map<String, Object> content = Map.of("body", "b",
                "manifest", Map.of("alertnames", List.of("A"), "services", List.of("svc"),
                        "steps", List.of(), "tools", List.of("prom")));
        ReleaseAsset asset = ReleaseAsset.of("SKILL", content, "t", Instant.now());
        assets.insert(asset);
        // 候选 ACTIVE 但允许集为空（不在任何 bundle manifest）
        candidates.rows.add(new SkillCandidate(UUID.randomUUID(), "outsider",
                UUID.randomUUID(), "b".repeat(64), SkillCandidate.VERIFIED,
                asset.assetDigest().hex(), SkillCandidate.ST_ACTIVE, null, "c",
                "operator", Instant.now(), null, null, null,
                Instant.now(), Instant.now()));

        assertThat(select(service(), UUID.randomUUID(), "A", "svc").present())
                .as("最新全局 ACTIVE 不冒充冻结允许集（§4.2）").isFalse();
    }

    @Test
    @DisplayName("无组合身份（releaseDigest=null）不回填不钉版")
    void legacyRunWithoutReleaseIdentity() {
        assertThat(service().selectPinned(UUID.randomUUID(), "primary", null,
                null, "A", "svc").present()).isFalse();
        assertThat(bindings.rows).as("不落任何绑定行").isEmpty();
    }

    @Test
    @DisplayName("并发双首次：insertIfAbsent 撞主键，所有调用返回胜者事实")
    void concurrentFirstSelectSingleWinner() {
        activate("v1", List.of("A"), List.of("svc"), List.of("prom"));
        UUID runId = UUID.randomUUID();
        SkillRunBinding winner = new SkillRunBinding(runId, "primary", 0L,
                SkillRunBinding.SELECTED, candidates.rows.get(0).assetDigest(),
                RELEASE, SkillSelectionService.SELECTOR_VERSION, null,
                Instant.parse("2026-09-13T00:00:00Z"));

        // 竞态假件：find 首次空（触发新选），insertIfAbsent 恒败（胜者已在库）
        AtomicBoolean raced = new AtomicBoolean(false);
        SkillRunBindingRepository racing = new SkillRunBindingRepository() {
            @Override
            public boolean insertIfAbsent(SkillRunBinding binding) {
                raced.set(true);
                return false;
            }

            @Override
            public Optional<SkillRunBinding> find(UUID run, String role, long epoch) {
                return raced.get() ? Optional.of(winner) : Optional.empty();
            }
        };
        SkillSelectionService service = new SkillSelectionService(racing, candidates,
                assets, bundles, () -> Instant.now());
        assertThat(select(service, runId, "A", "svc").name())
                .as("败者读胜者返回，不落第二行").isEqualTo("v1");
        assertThat(raced.get()).isTrue();
    }

    @Test
    @DisplayName("热切预生成：provisionForEpoch 落新代际选择记录（sourceCommand 留审计）")
    void provisionForEpochPinsNewEpochBinding() {
        activate("v1", List.of("A"), List.of("svc"), List.of("prom"));
        UUID runId = UUID.randomUUID();
        UUID commandId = UUID.randomUUID();
        service().provisionForEpoch(runId, "primary", 1L, RELEASE, "A", "svc",
                commandId);
        SkillRunBinding bound = bindings.rows.get(runId + "/primary/1");
        assertThat(bound).isNotNull();
        assertThat(bound.selected()).isTrue();
        assertThat(bound.sourceCommandId()).isEqualTo(commandId);
        assertThat(bound.selectorVersion())
                .isEqualTo(SkillSelectionService.SELECTOR_VERSION);
        // 同代际重放幂等：既有行直读不改写
        service().provisionForEpoch(runId, "primary", 1L, RELEASE, "A", "svc",
                UUID.randomUUID());
        assertThat(bindings.rows.get(runId + "/primary/1").sourceCommandId())
                .isEqualTo(commandId);
    }

    @Test
    @DisplayName("S08 隔离：EVALUATING 候选生产不可见；权限交集=Skill 声明 ∩ 角色 allowlist")
    void isolationAndIntersection() {
        Map<String, Object> content = Map.of("body", "b", "manifest",
                Map.of("alertnames", List.of("A"), "services", List.of("svc"),
                        "steps", List.of(), "tools", List.of("prom", "deploy.prod")));
        ReleaseAsset asset = ReleaseAsset.of("SKILL", content, "t", Instant.now());
        assets.insert(asset);
        allowSet.add(asset.assetDigest().hex());
        bundles.register(RELEASE, allowSet);
        candidates.rows.add(new SkillCandidate(UUID.randomUUID(), "evaluating-skill",
                UUID.randomUUID(), "b".repeat(64), SkillCandidate.VERIFIED,
                asset.assetDigest().hex(), SkillCandidate.ST_EVALUATING, null, "c",
                null, null, null, null, null, Instant.now(), Instant.now()));

        assertThat(select(service(), UUID.randomUUID(), "A", "svc").present())
                .as("EVALUATING 候选对生产 Run 不可见").isFalse();

        activate("real", List.of("A"), List.of("svc"), List.of("prom", "deploy.prod"));
        SkillSelectionService.SkillView view = select(service(),
                UUID.randomUUID(), "A", "svc");
        assertThat(SkillSelectionService.intersectTools(view, Set.of("prom")))
                .containsExactly("prom");
        assertThat(SkillSelectionService.intersectTools(view, Set.of("prom", "logs.query")))
                .containsExactly("prom");
    }

    // ------------------------------------------------------------ 桩

    private String missKey(UUID runId) {
        return bindings.rows.keySet().stream()
                .filter(k -> k.startsWith(runId.toString() + "/"))
                .findFirst().orElseThrow();
    }

    static final class MemCandidates implements SkillCandidateRepository {
        final List<SkillCandidate> rows = new ArrayList<>();

        @Override
        public boolean insert(SkillCandidate candidate) {
            rows.add(candidate);
            return true;
        }

        @Override
        public Optional<SkillCandidate> findById(UUID id) {
            return rows.stream().filter(r -> r.id().equals(id)).findFirst();
        }

        @Override
        public Optional<SkillCandidate> findBySource(String sourceDigest, String name) {
            return Optional.empty();
        }

        @Override
        public void update(SkillCandidate candidate) {
            rows.removeIf(r -> r.id().equals(candidate.id()));
            rows.add(candidate);
        }

        @Override
        public List<SkillCandidate> listByStatus(String status) {
            return rows.stream().filter(r -> r.status().equals(status)).toList();
        }

        @Override
        public Optional<SkillCandidate> findByAssetDigest(String assetDigest) {
            return rows.stream().filter(r -> assetDigest.equals(r.assetDigest()))
                    .findFirst();
        }
    }

    /** 绑定假件：主键语义 (run/role/epoch) 撞键 false；行落库后不改写 */
    static final class MemBindings implements SkillRunBindingRepository {
        final Map<String, SkillRunBinding> rows = new LinkedHashMap<>();

        @Override
        public boolean insertIfAbsent(SkillRunBinding binding) {
            String key = key(binding.runId(), binding.roleId(), binding.configEpoch());
            if (rows.containsKey(key)) {
                return false;
            }
            rows.put(key, binding);
            return true;
        }

        @Override
        public Optional<SkillRunBinding> find(UUID runId, String roleId, long configEpoch) {
            return Optional.ofNullable(rows.get(key(runId, roleId, configEpoch)));
        }

        private static String key(UUID runId, String roleId, long configEpoch) {
            return runId + "/" + roleId + "/" + configEpoch;
        }
    }

    /** bundle 假件：单一 digest → manifest.skills 允许集可变登记 */
    static final class MemBundles implements ConfigBundleRepository {
        private final Map<String, List<String>> allowSets = new LinkedHashMap<>();

        void register(String releaseDigest, List<String> skills) {
            allowSets.put(releaseDigest, new ArrayList<>(skills));
        }

        @Override
        public Optional<ConfigBundle> findByDigest(Digest digest) {
            List<String> skills = allowSets.get(digest.hex());
            if (skills == null) {
                return Optional.empty();
            }
            Map<String, Object> content = new LinkedHashMap<>();
            content.put("policy_version", "v1");
            content.put("release_manifest", Map.of(
                    "schema_version", "release-manifest.v1",
                    "roles", Map.of("primary", "c".repeat(64)),
                    "skills", new ArrayList<>(skills),
                    "tool_schemas", List.of(),
                    "model_routing", Map.of(),
                    "rag_corpus", Map.of(),
                    "context_rules", Map.of(),
                    "harness_compat", List.of("am4")));
            return Optional.of(new ConfigBundle(UUID.randomUUID(), digest, 1,
                    content, "test", Instant.now()));
        }

        @Override
        public long nextRevision() {
            return 1;
        }

        @Override
        public boolean insert(ConfigBundle bundle) {
            return true;
        }

        @Override
        public Optional<Digest> activeDigest() {
            return Optional.empty();
        }

        @Override
        public Optional<ActivePointer> findActivePointer() {
            return Optional.empty();
        }

        @Override
        public List<BundleSummary> listRecent(int limit) {
            return List.of();
        }

        @Override
        public boolean activateQualified(Digest toDigest, long expectedActiveRevision,
                String by, Instant at) {
            return true;
        }
    }
}
