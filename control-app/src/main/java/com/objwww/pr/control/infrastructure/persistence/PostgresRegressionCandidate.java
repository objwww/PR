package com.objwww.pr.control.infrastructure.persistence;

import com.objwww.pr.control.eval.domain.model.RegressionCandidate;
import com.objwww.pr.control.eval.domain.model.RegressionReview;
import com.objwww.pr.control.eval.domain.repository.RegressionCandidatePort;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.sql.Timestamp;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * rca_regression_candidate / rca_regression_review 的 Postgres 实现（OP-01，
 * V104）：候选幂等插入撞 uq(source_digest, case_key) 回读既有行；状态推进以
 * id+state 条件写（CAS）；审核意见 (candidate, reviewer) 幂等。
 */
public class PostgresRegressionCandidate implements RegressionCandidatePort {

    private static final String COLUMNS = """
            id, source_run_id, source_report_id, source_feedback_id, source_digest,
            case_key, scenario_family_id, state, created_by, created_at,
            reviewed_by, review_reason, reviewed_at
            """;

    private final JdbcClient jdbc;

    public PostgresRegressionCandidate(JdbcClient jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
    }

    @Override
    public RegressionCandidate insertIfAbsent(RegressionCandidate candidate) {
        try {
            jdbc.sql("""
                    insert into rca_regression_candidate (id, source_run_id,
                        source_report_id, source_feedback_id, source_digest, case_key,
                        scenario_family_id, state, created_by, created_at,
                        reviewed_by, review_reason, reviewed_at)
                    values (:id, :runId, :reportId, :feedbackId, :sourceDigest,
                        :caseKey, :familyId, :state, :createdBy, :createdAt,
                        null, null, null)
                    """)
                    .param("id", candidate.id())
                    .param("runId", candidate.sourceRunId())
                    .param("reportId", candidate.sourceReportId())
                    .param("feedbackId", candidate.sourceFeedbackId())
                    .param("sourceDigest", candidate.sourceDigest())
                    .param("caseKey", candidate.caseKey())
                    .param("familyId", candidate.scenarioFamilyId())
                    .param("state", candidate.state())
                    .param("createdBy", candidate.createdBy())
                    .param("createdAt", Timestamp.from(candidate.createdAt()))
                    .update();
            return candidate;
        } catch (DuplicateKeyException e) {
            return jdbc.sql("select " + COLUMNS
                            + " from rca_regression_candidate where source_digest = :d"
                            + " and case_key = :k")
                    .param("d", candidate.sourceDigest())
                    .param("k", candidate.caseKey())
                    .query(this::mapRow).list().stream().findFirst()
                    .orElseThrow(() -> e);
        }
    }

    @Override
    public Optional<RegressionCandidate> casState(UUID id, String fromState,
            RegressionCandidate next) {
        int updated = jdbc.sql("""
                update rca_regression_candidate
                set state = :toState, reviewed_by = :reviewedBy,
                    review_reason = :reason, reviewed_at = :reviewedAt
                where id = :id and state = :fromState
                """)
                .param("toState", next.state())
                .param("reviewedBy", next.reviewedBy())
                .param("reason", next.reviewReason())
                .param("reviewedAt",
                        next.reviewedAt() == null ? null
                                : Timestamp.from(next.reviewedAt()))
                .param("id", id)
                .param("fromState", fromState)
                .update();
        return updated == 1 ? findById(id) : Optional.empty();
    }

    @Override
    public Optional<RegressionCandidate> findById(UUID id) {
        List<RegressionCandidate> rows = jdbc.sql("select " + COLUMNS
                        + " from rca_regression_candidate where id = :id")
                .param("id", id).query(this::mapRow).list();
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    @Override
    public List<RegressionCandidate> findByState(String state) {
        return jdbc.sql("select " + COLUMNS
                        + " from rca_regression_candidate where state = :state"
                        + " order by created_at")
                .param("state", state).query(this::mapRow).list();
    }

    @Override
    public RegressionReview insertReview(RegressionReview review) {
        try {
            jdbc.sql("""
                    insert into rca_regression_review (id, candidate_id, reviewer,
                        verdict, reason, created_at)
                    values (:id, :candidateId, :reviewer, :verdict, :reason, :createdAt)
                    """)
                    .param("id", review.id())
                    .param("candidateId", review.candidateId())
                    .param("reviewer", review.reviewer())
                    .param("verdict", review.verdict())
                    .param("reason", review.reason())
                    .param("createdAt", Timestamp.from(review.createdAt()))
                    .update();
            return review;
        } catch (DuplicateKeyException e) {
            return jdbc.sql("""
                            select id, candidate_id, reviewer, verdict, reason, created_at
                            from rca_regression_review
                            where candidate_id = :cid and reviewer = :reviewer
                            """)
                    .param("cid", review.candidateId())
                    .param("reviewer", review.reviewer())
                    .query((rs, i) -> new RegressionReview(
                            rs.getObject("id", UUID.class),
                            rs.getObject("candidate_id", UUID.class),
                            rs.getString("reviewer"), rs.getString("verdict"),
                            rs.getString("reason"),
                            rs.getTimestamp("created_at").toInstant()))
                    .list().stream().findFirst().orElseThrow(() -> e);
        }
    }

    @Override
    public List<RegressionReview> reviewsOf(UUID candidateId) {
        return jdbc.sql("""
                        select id, candidate_id, reviewer, verdict, reason, created_at
                        from rca_regression_review where candidate_id = :cid
                        order by created_at
                        """)
                .param("cid", candidateId)
                .query((rs, i) -> new RegressionReview(
                        rs.getObject("id", UUID.class),
                        rs.getObject("candidate_id", UUID.class),
                        rs.getString("reviewer"), rs.getString("verdict"),
                        rs.getString("reason"),
                        rs.getTimestamp("created_at").toInstant()))
                .list();
    }

    private RegressionCandidate mapRow(java.sql.ResultSet rs, int rowNum)
            throws java.sql.SQLException {
        Timestamp reviewedAt = rs.getTimestamp("reviewed_at");
        return new RegressionCandidate(rs.getObject("id", UUID.class),
                rs.getObject("source_run_id", UUID.class),
                rs.getObject("source_report_id", UUID.class),
                rs.getObject("source_feedback_id", UUID.class),
                rs.getString("source_digest"), rs.getString("case_key"),
                rs.getString("scenario_family_id"), rs.getString("state"),
                rs.getString("created_by"),
                rs.getTimestamp("created_at").toInstant(),
                rs.getString("reviewed_by"), rs.getString("review_reason"),
                reviewedAt == null ? null : reviewedAt.toInstant());
    }
}
