package com.objwww.pr.control.infrastructure.persistence;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.objwww.pr.control.alert.domain.model.ValidationStatus;
import com.objwww.pr.control.alert.domain.repository.RcaReportReader;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * {@link RcaReportReader} 的 Postgres 实现（V7 rca_report）。
 *
 * <p>显式列清单 SELECT（不 SELECT *、不取 raw_text）——读面授权列即透出列，
 * Holmes 原文在 SQL 层即不离开表。validation_errors（jsonb 字符串数组，可空）
 * 由 Jackson 还原为 List&lt;String&gt;。
 */
public class PostgresRcaReportReader implements RcaReportReader {

    private final JdbcClient jdbc;
    private final ObjectMapper mapper;

    public PostgresRcaReportReader(JdbcClient jdbc, ObjectMapper mapper) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        this.mapper = Objects.requireNonNull(mapper, "mapper");
    }

    @Override
    public List<RcaReportView> findByRunId(UUID runId) {
        return jdbc.sql("""
                SELECT id, run_id, attempt_id, schema_version, validation_status, validation_errors,
                       package_json, model, prompt_tokens, completion_tokens, total_tokens,
                       usage_missing, created_at
                  FROM rca_report
                 WHERE run_id = :runId
                 ORDER BY created_at, id
                """)
                .param("runId", runId)
                .query(this::mapRow)
                .list();
    }

    private RcaReportView mapRow(ResultSet rs, int rowNum) throws SQLException {
        return new RcaReportView(
                rs.getObject("id", UUID.class),
                rs.getObject("run_id", UUID.class),
                rs.getObject("attempt_id", UUID.class),
                rs.getInt("schema_version"),
                ValidationStatus.valueOf(rs.getString("validation_status")),
                errorsOf(rs.getString("validation_errors")),
                rs.getString("package_json"),
                rs.getString("model"),
                (Integer) rs.getObject("prompt_tokens"),
                (Integer) rs.getObject("completion_tokens"),
                (Integer) rs.getObject("total_tokens"),
                rs.getBoolean("usage_missing"),
                rs.getTimestamp("created_at").toInstant());
    }

    /** validation_errors jsonb（写面 PostgresRcaReportRepository.toJsonArray 同键）→ List；NULL → 空表 */
    private List<String> errorsOf(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return mapper.readValue(json, new TypeReference<List<String>>() {
            });
        } catch (Exception e) {
            throw new IllegalStateException("rca_report.validation_errors 非合法 JSON 数组", e);
        }
    }
}
