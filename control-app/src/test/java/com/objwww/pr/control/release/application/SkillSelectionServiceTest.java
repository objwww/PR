package com.objwww.pr.control.release.application;

import com.objwww.pr.control.release.domain.model.SkillCandidate;
import com.objwww.pr.control.release.domain.repository.SkillCandidateRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Skill 生产选择面 L0（SK-08 运行时）：S08 只出 ACTIVE、S06 selector 命中/弃选、
 * S07 冲突字典序、<b>S11 run 钉版</b>（v1 运行中发布 v2 不切换；无匹配钉空，发布
 * 不追溯）、权限交集收口。零远端。
 */
class SkillSelectionServiceTest {

    private MemActiveCandidates candidates;
    private SkillCandidateServiceTest.MemAssets assets;
    private SkillSelectionService service;

    @BeforeEach
    void setUp() {
        candidates = new MemActiveCandidates();
        assets = new SkillCandidateServiceTest.MemAssets();
        service = new SkillSelectionService(candidates, assets);
    }

    /** 注册一个 ACTIVE 候选（资产+候选行），返回候选名 */
    private String activate(String name, List<String> alertnames, List<String> services,
            List<String> tools) {
        Map<String, Object> content = Map.of(
                "body", name + " 调查方法正文",
                "manifest", Map.of("alertnames", alertnames, "services", services,
                        "steps", List.of("step-1"), "tools", tools));
        com.objwww.pr.control.release.domain.model.ReleaseAsset asset =
                com.objwww.pr.control.release.domain.model.ReleaseAsset.of("SKILL",
                        content, "test", java.time.Instant.now());
        assets.insert(asset);
        SkillCandidate row = new SkillCandidate(UUID.randomUUID(), name,
                UUID.randomUUID(), "a".repeat(64),
                SkillCandidate.VERIFIED, asset.assetDigest().hex(),
                SkillCandidate.ST_ACTIVE, null, "curator",
                "operator", java.time.Instant.now(), null, null, null,
                java.time.Instant.now(), java.time.Instant.now());
        candidates.rows.add(row);
        return name;
    }

    @Test
    @DisplayName("S06/S07：selector 双维命中选一；冲突字典序+conflictSuppressed；未命中钉空")
    void matchConflictAndMiss() {
        activate("zeta", List.of("JvmHeapHigh"), List.of("svc-a"), List.of("prom"));
        activate("alpha", List.of("JvmHeapHigh"), List.of("svc-a"), List.of("prom"));

        SkillSelectionService.SkillView hit = service.select(
                UUID.randomUUID(), "JvmHeapHigh", "svc-a");
        assertThat(hit.present()).isTrue();
        assertThat(hit.name()).isEqualTo("alpha");
        assertThat(hit.conflictSuppressed()).isTrue();
        assertThat(hit.body()).contains("alpha");
        assertThat(hit.steps()).containsExactly("step-1");

        assertThat(service.select(UUID.randomUUID(), "Other", "svc-a").present())
                .as("未命中退回通用调查").isFalse();
    }

    @Test
    @DisplayName("S11 run 钉版：v1 运行中发布 v2 同 run 不切换；新 run 用 v2；钉空不追溯")
    void runPinSurvivesPublish() {
        UUID runId = UUID.randomUUID();
        activate("v1", List.of("JvmHeapHigh"), List.of("svc-a"), List.of("prom"));
        assertThat(service.select(runId, "JvmHeapHigh", "svc-a").name()).isEqualTo("v1");

        // 运行中发布 v2 并退场 v1（正常生命周期：ACTIVE→DEPRECATED）
        activate("v2", List.of("JvmHeapHigh"), List.of("svc-a"), List.of("prom"));
        candidates.rows.stream().filter(r -> r.name().equals("v1")).findFirst()
                .ifPresent(v1 -> candidates.rows.set(candidates.rows.indexOf(v1),
                        new SkillCandidate(v1.id(), v1.name(), v1.sourceRunId(),
                                v1.sourceDigest(), v1.verificationStatus(),
                                v1.assetDigest(), SkillCandidate.ST_DEPRECATED,
                                null, v1.proposedBy(), v1.activatedBy(),
                                v1.activatedAt(), null, null, null,
                                v1.createdAt(), java.time.Instant.now())));
        assertThat(service.select(runId, "JvmHeapHigh", "svc-a").name())
                .as("旧 run 钉 v1，不随发布/退场切换").isEqualTo("v1");
        assertThat(service.select(UUID.randomUUID(), "JvmHeapHigh", "svc-a").name())
                .as("新 run 按新集合选 v2").isEqualTo("v2");

        // 无匹配钉空：发布匹配 Skill 后旧 run 仍钉空
        UUID missRun = UUID.randomUUID();
        assertThat(service.select(missRun, "DiskFull", "svc-b").present()).isFalse();
        activate("late", List.of("DiskFull"), List.of("svc-b"), List.of("prom"));
        assertThat(service.select(missRun, "DiskFull", "svc-b").present())
                .as("发布不追溯影响已钉空 run").isFalse();
    }

    @Test
    @DisplayName("S08 隔离：EVALUATING 候选生产不可见；权限交集=Skill 声明 ∩ 角色 allowlist")
    void isolationAndIntersection() {
        // EVALUATING 候选（手工造行）不参与生产选择
        Map<String, Object> content = Map.of("body", "b", "manifest",
                Map.of("alertnames", List.of("A"), "services", List.of("svc"),
                        "steps", List.of(), "tools", List.of("prom", "deploy.prod")));
        var asset = com.objwww.pr.control.release.domain.model.ReleaseAsset.of("SKILL",
                content, "t", java.time.Instant.now());
        assets.insert(asset);
        candidates.rows.add(new SkillCandidate(UUID.randomUUID(), "evaluating-skill",
                UUID.randomUUID(), "b".repeat(64), SkillCandidate.VERIFIED,
                asset.assetDigest().hex(), SkillCandidate.ST_EVALUATING, null, "c",
                null, null, null, null, null, java.time.Instant.now(),
                java.time.Instant.now()));

        assertThat(service.select(UUID.randomUUID(), "A", "svc").present())
                .as("EVALUATING 候选对生产 Run 不可见").isFalse();

        // ACTIVE 后交集面：越权工具被收口
        activate("real", List.of("A"), List.of("svc"), List.of("prom", "deploy.prod"));
        SkillSelectionService.SkillView view = service.select(
                UUID.randomUUID(), "A", "svc");
        assertThat(SkillSelectionService.intersectTools(view, Set.of("prom")))
                .containsExactly("prom");
        assertThat(SkillSelectionService.intersectTools(view, Set.of("prom", "logs.query")))
                .containsExactly("prom");
    }

    // ------------------------------------------------------------ 桩

    static final class MemActiveCandidates implements SkillCandidateRepository {
        final List<SkillCandidate> rows = new ArrayList<>();

        @Override
        public boolean insert(SkillCandidate candidate) {
            return rows.add(candidate);
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
        }

        @Override
        public List<SkillCandidate> listByStatus(String status) {
            return rows.stream().filter(r -> r.status().equals(status)).toList();
        }
    }
}
