package com.objwww.pr.control.infrastructure.persistence;

import com.objwww.pr.control.alert.domain.model.RcaReport;
import com.objwww.pr.control.alert.domain.model.ValidationStatus;
import com.objwww.pr.control.alert.domain.repository.RcaReportRepository;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.sql.Timestamp;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * rca_report 的 Postgres 实现（只 INSERT+SELECT；validation_status 结构验证链落点）。
 */
public class PostgresRcaReportRepository implements RcaReportRepository {

    private final JdbcClient jdbc;

    public PostgresRcaReportRepository(JdbcClient jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc);
    }

    @Override
    public void insert(RcaReport report) {
        jdbc.sql("""
                INSERT INTO rca_report (
                    id, run_id, attempt_id, schema_version, validation_status, validation_errors,
                    package_json, raw_text, model,
                    prompt_tokens, completion_tokens, total_tokens, usage_missing, created_at
                ) VALUES (
                    :id, :runId, :attemptId, :schemaVersion, :validationStatus,
                    CAST(:validationErrors AS jsonb),
                    CAST(:packageJson AS jsonb), :rawText, :model,
                    :promptTokens, :completionTokens, :totalTokens, :usageMissing, :createdAt
                )
                """)
                .param("id", report.id())
                .param("runId", report.runId())
                .param("attemptId", report.attemptId())
                .param("schemaVersion", report.schemaVersion())
                .param("validationStatus", report.validationStatus().name())
                .param("validationErrors", toJsonArray(report.validationErrors()))
                .param("packageJson", report.packageJson())
                .param("rawText", report.rawText())
                .param("model", report.model())
                .param("promptTokens", report.promptTokens())
                .param("completionTokens", report.completionTokens())
                .param("totalTokens", report.totalTokens())
                .param("usageMissing", report.usageMissing())
                .param("createdAt", Timestamp.from(report.createdAt()))
                .update();
    }

    @Override
    public List<RcaReport> findByRunId(UUID runId) {
        return jdbc.sql("SELECT * FROM rca_report WHERE run_id = :runId ORDER BY created_at")
                .param("runId", runId)
                .query(this::mapRow)
                .list();
    }

    private static String toJsonArray(List<String> errors) {
        if (errors == null || errors.isEmpty()) {
            return null;
        }
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < errors.size(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append('"').append(errors.get(i).replace("\\", "\\\\").replace("\"", "\\\"")).append('"');
        }
        return sb.append(']').toString();
    }

    private RcaReport mapRow(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
        return new RcaReport(
                rs.getObject("id", UUID.class),
                rs.getObject("run_id", UUID.class),
                rs.getObject("attempt_id", UUID.class),
                rs.getInt("schema_version"),
                ValidationStatus.valueOf(rs.getString("validation_status")),
                null,
                rs.getString("package_json"),
                rs.getString("raw_text"),
                rs.getString("model"),
                (Integer) rs.getObject("prompt_tokens"),
                (Integer) rs.getObject("completion_tokens"),
                (Integer) rs.getObject("total_tokens"),
                rs.getBoolean("usage_missing"),
                rs.getTimestamp("created_at").toInstant());
    }
}
