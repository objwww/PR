package com.objwww.pr.control.it;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.objwww.pr.control.alert.domain.model.ReportFeedback;
import com.objwww.pr.control.alert.domain.model.TypedRootCause;
import com.objwww.pr.control.alert.domain.repository.ReportFeedbackPort;
import com.objwww.pr.control.eval.domain.model.CaseVersion;
import com.objwww.pr.control.eval.domain.model.DatasetVersion;
import com.objwww.pr.control.eval.domain.model.EvalCaseV1;
import com.objwww.pr.control.eval.domain.model.PartitionClass;
import com.objwww.pr.control.eval.domain.model.RegressionCandidate;
import com.objwww.pr.control.eval.domain.model.RegressionReview;
import com.objwww.pr.control.eval.domain.model.SourceClass;
import com.objwww.pr.control.eval.domain.repository.RegressionCandidatePort;
import com.objwww.pr.control.infrastructure.persistence.PostgresActionAssessment;
import com.objwww.pr.control.infrastructure.persistence.PostgresDatasetVersionRepository;
import com.objwww.pr.control.infrastructure.persistence.PostgresRegressionCandidate;
import com.objwww.pr.control.infrastructure.persistence.PostgresReportFeedback;
import com.objwww.pr.control.ops.domain.model.ActionAssessment;
import com.objwww.pr.control.ops.domain.repository.ActionAssessmentPort;
import com.objwww.pr.shared.Digest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * OP 批三表真 PG 屏障（V103/V104/V105，后续优化方案 §5/§4/§3.1）：
 * report_feedback 幂等键与更正链唯一约束、rca_regression_candidate 的
 * uq(source_digest,case_key) 幂等锚与状态 CAS、rca_action_assessment 的
 * (run,logical_action_key,assessor_version,snapshot) 重入幂等——全部经
 * control_app 角色触库（V103~V105 的 grant 面随迁移真实验证）。
 */
class PostgresOpBatchIT extends PostgresITBase {

    private static final Instant NOW = Instant.parse("2026-09-13T05:00:00Z");

    private ReportFeedbackPort feedbacks;
    private RegressionCandidatePort candidates;
    private ActionAssessmentPort assessments;

    @BeforeEach
    void setUp() {
        feedbacks = new PostgresReportFeedback(controlJdbc, new ObjectMapper());
        candidates = new PostgresRegressionCandidate(controlJdbc);
        assessments = new PostgresActionAssessment(controlJdbc, new ObjectMapper());
    }

    // ------------------------------------------------------------------ V103 report_feedback

    @Test
    @DisplayName("V103 幂等键：同 (author,idempotencyKey) 二插回读既有行；异作者同键各自落行")
    void reportFeedbackIdempotencyFace() {
        ReportFeedback first = feedbacks.insert(row("op-a", "k1", null));
        ReportFeedback replay = feedbacks.insert(row("op-a", "k1", null));
        assertThat(replay.id()).isEqualTo(first.id());
        assertThat(count("report_feedback")).isEqualTo(1);

        // 异作者同键 = 新行；row() 默认随机 reportId，此处换同报告才可按报告回读两行
        ReportFeedback other = feedbacks.insert(
                row("op-b", "k1", null, first.reportId()));
        assertThat(other.id()).isNotEqualTo(first.id());
        assertThat(count("report_feedback")).isEqualTo(2);
        assertThat(feedbacks.findByReportId(first.reportId())).hasSize(2);
    }

    @Test
    @DisplayName("V103 更正链：uq(supersedes_id) 一胜一拒——败者插不进且回读胜者行")
    void reportFeedbackSupersedesSingleWinner() {
        ReportFeedback base = feedbacks.insert(row("op-a", "k1", null));
        ReportFeedback winner = feedbacks.insert(row("op-a", "k2", base.id()));
        ReportFeedback loser = feedbacks.insert(row("op-a", "k3", base.id()));

        assertThat(loser.id()).isEqualTo(winner.id());
        assertThat(count("report_feedback")).isEqualTo(2);
        Optional<ReportFeedback> read = feedbacks.findById(base.id());
        assertThat(read).isPresent();
        assertThat(read.orElseThrow().verdict()).isEqualTo(base.verdict());
    }

    private static ReportFeedback row(String author, String key, UUID supersedes) {
        return row(author, key, supersedes, UUID.randomUUID());
    }

    private static ReportFeedback row(String author, String key, UUID supersedes,
            UUID reportId) {
        return new ReportFeedback(UUID.randomUUID(), reportId, UUID.randomUUID(),
                "d".repeat(64), author, ReportFeedback.Verdict.PARTIAL, "r",
                List.of("ev-1"), supersedes, key, NOW);
    }

    // ------------------------------------------------------------------ V104 regression candidate

    @Test
    @DisplayName("V104 幂等锚：uq(source_digest,case_key) 同源同 case 二提回读既有行")
    void regressionCandidateIdempotentAnchor() {
        RegressionCandidate first = candidates.insertIfAbsent(candidate("case-1"));
        RegressionCandidate replay = candidates.insertIfAbsent(candidate("case-1"));
        assertThat(replay.id()).isEqualTo(first.id());
        assertThat(count("rca_regression_candidate")).isEqualTo(1);
        assertThat(candidates.findByState(RegressionCandidate.ST_PENDING_REVIEW))
                .hasSize(1);
    }

    @Test
    @DisplayName("V104 状态 CAS 与审核 (candidate,reviewer) 幂等")
    void regressionCandidateCasAndReview() {
        RegressionCandidate stored = candidates.insertIfAbsent(candidate("case-2"));
        assertThat(candidates.casState(stored.id(), RegressionCandidate.ST_PENDING_REVIEW,
                stored.withReviewed(RegressionCandidate.ST_ACCEPTED, "rev-1", "ok", NOW)))
                .isPresent();
        assertThat(candidates.casState(stored.id(), RegressionCandidate.ST_PENDING_REVIEW,
                stored.withReviewed(RegressionCandidate.ST_REJECTED, "rev-1", "no", NOW)))
                .as("已推进状态的条件写零行").isEmpty();

        RegressionReview review = candidates.insertReview(new RegressionReview(
                UUID.randomUUID(), stored.id(), "rev-1",
                RegressionReview.ACCEPTED_FOR_CANDIDATE, "ok", NOW));
        RegressionReview replay = candidates.insertReview(new RegressionReview(
                UUID.randomUUID(), stored.id(), "rev-1",
                RegressionReview.ACCEPTED_FOR_CANDIDATE, "ok 确认", NOW));
        assertThat(replay.id()).isEqualTo(review.id());
        assertThat(candidates.reviewsOf(stored.id())).hasSize(1);
    }

    private static RegressionCandidate candidate(String caseKey) {
        return new RegressionCandidate(UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), null, "sd-" + caseKey, caseKey, "family-x",
                RegressionCandidate.ST_PENDING_REVIEW, "proposer", NOW,
                null, null, null);
    }

    // ------------------------------------------------------------------ V105 action assessment

    @Test
    @DisplayName("V105 重入幂等：同 (run,logical_key,version,snapshot) 回读既有行；新快照新行并存")
    void actionAssessmentReentrantFaces() {
        UUID runId = UUID.randomUUID();
        ActionAssessment first = assessments.insertIfAbsent(
                action(runId, "task-1#digest-a", "snap-1"));
        ActionAssessment replay = assessments.insertIfAbsent(
                action(runId, "task-1#digest-a", "snap-1"));
        assertThat(replay.id()).isEqualTo(first.id());
        assertThat(count("rca_action_assessment")).isEqualTo(1);

        assessments.insertIfAbsent(action(runId, "task-1#digest-a", "snap-2"));
        assertThat(count("rca_action_assessment")).isEqualTo(2);
        assertThat(assessments.findByRun(runId)).hasSize(2);
    }

    private static ActionAssessment action(UUID runId, String logicalKey, String snapshot) {
        return new ActionAssessment(UUID.randomUUID(), runId, UUID.randomUUID(),
                logicalKey, "action-assessor.v1", snapshot, 3, 1,
                List.of(), 0, ActionAssessment.NEW_OBSERVATION,
                "deterministic-rules.v1", NOW);
    }

    // ------------------------------------------------- V106 dataset/case_version 授权锁

    @Test
    @DisplayName("V106+V107 control_app 写 V20 不可变版本表（materialize 真实写路径：grant + RLS 授权锁）")
    void datasetVersionWriteGrantForControlApp() {
        PostgresDatasetVersionRepository datasets = new PostgresDatasetVersionRepository(controlJdbc);
        String name = "op-it-ds-" + UUID.randomUUID();
        EvalCaseV1 content = new EvalCaseV1("case-v106", "fam-v106",
                new TypedRootCause("db.pool", "exhaustion", "POOL_EXHAUSTED"),
                List.of("LATENCY_HIGH"), Map.of("source", "op-it"));
        Digest caseDigest = Digest.sha256Of("op-it-case");
        datasets.insertDatasetVersion(new DatasetVersion(UUID.randomUUID(),
                "rca_report_feedback", name, "v1", "run://op-it", "internal", "INTERNAL",
                caseDigest, "op-it.v1", NOW, SourceClass.PRIVATE, PartitionClass.TUNING,
                DatasetVersion.familyDigest(List.of(content))));

        Optional<DatasetVersion> found = datasets.findDataset(name, "v1");
        assertThat(found).as("control_app 读回自插数据集版本").isPresent();

        assertThat(datasets.insertCaseVersion(new CaseVersion(UUID.randomUUID(),
                found.orElseThrow().id(), "case-v106", "fam-v106", NOW, null,
                caseDigest, "candidate:op-it", content)))
                .as("control_app 插案例版本行").isTrue();
        assertThat(datasets.findCasesValidAt(found.orElseThrow().id(), NOW.plusSeconds(1)))
                .hasSize(1);
    }
}
