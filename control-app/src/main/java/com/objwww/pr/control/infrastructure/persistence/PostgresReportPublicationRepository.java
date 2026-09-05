package com.objwww.pr.control.infrastructure.persistence;

import com.objwww.pr.control.alert.domain.model.ReportPublication;
import com.objwww.pr.control.alert.domain.model.PublicationState;
import com.objwww.pr.control.alert.domain.repository.ReportPublicationRepository;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * report_publication 的 Postgres 实现（V9；M3-09）。control 侧只出生（INSERT）+ 查询；
 * 状态机迁移 SQL 在 notify-app 投递侧（同表，列级授权隔离）。
 */
public class PostgresReportPublicationRepository implements ReportPublicationRepository {

    private static final String INSERT_SQL = """
            INSERT INTO report_publication (
                id, report_id, state, lease_epoch, attempt_count, max_attempts,
                available_at, created_at, updated_at
            ) VALUES (
                :id, :reportId, :state, :leaseEpoch, :attemptCount, :maxAttempts,
                :availableAt, :createdAt, :updatedAt
            )
            """;

    private final JdbcClient jdbc;

    public PostgresReportPublicationRepository(JdbcClient jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc);
    }

    @Override
    public void insert(ReportPublication publication) {
        jdbc.sql(INSERT_SQL)
                .param("id", publication.id())
                .param("reportId", publication.reportId())
                .param("state", publication.state().name())
                .param("leaseEpoch", publication.leaseEpoch())
                .param("attemptCount", publication.attemptCount())
                .param("maxAttempts", publication.maxAttempts())
                .param("availableAt", ts(publication.availableAt()))
                .param("createdAt", ts(publication.createdAt()))
                .param("updatedAt", ts(publication.updatedAt()))
                .update();
    }

    @Override
    public Optional<ReportPublication> findByReportId(UUID reportId) {
        return jdbc.sql("SELECT * FROM report_publication WHERE report_id = :reportId")
                .param("reportId", reportId)
                .query(this::mapRow)
                .optional();
    }

    private ReportPublication mapRow(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
        Timestamp leaseUntil = rs.getTimestamp("lease_until");
        Timestamp availableAt = rs.getTimestamp("available_at");
        Instant updated = rs.getTimestamp("updated_at").toInstant();
        return new ReportPublication(
                rs.getObject("id", UUID.class),
                rs.getObject("report_id", UUID.class),
                PublicationState.valueOf(rs.getString("state")),
                rs.getString("lease_owner"),
                leaseUntil == null ? null : leaseUntil.toInstant(),
                rs.getLong("lease_epoch"),
                rs.getInt("attempt_count"),
                rs.getInt("max_attempts"),
                availableAt == null ? null : availableAt.toInstant(),
                rs.getString("last_error"),
                rs.getTimestamp("created_at").toInstant(),
                updated);
    }

    private static Timestamp ts(Instant instant) {
        return instant == null ? null : Timestamp.from(instant);
    }
}
