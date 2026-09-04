package com.objwww.pr.control.infrastructure.persistence;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.objwww.pr.control.alert.domain.model.AlertFiringStatus;
import com.objwww.pr.control.alert.domain.model.AlertGroupEnvelope;
import com.objwww.pr.control.alert.domain.model.AlertInbox;
import com.objwww.pr.control.alert.domain.model.InboxDecision;
import com.objwww.pr.control.alert.domain.model.InboxState;
import com.objwww.pr.control.alert.domain.repository.AlertInboxRepository;
import com.objwww.pr.shared.Digest;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * alert_inbox 的 Postgres 实现（V7 全列；六态+租约+退避）。
 * 不带 @Repository，由 PersistenceConfig 手工装配（仅 docker profile）。
 */
public class PostgresAlertInboxRepository implements AlertInboxRepository {

    private static final String INSERT_SQL = """
            INSERT INTO alert_inbox (
                id, version, receiver, group_key, group_labels, common_labels, common_annotations,
                external_url, group_status, truncated_alerts, alert_count,
                payload_raw, payload_digest, state, decision,
                lease_owner, lease_until, lease_epoch,
                attempt_count, max_attempts, next_retry_at, last_error,
                received_at, updated_at, processed_at
            ) VALUES (
                :id, :version, :receiver, :groupKey, CAST(:groupLabels AS jsonb),
                CAST(:commonLabels AS jsonb), CAST(:commonAnnotations AS jsonb),
                :externalUrl, :groupStatus, :truncatedAlerts, :alertCount,
                :payloadRaw, :payloadDigest, :state, :decision,
                :leaseOwner, :leaseUntil, :leaseEpoch,
                :attemptCount, :maxAttempts, :nextRetryAt, CAST(:lastError AS jsonb),
                :receivedAt, :updatedAt, :processedAt
            )
            """;

    /** 领取：公平排序 + SKIP LOCKED + 租约翻转 + epoch+1（端口契约原文） */
    private static final String CLAIM_SQL = """
            UPDATE alert_inbox SET
                state = 'PROCESSING',
                lease_owner = :owner,
                lease_until = :leaseUntil,
                lease_epoch = lease_epoch + 1,
                updated_at = :now
            WHERE id = (
                SELECT id FROM alert_inbox
                 WHERE state IN ('RECEIVED', 'RETRY_WAIT')
                   AND (next_retry_at IS NULL OR next_retry_at <= :now)
                 ORDER BY next_retry_at NULLS FIRST, received_at
                 LIMIT 1 FOR UPDATE SKIP LOCKED
            )
            RETURNING *
            """;

    private static final String SELECT_BY_ID = "SELECT * FROM alert_inbox WHERE id = :id";

    private final JdbcClient jdbc;
    private final ObjectMapper mapper;

    public PostgresAlertInboxRepository(JdbcClient jdbc, ObjectMapper mapper) {
        this.jdbc = Objects.requireNonNull(jdbc);
        this.mapper = Objects.requireNonNull(mapper);
    }

    @Override
    public void insert(AlertInbox row) {
        jdbc.sql(INSERT_SQL)
                .param("id", row.id())
                .param("version", row.envelope().version())
                .param("receiver", row.envelope().receiver())
                .param("groupKey", row.envelope().groupKey())
                .param("groupLabels", toJson(row.envelope().groupLabels()))
                .param("commonLabels", toJson(row.envelope().commonLabels()))
                .param("commonAnnotations", toJson(row.envelope().commonAnnotations()))
                .param("externalUrl", row.envelope().externalUrl())
                .param("groupStatus", row.envelope().groupStatus().raw())
                .param("truncatedAlerts", row.envelope().truncatedAlerts())
                .param("alertCount", row.envelope().alertCount())
                .param("payloadRaw", row.envelope().payloadRaw())
                .param("payloadDigest", row.envelope().payloadDigest().value())
                .param("state", row.state().name())
                .param("decision", row.decision() == null ? null : row.decision().name())
                .param("leaseOwner", row.leaseOwner())
                .param("leaseUntil", row.leaseUntil() == null ? null : Timestamp.from(row.leaseUntil()))
                .param("leaseEpoch", row.leaseEpoch())
                .param("attemptCount", row.attemptCount())
                .param("maxAttempts", row.maxAttempts())
                .param("nextRetryAt", row.nextRetryAt() == null ? null : Timestamp.from(row.nextRetryAt()))
                .param("lastError", JsonbText.encode(row.lastError()))
                .param("receivedAt", Timestamp.from(row.receivedAt()))
                .param("updatedAt", Timestamp.from(row.updatedAt()))
                .param("processedAt", row.processedAt() == null ? null : Timestamp.from(row.processedAt()))
                .update();
    }

    @Override
    public Optional<AlertInbox> claimNext(String owner, Instant now, Duration lease) {
        List<AlertInbox> rows = jdbc.sql(CLAIM_SQL)
                .param("owner", owner)
                .param("now", Timestamp.from(now))
                .param("leaseUntil", Timestamp.from(now.plus(lease)))
                .query(this::mapRow)
                .list();
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    @Override
    public boolean complete(UUID id, long leaseEpoch, InboxDecision decision, Instant now) {
        return jdbc.sql("""
                UPDATE alert_inbox SET state = 'PROCESSED', decision = :decision,
                       processed_at = :now, updated_at = :now
                 WHERE id = :id AND lease_epoch = :epoch AND state = 'PROCESSING'
                """)
                .param("decision", decision.name())
                .param("now", Timestamp.from(now))
                .param("id", id).param("epoch", leaseEpoch)
                .update() > 0;
    }

    @Override
    public boolean scheduleRetry(UUID id, long leaseEpoch, InboxDecision decision, String lastError,
                                 Instant nextRetryAt, Instant now) {
        return jdbc.sql("""
                UPDATE alert_inbox SET state = 'RETRY_WAIT', attempt_count = attempt_count + 1,
                       decision = :decision,
                       next_retry_at = :nextRetryAt, last_error = CAST(:lastError AS jsonb),
                       updated_at = :now
                 WHERE id = :id AND lease_epoch = :epoch AND state = 'PROCESSING'
                """)
                .param("decision", decision == null ? null : decision.name())
                .param("nextRetryAt", Timestamp.from(nextRetryAt))
                .param("lastError", JsonbText.encode(lastError))
                .param("now", Timestamp.from(now))
                .param("id", id).param("epoch", leaseEpoch)
                .update() > 0;
    }

    @Override
    public boolean markDeadLetter(UUID id, long leaseEpoch, String lastError, Instant now) {
        return jdbc.sql("""
                UPDATE alert_inbox SET state = 'DEAD_LETTER',
                       attempt_count = attempt_count + 1, last_error = CAST(:lastError AS jsonb),
                       processed_at = :now, updated_at = :now
                 WHERE id = :id AND lease_epoch = :epoch AND state = 'PROCESSING'
                """)
                .param("lastError", JsonbText.encode(lastError))
                .param("now", Timestamp.from(now))
                .param("id", id).param("epoch", leaseEpoch)
                .update() > 0;
    }

    @Override
    public boolean markIgnored(UUID id, long leaseEpoch, Instant now) {
        return jdbc.sql("""
                UPDATE alert_inbox SET state = 'IGNORED', processed_at = :now, updated_at = :now
                 WHERE id = :id AND lease_epoch = :epoch AND state = 'PROCESSING'
                """)
                .param("now", Timestamp.from(now))
                .param("id", id).param("epoch", leaseEpoch)
                .update() > 0;
    }

    @Override
    public long reclaimExpired(Instant now) {
        return jdbc.sql("""
                UPDATE alert_inbox SET state = 'RECEIVED', updated_at = :now
                 WHERE state = 'PROCESSING' AND lease_until < :now
                """)
                .param("now", Timestamp.from(now))
                .update();
    }

    @Override
    public Optional<AlertInbox> findById(UUID id) {
        List<AlertInbox> rows = jdbc.sql(SELECT_BY_ID)
                .param("id", id)
                .query(this::mapRow)
                .list();
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    private AlertInbox mapRow(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
        Timestamp leaseUntil = rs.getTimestamp("lease_until");
        Timestamp nextRetryAt = rs.getTimestamp("next_retry_at");
        Timestamp processedAt = rs.getTimestamp("processed_at");
        String decision = rs.getString("decision");

        AlertGroupEnvelope envelope = new AlertGroupEnvelope(
                rs.getString("version"),
                rs.getString("receiver"),
                rs.getString("group_key"),
                fromJson(rs.getString("group_labels")),
                fromJson(rs.getString("common_labels")),
                fromJson(rs.getString("common_annotations")),
                rs.getString("external_url"),
                AlertFiringStatus.fromRaw(rs.getString("group_status")),
                rs.getInt("truncated_alerts"),
                rs.getInt("alert_count"),
                rs.getBytes("payload_raw"),
                new Digest(rs.getString("payload_digest")));

        return new AlertInbox(
                rs.getObject("id", UUID.class),
                envelope,
                InboxState.valueOf(rs.getString("state")),
                decision == null ? null : InboxDecision.valueOf(decision),
                rs.getString("lease_owner"),
                leaseUntil == null ? null : leaseUntil.toInstant(),
                rs.getLong("lease_epoch"),
                rs.getInt("attempt_count"),
                rs.getInt("max_attempts"),
                nextRetryAt == null ? null : nextRetryAt.toInstant(),
                JsonbText.decode(rs.getString("last_error")),
                rs.getTimestamp("received_at").toInstant(),
                rs.getTimestamp("updated_at").toInstant(),
                processedAt == null ? null : processedAt.toInstant());
    }

    private String toJson(Map<String, String> labels) {
        try {
            return mapper.writeValueAsString(labels);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("标签无法序列化为 jsonb", e);
        }
    }

    private Map<String, String> fromJson(String json) {
        try {
            return mapper.readValue(json, new TypeReference<>() {
            });
        } catch (Exception e) {
            throw new IllegalStateException("标签 jsonb 反序列化失败", e);
        }
    }
}
