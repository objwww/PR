package com.objwww.pr.control.infrastructure.persistence;

import com.objwww.pr.control.alert.domain.repository.RunFallbackRepository;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * run_fallback 的 Postgres 实现（V33，M6-04）。insert-only：只占位/计数/读，零
 * UPDATE/DELETE 路径（授权面同构封死）；uq_rf_source 冲突 = ON CONFLICT DO NOTHING
 * 返回 false（并发败者语义，非错误）。
 */
public class PostgresRunFallbackRepository implements RunFallbackRepository {

    private static final String OCCUPY_SQL = """
            INSERT INTO run_fallback (
                source_native_run_id, source_incident_id, generation, fallback_run_id,
                depth, error_class, created_at
            ) VALUES (
                :sourceNativeRunId, :sourceIncidentId, :generation, :fallbackRunId,
                :depth, :errorClass, :createdAt
            )
            ON CONFLICT ON CONSTRAINT uq_rf_source DO NOTHING
            """;

    private static final String COUNT_SQL = """
            SELECT count(*) FROM run_fallback WHERE created_at >= :after
            """;

    private static final String FIND_SQL = """
            SELECT source_native_run_id, source_incident_id, generation, fallback_run_id,
                   depth, error_class, created_at
              FROM run_fallback
             WHERE source_native_run_id = :sourceNativeRunId
            """;

    private final JdbcClient jdbc;

    public PostgresRunFallbackRepository(JdbcClient jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc 不得为 null");
    }

    @Override
    public boolean insertOccupancy(OccupancyRow row) {
        int inserted = jdbc.sql(OCCUPY_SQL)
                .param("sourceNativeRunId", row.sourceNativeRunId())
                .param("sourceIncidentId", row.sourceIncidentId())
                .param("generation", row.generation())
                .param("fallbackRunId", row.fallbackRunId())
                .param("depth", row.depth())
                .param("errorClass", row.errorClass())
                .param("createdAt", Timestamp.from(row.createdAt()))
                .update();
        return inserted == 1;
    }

    @Override
    public long countCreatedSince(Instant after) {
        return jdbc.sql(COUNT_SQL)
                .param("after", Timestamp.from(after))
                .query(Long.class)
                .single();
    }

    @Override
    public Optional<OccupancyRow> findBySourceRunId(UUID sourceNativeRunId) {
        return jdbc.sql(FIND_SQL)
                .param("sourceNativeRunId", sourceNativeRunId)
                .query((rs, n) -> new OccupancyRow(
                        UUID.fromString(rs.getString("source_native_run_id")),
                        UUID.fromString(rs.getString("source_incident_id")),
                        rs.getInt("generation"),
                        UUID.fromString(rs.getString("fallback_run_id")),
                        rs.getInt("depth"),
                        rs.getString("error_class"),
                        rs.getTimestamp("created_at").toInstant()))
                .optional();
    }
}
