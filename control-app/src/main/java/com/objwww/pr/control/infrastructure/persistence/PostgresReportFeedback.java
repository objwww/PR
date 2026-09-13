package com.objwww.pr.control.infrastructure.persistence;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.objwww.pr.control.alert.domain.model.ReportFeedback;
import com.objwww.pr.control.alert.domain.repository.ReportFeedbackPort;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.sql.Timestamp;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * report_feedback 的 Postgres 实现（OP-04，V103）：幂等冲突回读既有行；
 * uq(supersedes_id) 冲突回读既有更正（服务层判"冲突更正可辨"）。
 */
public class PostgresReportFeedback implements ReportFeedbackPort {

    private static final TypeReference<List<String>> REFS =
            new TypeReference<>() {
            };

    private final JdbcClient jdbc;
    private final ObjectMapper mapper;

    public PostgresReportFeedback(JdbcClient jdbc, ObjectMapper mapper) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        this.mapper = Objects.requireNonNull(mapper, "mapper");
    }

    @Override
    public ReportFeedback insert(ReportFeedback candidate) {
        try {
            jdbc.sql("""
                    insert into report_feedback (id, report_id, run_id, report_digest,
                        author, verdict, reason, evidence_refs, supersedes_id,
                        idempotency_key, created_at)
                    values (:id, :reportId, :runId, :reportDigest, :author, :verdict,
                        :reason, cast(:evidenceRefs as jsonb), :supersedesId,
                        :idempotencyKey, :createdAt)
                    """)
                    .param("id", candidate.id())
                    .param("reportId", candidate.reportId())
                    .param("runId", candidate.runId())
                    .param("reportDigest", candidate.reportDigest())
                    .param("author", candidate.author())
                    .param("verdict", candidate.verdict().name())
                    .param("reason", candidate.reason())
                    .param("evidenceRefs", jsonOf(candidate.evidenceRefs()))
                    .param("supersedesId", candidate.supersedesId())
                    .param("idempotencyKey", candidate.idempotencyKey())
                    .param("createdAt", Timestamp.from(candidate.createdAt()))
                    .update();
            return candidate;
        } catch (DuplicateKeyException e) {
            // (author, idempotency_key) 幂等面或 uq(supersedes_id) 更正面
            return findByAuthorIdempotency(candidate.author(), candidate.idempotencyKey())
                    .or(() -> candidate.supersedesId() == null ? Optional.empty()
                            : findBySupersedes(candidate.supersedesId()))
                    .orElseThrow(() -> e);
        }
    }

    @Override
    public List<ReportFeedback> findByReportId(UUID reportId) {
        return jdbc.sql("select * from report_feedback where report_id = :reportId"
                        + " order by created_at, id")
                .param("reportId", reportId).query(this::mapRow).list();
    }

    @Override
    public Optional<ReportFeedback> findById(UUID id) {
        List<ReportFeedback> rows = jdbc.sql(
                        "select * from report_feedback where id = :id")
                .param("id", id).query(this::mapRow).list();
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    private Optional<ReportFeedback> findByAuthorIdempotency(String author, String key) {
        List<ReportFeedback> rows = jdbc.sql(
                        "select * from report_feedback where author = :author"
                                + " and idempotency_key = :key")
                .param("author", author).param("key", key).query(this::mapRow).list();
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    private Optional<ReportFeedback> findBySupersedes(UUID supersedesId) {
        List<ReportFeedback> rows = jdbc.sql(
                        "select * from report_feedback where supersedes_id = :sid")
                .param("sid", supersedesId).query(this::mapRow).list();
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    private ReportFeedback mapRow(java.sql.ResultSet rs, int rowNum)
            throws java.sql.SQLException {
        return new ReportFeedback(rs.getObject("id", UUID.class),
                rs.getObject("report_id", UUID.class),
                rs.getObject("run_id", UUID.class),
                rs.getString("report_digest"), rs.getString("author"),
                ReportFeedback.Verdict.valueOf(rs.getString("verdict")),
                rs.getString("reason"), refsOf(rs.getString("evidence_refs")),
                rs.getObject("supersedes_id", UUID.class),
                rs.getString("idempotency_key"),
                rs.getTimestamp("created_at").toInstant());
    }

    private String jsonOf(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException("evidence_refs 序列化失败: " + e.getMessage(), e);
        }
    }

    private List<String> refsOf(String json) {
        try {
            return mapper.readValue(json == null ? "[]" : json, REFS);
        } catch (Exception e) {
            throw new IllegalStateException("evidence_refs 解析失败: " + e.getMessage(), e);
        }
    }
}
