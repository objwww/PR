package com.objwww.pr.control.infrastructure.persistence;

import com.objwww.pr.control.alert.domain.model.NotifyOutboxEntry;
import com.objwww.pr.control.alert.domain.model.OutboxState;
import com.objwww.pr.control.alert.domain.repository.NotifyOutboxRepository;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * notify_outbox 的 Postgres 实现（V9；M3-19 生产者面）。
 * 领取/退避/终态迁移 SQL 在 notify-app 投递侧（M3-20/23）。
 */
public class PostgresNotifyOutboxRepository implements NotifyOutboxRepository {

    private static final String INSERT_SQL = """
            INSERT INTO notify_outbox (
                id, publication_id, report_id, channel, template_version, operation_id,
                payload_json, state, attempt_count, max_attempts, available_at,
                created_at, updated_at
            ) VALUES (
                :id, :publicationId, :reportId, :channel, :templateVersion, :operationId,
                CAST(:payloadJson AS jsonb), :state, :attemptCount, :maxAttempts, :availableAt,
                :createdAt, :updatedAt
            )
            """;

    private final JdbcClient jdbc;

    public PostgresNotifyOutboxRepository(JdbcClient jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc);
    }

    @Override
    public void insert(NotifyOutboxEntry entry) {
        jdbc.sql(INSERT_SQL)
                .param("id", entry.id())
                .param("publicationId", entry.publicationId())
                .param("reportId", entry.reportId())
                .param("channel", entry.channel())
                .param("templateVersion", entry.templateVersion())
                .param("operationId", entry.operationId())
                .param("payloadJson", entry.payloadJson())
                .param("state", entry.state().name())
                .param("attemptCount", entry.attemptCount())
                .param("maxAttempts", entry.maxAttempts())
                .param("availableAt", ts(entry.availableAt()))
                .param("createdAt", ts(entry.createdAt()))
                .param("updatedAt", ts(entry.updatedAt()))
                .update();
    }

    @Override
    public List<NotifyOutboxEntry> findByPublicationId(UUID publicationId) {
        return jdbc.sql("""
                        SELECT * FROM notify_outbox WHERE publication_id = :pid
                        ORDER BY created_at, channel
                        """)
                .param("pid", publicationId)
                .query(this::mapRow)
                .list();
    }

    private NotifyOutboxEntry mapRow(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
        Timestamp leaseUntil = rs.getTimestamp("lease_until");
        Timestamp availableAt = rs.getTimestamp("available_at");
        Timestamp sentAt = rs.getTimestamp("sent_at");
        Instant created = rs.getTimestamp("created_at").toInstant();
        return new NotifyOutboxEntry(
                rs.getObject("id", UUID.class),
                rs.getObject("publication_id", UUID.class),
                rs.getObject("report_id", UUID.class),
                rs.getString("channel"),
                rs.getString("template_version"),
                rs.getObject("operation_id", UUID.class),
                rs.getString("payload_json"),
                OutboxState.valueOf(rs.getString("state")),
                rs.getString("lease_owner"),
                leaseUntil == null ? null : leaseUntil.toInstant(),
                rs.getLong("lease_epoch"),
                rs.getInt("attempt_count"),
                rs.getInt("max_attempts"),
                availableAt == null ? null : availableAt.toInstant(),
                rs.getString("last_error"),
                sentAt == null ? null : sentAt.toInstant(),
                created,
                rs.getTimestamp("updated_at").toInstant());
    }

    private static Timestamp ts(Instant instant) {
        return instant == null ? null : Timestamp.from(instant);
    }
}
