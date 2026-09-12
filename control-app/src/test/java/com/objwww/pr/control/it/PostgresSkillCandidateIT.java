package com.objwww.pr.control.it;

import com.objwww.pr.control.release.domain.model.ReleaseAsset;
import com.objwww.pr.control.release.domain.model.SkillCandidate;
import com.objwww.pr.control.release.domain.repository.SkillCandidateRepository;
import com.objwww.pr.control.infrastructure.persistence.PostgresReleaseAssetRepository;
import com.objwww.pr.control.infrastructure.persistence.PostgresSkillCandidateRepository;
import com.objwww.pr.control.release.application.SkillCandidateService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * EN-08 Skill 候选真 PG 数据面（L1；本机无 docker 自动跳过）：V97 形状与 CHECK
 * （状态封闭集/UNVERIFIED→REJECTED 必无资产指针/ACTIVE 必带人工授权/RETIRED 三件套）、
 * uq(source_digest,name) = S14 幂等锚（DuplicateKey 兜底直插）、生命周期 update 授权。
 * 门语义裁决（S09/S10/S12）的封闭用例见 SkillCandidateServiceTest（L0）。
 */
class PostgresSkillCandidateIT extends PostgresITBase {

    private static final Instant NOW = Instant.parse("2026-09-12T14:00:00Z");

    private SkillCandidateRepository candidates;
    private SkillCandidateService service;

    @BeforeEach
    void seed() {
        candidates = new PostgresSkillCandidateRepository(controlJdbc);
        service = new SkillCandidateService(candidates,
                new PostgresReleaseAssetRepository(controlDataSource()),
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private SkillCandidateService.Proposal proposal(String name, boolean verified) {
        return new SkillCandidateService.Proposal(name, UUID.randomUUID(),
                String.format("%064x", Math.abs(name.hashCode() * 31L)), verified,
                "HttpHighErrorRate", "svc-a",
                List.of("查 error rate", "比对变更窗"), List.of("连续 3 步零新证据即停"),
                List.of("prometheus.instant"), 10L,
                "调查步骤正文（无事故特有信息）", "skill-curator");
    }

    @Test
    @DisplayName("EN-08 全链真 PG：DRAFT 落档→校验→PASS 资格→人工激活→ACTIVE 生产可见；S14 幂等")
    void fullLifecycleOnRealPg() {
        SkillCandidateService.ProposeResult first = service.propose(proposal("pg-skill", true));
        assertThat(first.candidate().status()).isEqualTo(SkillCandidate.ST_DRAFT);

        // S14：同键重放返回既有行（uq 兜底：直插撞 uq 抛 DuplicateKey）
        assertThat(service.propose(proposal("pg-skill", true)).dup()).isTrue();
        assertThatThrownBy(() -> controlJdbc.sql("""
                        insert into rca_skill_candidate(id, name, source_run_id, source_digest,
                            verification_status, status, proposed_by, created_at, updated_at)
                        values (:id, :name, :run, :digest, 'VERIFIED', 'DRAFT', 'x', now(), now())
                        """)
                .param("id", UUID.randomUUID()).param("name", "pg-skill")
                .param("run", first.candidate().sourceRunId())
                .param("digest", first.candidate().sourceDigest())
                .update()).isInstanceOf(DuplicateKeyException.class);

        // S02：UNVERIFIED → REJECTED 且无资产指针（DB CHECK 同钉）
        SkillCandidate unverified = service.propose(proposal("pg-unverified", false))
                .candidate();
        assertThat(unverified.status()).isEqualTo(SkillCandidate.ST_REJECTED);
        assertThat(unverified.assetDigest()).isNull();

        // 校验→资格→激活
        SkillCandidate evaluating = service.validate(first.candidate().id(),
                Set.of("prometheus.instant"));
        assertThat(evaluating.status()).isEqualTo(SkillCandidate.ST_EVALUATING);
        SkillCandidate qualified = service.recordQualification(evaluating.id(),
                new com.objwww.pr.control.release.domain.model.ReleaseQualification(
                        UUID.randomUUID(),
                        new com.objwww.pr.shared.Digest("a".repeat(64)),
                        new com.objwww.pr.shared.Digest("b".repeat(64)),
                        "c".repeat(64), "runner-v1", "grader-v1",
                        com.objwww.pr.control.release.domain.model.ReleaseQualification.VERDICT_PASS,
                        com.objwww.pr.control.release.domain.model.ReleaseQualification.USAGE_MATCHED,
                        "skill:pg-skill", "eval-operator", NOW, null, null, null));
        assertThat(qualified.status()).isEqualTo(SkillCandidate.ST_QUALIFIED);
        assertThat(service.activeSkills()).isEmpty();

        SkillCandidate active = service.activate(qualified.id(), "operator-alice");
        assertThat(active.status()).isEqualTo(SkillCandidate.ST_ACTIVE);
        assertThat(service.activeSkills()).extracting(SkillCandidate::name)
                .containsExactly("pg-skill");

        // 生命周期终态：ACTIVE 直退 RETIRED（三件套 CHECK），行不删
        SkillCandidate retired = service.retire(active.id(), "operator-alice",
                "误选证据成立", true);
        assertThat(retired.status()).isEqualTo(SkillCandidate.ST_RETIRED);
        assertThat(candidates.findById(active.id())).isPresent();
        assertThat(service.activeSkills()).isEmpty();
    }

    @Test
    @DisplayName("V97 CHECK 面：非法状态/ACTIVE 缺授权/UNVERIFIED 带资产全拒")
    void checkConstraints() {
        assertThatThrownBy(() -> controlJdbc.sql("""
                        insert into rca_skill_candidate(id, name, source_run_id, source_digest,
                            verification_status, status, proposed_by, created_at, updated_at)
                        values (:id, :name, :run, :digest, 'VERIFIED', 'PROMOTED', 'x', now(), now())
                        """)
                .param("id", UUID.randomUUID()).param("name", "bad-status")
                .param("run", UUID.randomUUID()).param("digest", "a".repeat(64))
                .update()).isInstanceOf(
                org.springframework.dao.DataIntegrityViolationException.class);

        assertThatThrownBy(() -> controlJdbc.sql("""
                        insert into rca_skill_candidate(id, name, source_run_id, source_digest,
                            verification_status, status, proposed_by, created_at, updated_at)
                        values (:id, :name, :run, :digest, 'VERIFIED', 'ACTIVE', 'x', now(), now())
                        """)
                .param("id", UUID.randomUUID()).param("name", "no-activation")
                .param("run", UUID.randomUUID()).param("digest", "a".repeat(64))
                .update()).isInstanceOf(
                org.springframework.dao.DataIntegrityViolationException.class);

        assertThatThrownBy(() -> controlJdbc.sql("""
                        insert into rca_skill_candidate(id, name, source_run_id, source_digest,
                            verification_status, asset_digest, status, proposed_by,
                            created_at, updated_at)
                        values (:id, :name, :run, :digest, 'UNVERIFIED', :asset, 'REJECTED',
                                'x', now(), now())
                        """)
                .param("id", UUID.randomUUID()).param("name", "unverified-with-asset")
                .param("run", UUID.randomUUID()).param("digest", "a".repeat(64))
                .param("asset", "b".repeat(64))
                .update()).isInstanceOf(
                org.springframework.dao.DataIntegrityViolationException.class);
    }
}
