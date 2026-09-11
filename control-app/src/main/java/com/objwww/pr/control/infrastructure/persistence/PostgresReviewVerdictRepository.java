package com.objwww.pr.control.infrastructure.persistence;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.objwww.pr.control.eval.domain.model.ReviewVerdict;
import com.objwww.pr.control.eval.domain.repository.ReviewVerdictRepository;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * {@link ReviewVerdictRepository} 的 Postgres 实现（EV-08/V87）。insert-only：
 * 本类无 UPDATE/DELETE 语句（V87 授权面同——control_app select,insert 零改删开口）。
 * labels/evidence_refs 为自由文本 jsonb 数组，ObjectMapper 序列化（不手拼防注入），
 * ::text 原文出入，结构校验归应用服务。
 */
public class PostgresReviewVerdictRepository implements ReviewVerdictRepository {

    private static final String SELECT =
            "select id, assignment_id, run_id, case_execution_id, reviewer, rubric_version,"
                    + " verdict, score, labels::text as labels_json, reason,"
                    + " evidence_refs::text as evidence_refs_json, created_at"
                    + " from review_verdict";

    private final JdbcClient jdbc;
    private final ObjectMapper mapper;

    public PostgresReviewVerdictRepository(JdbcClient jdbc, ObjectMapper mapper) {
        this.jdbc = Objects.requireNonNull(jdbc);
        this.mapper = Objects.requireNonNull(mapper);
    }

    @Override
    public void insert(ReviewVerdict v) {
        jdbc.sql("""
                        insert into review_verdict (
                            id, assignment_id, run_id, case_execution_id, reviewer,
                            rubric_version, verdict, score, labels, reason, evidence_refs,
                            created_at
                        ) values (
                            :id, :assignmentId, :runId, :caseId, :reviewer,
                            :rubricVersion, :verdict, :score, cast(:labels as jsonb),
                            :reason, cast(:evidenceRefs as jsonb), :createdAt
                        )
                        """)
                .param("id", v.id())
                .param("assignmentId", v.assignmentId())
                .param("runId", v.runId())
                .param("caseId", v.caseExecutionId())
                .param("reviewer", v.reviewer())
                .param("rubricVersion", v.rubricVersion())
                .param("verdict", v.verdict().name())
                .param("score", v.score())
                .param("labels", toJson(v.labels()))
                .param("reason", v.reason())
                .param("evidenceRefs", toJson(v.evidenceRefs()))
                .param("createdAt", Timestamp.from(v.createdAt()))
                .update();
    }

    @Override
    public Optional<ReviewVerdict> findByAssignmentId(UUID assignmentId) {
        return jdbc.sql(SELECT + " where assignment_id = :assignmentId")
                .param("assignmentId", assignmentId)
                .query(this::map).optional();
    }

    @Override
    public List<ReviewVerdict> listByCase(UUID runId, UUID caseExecutionId) {
        return jdbc.sql(SELECT
                        + " where run_id = :runId and case_execution_id = :caseId"
                        + " order by created_at asc, id asc")
                .param("runId", runId)
                .param("caseId", caseExecutionId)
                .query(this::map).list();
    }

    @Override
    public List<ReviewVerdict> listByRun(UUID runId) {
        return jdbc.sql(SELECT + " where run_id = :runId order by created_at asc, id asc")
                .param("runId", runId)
                .query(this::map).list();
    }

    private ReviewVerdict map(ResultSet rs, int i) throws SQLException {
        return new ReviewVerdict(
                rs.getObject("id", UUID.class),
                rs.getObject("assignment_id", UUID.class),
                rs.getObject("run_id", UUID.class),
                rs.getObject("case_execution_id", UUID.class),
                rs.getString("reviewer"),
                rs.getString("rubric_version"),
                ReviewVerdict.Verdict.valueOf(rs.getString("verdict")),
                rs.getObject("score", Integer.class),
                fromJson(rs.getString("labels_json")),
                rs.getString("reason"),
                fromJson(rs.getString("evidence_refs_json")),
                rs.getTimestamp("created_at").toInstant());
    }

    private String toJson(List<String> values) {
        try {
            return mapper.writeValueAsString(values);
        } catch (Exception e) {
            throw new IllegalStateException("评审标签/证据引用序列化失败", e);
        }
    }

    /** jsonb 数组原文 → 字符串表（非文本条目跳过；解析失败 = 写面缺陷，快速失败） */
    private List<String> fromJson(String json) {
        if (json == null) {
            return List.of();
        }
        try {
            List<String> out = new java.util.ArrayList<>();
            for (var node : mapper.readTree(json)) {
                if (node.isTextual()) {
                    out.add(node.asText());
                }
            }
            return List.copyOf(out);
        } catch (Exception e) {
            throw new IllegalStateException("review_verdict jsonb 列读回解析失败", e);
        }
    }
}
