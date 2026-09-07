package com.objwww.pr.control.it;

import com.objwww.pr.control.eval.application.GoldenCandidateService;
import com.objwww.pr.control.eval.domain.model.GoldenCandidate;
import com.objwww.pr.control.eval.domain.model.GoldenCandidateState;
import com.objwww.pr.control.eval.domain.model.GoldenReviewAction;
import com.objwww.pr.control.eval.domain.model.GoldenReviewEvent;
import com.objwww.pr.control.eval.domain.repository.GoldenCandidateRepository;
import com.objwww.pr.control.infrastructure.persistence.PostgresGoldenCandidateRepository;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DuplicateKeyException;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * golden_candidate / golden_review_event 真 PG 组件测试（M5-03；落码方案 §M5-03④
 * IT 面）：发布/拒绝/撤回无旁路、双签同事务（候选 UPDATE + 事件 INSERT 原子）、
 * ck_golden_dual_review/ck_golden_terminal_reviewers DB 兜底、idempotency 唯一、
 * 事件 append-only、control_app 零权限。
 * 本机无 Docker 自动跳过（真证据待 195 释放后统一补）。
 */
class PostgresGoldenCandidateRepositoryTest extends PostgresITBase {

    private HikariDataSource evalSingleDs;
    private GoldenCandidateRepository repo;
    private GoldenCandidateService service;

    @BeforeEach
    void setUp() {
        adminJdbc.sql("DELETE FROM golden_review_event").update();
        adminJdbc.sql("DELETE FROM golden_candidate").update();

        // eval_app 单连接：事务边界在仓储内（UPDATE 候选 + INSERT 事件同事务）
        HikariConfig cfg = new HikariConfig();
        cfg.setJdbcUrl(PG.getJdbcUrl());
        cfg.setUsername(EVAL_ROLE);
        cfg.setPassword(EVAL_PASSWORD);
        cfg.setMaximumPoolSize(1);
        evalSingleDs = new HikariDataSource(cfg);
        repo = new PostgresGoldenCandidateRepository(evalSingleDs);
        service = new GoldenCandidateService(repo);
    }

    @AfterEach
    void tearDown() {
        evalSingleDs.close();
    }

    private UUID seedCaseVersion() {
        // golden_candidate.case_version_id FK 依赖 case_version 行——先种一条 TUNING 案例
        UUID datasetId = UUID.randomUUID();
        UUID caseId = UUID.randomUUID();
        adminJdbc.sql("""
                INSERT INTO dataset_version (
                    id, source, name, version, source_uri, license, access_class,
                    content_digest, adapter_version, imported_at, source_class,
                    partition_class, scenario_family_digest
                ) VALUES (:id, 'order-arena', 'fault-injection', :v, 'https://arena.internal/ds',
                    'internal', 'internal-use', :d, 'oa-v1', now(), 'PRIVATE', 'TUNING', :d)
                """)
                .param("id", datasetId).param("v", "v-" + caseId).param("d", "0".repeat(64))
                .update();
        adminJdbc.sql("""
                INSERT INTO case_version (
                    id, dataset_version_id, case_key, scenario_family_id, partition_class,
                    valid_from, valid_to, content_digest, payload, source_artifact_ref
                ) VALUES (:id, :ds, :key, 'family-f1', 'TUNING',
                    now(), null, :d, '{}'::jsonb, null)
                """)
                .param("id", caseId).param("ds", datasetId).param("key", "case-" + caseId)
                .param("d", "0".repeat(64))
                .update();
        return caseId;
    }

    @Test
    void proposeSubmitPublishFlowPersistsCandidateAndTimelineAtomically() {
        UUID caseVersionId = seedCaseVersion();

        GoldenCandidate draft = service.propose(caseVersionId,
                Map.of("root_cause", "duplicate-payment"), "复核提案", "proposer-1");
        service.submit(draft.id(), "proposer-1", 0, "it-submit");
        service.decide(draft.id(), GoldenCandidateService.Decision.PUBLISH,
                "reviewer-1", "reviewer-2", 1, "it-publish");

        GoldenCandidate published = repo.find(draft.id()).orElseThrow();
        assertThat(published.state()).isEqualTo(GoldenCandidateState.PUBLISHED);
        assertThat(published.reviewerA()).isEqualTo("reviewer-1");
        assertThat(published.reviewerB()).isEqualTo("reviewer-2");
        assertThat(published.revision()).isEqualTo(2L);

        List<GoldenReviewAction> timeline = repo.events(draft.id()).stream()
                .map(GoldenReviewEvent::action).toList();
        assertThat(timeline).containsExactly(
                GoldenReviewAction.PROPOSED, GoldenReviewAction.SUBMITTED,
                GoldenReviewAction.PUBLISHED);
    }

    @Test
    void dualSignAndTerminalReviewerChecksEnforcedByDb() {
        UUID caseVersionId = seedCaseVersion();

        // 同人双签（两签位非空相等）→ ck_golden_dual_review 拒绝
        UUID candidateId = UUID.randomUUID();
        assertThatThrownBy(() -> adminJdbc.sql("""
                        INSERT INTO golden_candidate (
                            id, case_version_id, proposed_gt, reason, state, proposed_by,
                            reviewer_a, reviewer_b, revision, created_at, updated_at
                        ) VALUES (:id, :cv, '{}'::jsonb, 'r', 'PUBLISHED', 'p',
                            'same-person', 'same-person', 2, now(), now())
                        """)
                .param("id", candidateId).param("cv", caseVersionId).update())
                .as("INV-AM5-2 DB 兜底：同人不能双签")
                .isInstanceOf(DataAccessException.class);

        // 终态单签 → ck_golden_terminal_reviewers 拒绝
        assertThatThrownBy(() -> adminJdbc.sql("""
                        INSERT INTO golden_candidate (
                            id, case_version_id, proposed_gt, reason, state, proposed_by,
                            reviewer_a, reviewer_b, revision, created_at, updated_at
                        ) VALUES (:id, :cv, '{}'::jsonb, 'r', 'PUBLISHED', 'p',
                            'only-a', null, 2, now(), now())
                        """)
                .param("id", UUID.randomUUID()).param("cv", caseVersionId).update())
                .as("PUBLISHED 单签 = 违约")
                .isInstanceOf(DataAccessException.class);
    }

    @Test
    void casTransitionRejectsStateBypassWithoutWriting() {
        UUID caseVersionId = seedCaseVersion();
        GoldenCandidate draft = service.propose(caseVersionId,
                Map.of("root_cause", "x"), "理由", "proposer-1");

        // 旁路：期望态 REVIEW 但实际 DRAFT → CAS 0 行 → false，状态不被改写
        GoldenCandidate publishedNext = new GoldenCandidate(draft.id(), draft.caseVersionId(),
                draft.proposedGt(), draft.reason(), GoldenCandidateState.PUBLISHED,
                draft.proposedBy(), "a", "b", draft.revision() + 1,
                draft.createdAt(), draft.updatedAt());
        GoldenReviewEvent event = new GoldenReviewEvent(UUID.randomUUID(), draft.id(),
                GoldenReviewAction.PUBLISHED, "a", draft.revision(), "it-bypass",
                Map.of("to", "PUBLISHED"), draft.updatedAt());
        assertThat(repo.casTransition(publishedNext, GoldenCandidateState.REVIEW, event))
                .as("发布/拒绝无旁路：状态错配 CAS 0 行")
                .isFalse();
        assertThat(repo.find(draft.id()).orElseThrow().state())
                .isEqualTo(GoldenCandidateState.DRAFT);
        assertThat(repo.events(draft.id())).as("旁路不落事件").hasSize(1);
    }

    @Test
    void idempotencyKeyIsGloballyUnique() {
        UUID caseVersionId = seedCaseVersion();
        GoldenCandidate draft = service.propose(caseVersionId,
                Map.of("root_cause", "x"), "理由", "proposer-1");

        // 服务层重放短路：同 key 二次 submit 不落第二条事件
        service.submit(draft.id(), "proposer-1", 0, "it-idem");
        service.submit(draft.id(), "proposer-1", 1, "it-idem");
        assertThat(repo.events(draft.id())).hasSize(2);

        // DB 面：同 key 直插 = DuplicateKey
        assertThatThrownBy(() -> evalJdbc.sql("""
                        INSERT INTO golden_review_event (
                            id, candidate_id, action, actor, expected_revision,
                            idempotency_key, payload, created_at
                        ) VALUES (:id, :cid, 'SUBMITTED', 'x', 0, 'it-idem', NULL, now())
                        """)
                .param("id", UUID.randomUUID()).param("cid", draft.id()).update())
                .isInstanceOf(DuplicateKeyException.class);
    }

    @Test
    void reviewEventIsAppendOnlyAndCandidateUpdateIsColumnGated() {
        UUID caseVersionId = seedCaseVersion();
        GoldenCandidate draft = service.propose(caseVersionId,
                Map.of("root_cause", "x"), "理由", "proposer-1");

        // 事件表 UPDATE/DELETE 授权面为 0（V22 只授 select,insert）
        assertThatExceptionOfType(DataAccessException.class)
                .isThrownBy(() -> evalJdbc.sql(
                        "UPDATE golden_review_event SET actor = 'tampered' WHERE candidate_id = :id")
                        .param("id", draft.id()).update());
        assertThatExceptionOfType(DataAccessException.class)
                .isThrownBy(() -> evalJdbc.sql(
                        "DELETE FROM golden_review_event WHERE candidate_id = :id")
                        .param("id", draft.id()).update());
        assertThat(repo.events(draft.id())).hasSize(1);

        // 候选整行 UPDATE 不在授权面（只有列级 UPDATE：state/reviewers/revision/updated_at）
        assertThatExceptionOfType(DataAccessException.class)
                .isThrownBy(() -> evalJdbc.sql(
                        "UPDATE golden_candidate SET reason = 'tampered' WHERE id = :id")
                        .param("id", draft.id()).update());

        // control_app 零权限（INV-AM5-2 之外的第二道隔离：复核面只有 eval 域身份可达）
        assertThatExceptionOfType(DataAccessException.class)
                .isThrownBy(() -> controlJdbc.sql("SELECT count(*) FROM golden_candidate")
                        .query(Long.class).single());
    }
}
