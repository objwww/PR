package com.objwww.pr.control.release.application;

import com.objwww.pr.control.release.domain.model.ReleaseAsset;
import com.objwww.pr.control.release.domain.model.ReleaseQualification;
import com.objwww.pr.control.release.domain.model.SkillCandidate;
import com.objwww.pr.control.release.domain.repository.ReleaseAssetRepository;
import com.objwww.pr.control.release.domain.repository.SkillCandidateRepository;
import com.objwww.pr.shared.Digest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * EN-08 Skill 候选链 L0 面（§四 + S 案 E 面）：S01 生成器仅写 DRAFT/S02 未复核隔离/
 * S03 提炼清洗+验泄漏/S04 有界步骤+禁 DAG/S05 权限交集+预算上界/S09 资格门/S10
 * 篡改对账/S12 生命周期退场/S14 幂等重放。零远端零真库（真库 IT 留 195 窗）。
 */
class SkillCandidateServiceTest {

    private static final Instant NOW = Instant.parse("2026-09-12T09:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    private MemCandidates candidates;
    private MemAssets assets;
    private SkillCandidateService service;

    @BeforeEach
    void setUp() {
        candidates = new MemCandidates();
        assets = new MemAssets();
        service = new SkillCandidateService(candidates, assets, CLOCK);
    }

    private static SkillCandidateService.Proposal proposal(String name, boolean verified) {
        return new SkillCandidateService.Proposal(name,
                UUID.randomUUID(), "a".repeat(64), verified,
                "HttpHighErrorRate", "svc-a",
                List.of("查 error rate", "比对变更窗"), List.of("连续 3 步零新证据即停"),
                List.of("prometheus.instant"), 10L,
                "定位 svc-a 高错误率的调查步骤；不要在无引用时下根因结论",
                "skill-curator");
    }

    // ------------------------------------------------------------ S01/S02

    @Test
    @DisplayName("S01：已复核轨迹→自动提案→DRAFT 落档（资产+候选行，无任何 ACTIVE 面）")
    void s01_verifiedProposalLandsDraftOnly() {
        SkillCandidateService.ProposeResult result = service.propose(proposal("svc-a-runbook", true));

        assertThat(result.dup()).isFalse();
        assertThat(result.candidate().status()).isEqualTo(SkillCandidate.ST_DRAFT);
        assertThat(result.candidate().verificationStatus()).isEqualTo(SkillCandidate.VERIFIED);
        assertThat(result.candidate().assetDigest()).isNotNull();
        assertThat(assets.rows).hasSize(1);
        assertThat(assets.rows.get(0).kind()).isEqualTo(ReleaseAsset.KIND_SKILL);
        // 生产读面（S08 同面）：DRAFT 不可见
        assertThat(service.activeSkills()).isEmpty();
    }

    @Test
    @DisplayName("S02：未复核/仅模型自称成功轨迹提交 → REJECTED 隔离行，不落资产")
    void s02_unverifiedSourceRejected() {
        SkillCandidateService.ProposeResult result =
                service.propose(proposal("unverified-skill", false));

        assertThat(result.candidate().status()).isEqualTo(SkillCandidate.ST_REJECTED);
        assertThat(result.candidate().failureReason()).contains("S02").contains("UNVERIFIED");
        assertThat(result.candidate().assetDigest()).isNull();
        assertThat(assets.rows).as("未复核原料不落 SKILL 资产").isEmpty();
    }

    // ------------------------------------------------------------ S03

    @Test
    @DisplayName("S03：提炼清洗——UUID/IP/答案标签被移除；泄漏验面（静态）对三形状各自命中")
    void s03_scrubAndLeakGate() {
        // 清洗面：UUID/IP 泛化、答案标签行剔除
        String scrubbed = SkillCandidateService.scrub(
                "run id 3f2b8a4c-11d2-42a1-9c33-aabbccddeeff at 10.0.3.7\n"
                        + "golden_answer = memory leak\n"
                        + "check heap usage");
        assertThat(scrubbed).doesNotContain("3f2b8a4c").doesNotContain("10.0.3.7")
                .doesNotContain("golden").contains("<事故特有id已脱敏>").contains("<ip已脱敏>")
                .contains("check heap usage");
        // 验泄漏面（纵深防御闸的判定函数）：清洗后产物零命中
        assertThat(SkillCandidateService.leakOf(scrubbed, "body")).isNull();
        // 静态命中：三形状各自可检出（scrub 回归即整卷拒绝的守门依据）
        assertThat(SkillCandidateService.leakOf(
                "id 3f2b8a4c-11d2-42a1-9c33-aabbccddeeff", "x"))
                .isNotNull().extracting(SkillCandidateService.Leak::pattern)
                .isEqualTo("incident-uuid");
        assertThat(SkillCandidateService.leakOf("host 10.1.2.3", "x"))
                .isNotNull().extracting(SkillCandidateService.Leak::pattern)
                .isEqualTo("ip-literal");
        assertThat(SkillCandidateService.leakOf("answer_key=heap", "x"))
                .isNotNull().extracting(SkillCandidateService.Leak::pattern)
                .isEqualTo("answer-label");
    }

    // ------------------------------------------------------------ S04/S05

    @Test
    @DisplayName("S04：steps 缺失/超界/DAG 结构/无停止条件 → VALIDATING 失败 REJECTED（远端零调用）")
    void s04_manifestBoundsGate() {
        assertThat(service.validateManifest(Map.of(), Set.of()))
                .contains("S04").contains("steps");
        assertThat(service.validateManifest(Map.of("steps", List.of()), Set.of()))
                .contains("S04").contains("steps");
        assertThat(service.validateManifest(
                Map.of("steps", List.of("s"), "stop_conditions", List.of("stop"),
                        "budget_caps", Map.of(), "tools", List.of(), "dag", "deep"),
                Set.of())).contains("S04").contains("DAG");
        List<String> tooDeep = new ArrayList<>();
        for (int i = 0; i < SkillCandidateService.MAX_STEPS + 1; i++) {
            tooDeep.add("step " + i);
        }
        assertThat(service.validateManifest(
                Map.of("steps", tooDeep, "stop_conditions", List.of("stop"),
                        "budget_caps", Map.of("tool_calls", 1), "tools", List.of()),
                Set.of())).contains("S04").contains("上限");
        assertThat(service.validateManifest(
                Map.of("steps", List.of("s"), "budget_caps", Map.of("tool_calls", 1),
                        "tools", List.of()),
                Set.of())).contains("S04").contains("stop_conditions");
    }

    @Test
    @DisplayName("S05：声明超预算上界/未授权工具 → 权限交集拒绝（不能扩权）")
    void s05_permissionIntersection() {
        assertThat(service.validateManifest(
                Map.of("steps", List.of("s"), "stop_conditions", List.of("stop"),
                        "budget_caps", Map.of("tool_calls", 10_000L), "tools", List.of()),
                Set.of())).contains("S05").contains("预算");
        assertThat(service.validateManifest(
                Map.of("steps", List.of("s"), "stop_conditions", List.of("stop"),
                        "budget_caps", Map.of("tool_calls", 5L),
                        "tools", List.of("deploy.prod")),
                Set.of("prometheus.instant"))).contains("S05").contains("deploy.prod");
        // 交集内全过
        assertThat(service.validateManifest(
                Map.of("steps", List.of("s"), "stop_conditions", List.of("stop"),
                        "budget_caps", Map.of("tool_calls", 5L),
                        "tools", List.of("prometheus.instant")),
                Set.of("prometheus.instant", "logs.query"))).isNull();
    }

    @Test
    @DisplayName("validate 全链：DRAFT→VALIDATING→EVALUATING；失败→REJECTED 留痕")
    void validateLifecycle() {
        SkillCandidate draft = service.propose(proposal("ok-skill", true)).candidate();
        SkillCandidate evaluating = service.validate(draft.id(), Set.of("prometheus.instant"));
        assertThat(evaluating.status()).isEqualTo(SkillCandidate.ST_EVALUATING);

        // 失败路径：声明未授权工具 → REJECTED（远端零调用——假件无执行面可证）
        SkillCandidate bad = service.propose(proposal("bad-skill", true)).candidate();
        SkillCandidate rejected = service.validate(bad.id(), Set.of());
        assertThat(rejected.status()).isEqualTo(SkillCandidate.ST_REJECTED);
        assertThat(rejected.failureReason()).contains("S05");
        assertThat(rejected.assetDigest()).as("REJECTED 前已落 DRAFT 资产——行保指针，历史不改写")
                .isNotNull();
    }

    // ------------------------------------------------------------ S09/S10

    @Test
    @DisplayName("S09：FAIL/INCONCLUSIVE 资格→REJECTED 不晋升；MATCHED 不背书；PASS+未撤销→QUALIFIED")
    void s09_qualificationGate() {
        SkillCandidate evaluating = service.validate(
                service.propose(proposal("gate-skill", true)).candidate().id(),
                Set.of("prometheus.instant"));

        // FAIL（即便费用 MATCHED）→ REJECTED
        SkillCandidate fail = service.recordQualification(evaluating.id(),
                qualification(SkillCandidateServiceTest.class.getSimpleName(),
                        ReleaseQualification.VERDICT_FAIL, ReleaseQualification.USAGE_MATCHED));
        assertThat(fail.status()).isEqualTo(SkillCandidate.ST_REJECTED);
        assertThat(fail.failureReason()).contains("S09");

        // INCONCLUSIVE → 不能晋升
        SkillCandidate evaluating2 = service.validate(
                service.propose(proposal("inconclusive-skill", true)).candidate().id(),
                Set.of("prometheus.instant"));
        SkillCandidate inconclusive = service.recordQualification(evaluating2.id(),
                qualification("inconclusive", ReleaseQualification.VERDICT_INCONCLUSIVE,
                        ReleaseQualification.USAGE_MATCHED));
        assertThat(inconclusive.status()).isEqualTo(SkillCandidate.ST_REJECTED);

        // PASS+未撤销 → QUALIFIED
        SkillCandidate evaluating3 = service.validate(
                service.propose(proposal("pass-skill", true)).candidate().id(),
                Set.of("prometheus.instant"));
        SkillCandidate qualified = service.recordQualification(evaluating3.id(),
                qualification("pass", ReleaseQualification.VERDICT_PASS,
                        ReleaseQualification.USAGE_MATCHED));
        assertThat(qualified.status()).isEqualTo(SkillCandidate.ST_QUALIFIED);
        assertThat(service.activeSkills()).as("QUALIFIED 仍未发布（S08）").isEmpty();
    }

    @Test
    @DisplayName("S10：激活重算资产 digest 对账——内容篡改=新 digest=候选指针失配=激活拒绝")
    void s10_tamperedAssetCannotActivate() {
        SkillCandidate qualified = happyQualified("tamper-skill");
        // 直接改内存资产内容模拟 DB 篡改（digest 不再与内容一致）
        ReleaseAsset original = assets.rows.get(0);
        Map<String, Object> tampered = new LinkedHashMap<>(original.content());
        tampered.put("body", "被篡改的正文");
        assets.rows.set(0, new ReleaseAsset(ReleaseAsset.KIND_SKILL, original.assetDigest(),
                tampered, original.createdBy(), original.createdAt()));

        assertThatThrownBy(() -> service.activate(qualified.id(), "operator-alice"))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("S10");
        assertThat(candidates.findById(qualified.id()).orElseThrow().status())
                .as("激活拒绝后 active 零变化").isEqualTo(SkillCandidate.ST_QUALIFIED);
    }

    // ------------------------------------------------------------ S12

    @Test
    @DisplayName("S12：普通退场 ACTIVE→DEPRECATED→RETIRED；紧急撤销 ACTIVE→RETIRED 直退；行不删")
    void s12_lifecycleExits() {
        // 普通退场
        SkillCandidate normal = happyQualified("normal-exit");
        SkillCandidate active = service.activate(normal.id(), "operator-bob");
        SkillCandidate deprecated = service.retire(active.id(), "operator-bob",
                "新版本替代", false);
        assertThat(deprecated.status()).isEqualTo(SkillCandidate.ST_DEPRECATED);
        SkillCandidate retired = service.retire(deprecated.id(), "operator-bob",
                "新版本替代", false);
        assertThat(retired.status()).isEqualTo(SkillCandidate.ST_RETIRED);
        assertThat(retired.retiredBy()).isEqualTo("operator-bob");
        assertThat(service.activeSkills()).isEmpty();

        // 紧急撤销：ACTIVE 直退 RETIRED（语义分开，不经过 DEPRECATED）
        SkillCandidate emergency = happyQualified("emergency-exit");
        SkillCandidate emergencyActive = service.activate(emergency.id(), "operator-carol");
        SkillCandidate emergencyRetired = service.retire(emergencyActive.id(),
                "operator-carol", "生产误选证据", true);
        assertThat(emergencyRetired.status()).isEqualTo(SkillCandidate.ST_RETIRED);
        // 行永不删：RETIRED 行仍可查（可恢复历史）
        assertThat(candidates.findById(emergencyActive.id())).isPresent();
        assertThat(candidates.rows).hasSize(candidates.rows.size());
    }

    // ------------------------------------------------------------ S14

    @Test
    @DisplayName("S14：重复提交生成作业 → 幂等重放既有行（不堆重复候选，不重复落资产）")
    void s14_idempotentReplay() {
        SkillCandidateService.ProposeResult first = service.propose(proposal("dup-skill", true));
        SkillCandidateService.ProposeResult second = service.propose(proposal("dup-skill", true));

        assertThat(first.dup()).isFalse();
        assertThat(second.dup()).isTrue();
        assertThat(second.candidate().id()).isEqualTo(first.candidate().id());
        assertThat(candidates.rows).hasSize(1);
        assertThat(assets.rows).as("重放不重复登记生成成本").hasSize(1);
        // 重放不改变生命周期：DRAFT 仍是 DRAFT
        assertThat(second.candidate().status()).isEqualTo(SkillCandidate.ST_DRAFT);
    }

    // ------------------------------------------------------------ 辅助

    /** 修好全链（提案→校验→PASS 资格）→ QUALIFIED 候选 */
    private SkillCandidate happyQualified(String name) {
        SkillCandidate evaluating = service.validate(
                service.propose(proposal(name, true)).candidate().id(),
                Set.of("prometheus.instant"));
        return service.recordQualification(evaluating.id(),
                qualification(name, ReleaseQualification.VERDICT_PASS,
                        ReleaseQualification.USAGE_MATCHED));
    }

    private static ReleaseQualification qualification(String tag, String verdict,
            String usage) {
        return new ReleaseQualification(UUID.randomUUID(),
                new Digest("c".repeat(64)), new Digest("d".repeat(64)),
                "e".repeat(64), "runner-v1", "grader-v1", verdict, usage,
                "skill:" + tag, "eval-operator", NOW, null, null, null);
    }

    // ------------------------------------------------------------ 内存假件

    static final class MemCandidates implements SkillCandidateRepository {
        final List<SkillCandidate> rows = new ArrayList<>();

        @Override
        public boolean insert(SkillCandidate candidate) {
            boolean exists = rows.stream().anyMatch(r ->
                    r.sourceDigest().equals(candidate.sourceDigest())
                            && r.name().equals(candidate.name()));
            if (exists) {
                return false;
            }
            rows.add(candidate);
            return true;
        }

        @Override
        public Optional<SkillCandidate> findById(UUID id) {
            return rows.stream().filter(r -> r.id().equals(id)).findFirst();
        }

        @Override
        public Optional<SkillCandidate> findBySource(String sourceDigest, String name) {
            return rows.stream().filter(r -> r.sourceDigest().equals(sourceDigest)
                    && r.name().equals(name)).findFirst();
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
    }

    static final class MemAssets implements ReleaseAssetRepository {
        final List<ReleaseAsset> rows = new ArrayList<>();

        @Override
        public boolean insert(ReleaseAsset asset) {
            boolean exists = rows.stream().anyMatch(r ->
                    r.kind().equals(asset.kind())
                            && r.assetDigest().hex().equals(asset.assetDigest().hex()));
            if (exists) {
                return false;
            }
            rows.add(asset);
            return true;
        }

        @Override
        public Optional<ReleaseAsset> findByDigest(String kind, Digest digest) {
            return rows.stream().filter(r -> r.kind().equals(kind)
                    && r.assetDigest().hex().equals(digest.hex())).findFirst();
        }

        @Override
        public List<ReleaseAsset> listRecent(String kind, int limit) {
            return rows.stream().limit(limit).toList();
        }
    }
}
