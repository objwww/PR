package com.objwww.pr.control.infrastructure.persistence;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.objwww.pr.control.alert.domain.model.AlertEvent;
import com.objwww.pr.control.alert.domain.model.AlertFiringStatus;
import com.objwww.pr.control.alert.domain.repository.AlertEventRepository;
import com.objwww.pr.shared.Digest;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * alert_event 的 Postgres 实现（不可变追加；INV-AM1-5 只 INSERT+SELECT——
 * V7 未授 UPDATE，DB 层天然只增）。uq_alert_event_dedup 冲突原样抛
 * DuplicateKeyException（投影层 received-only 语义的锚点）。
 */
public class PostgresAlertEventRepository implements AlertEventRepository {

    private static final String INSERT_SQL = """
            INSERT INTO alert_event (
                id, inbox_id, incident_id, generation,
                fingerprint, status, labels, annotations, starts_at, ends_at,
                payload_hash, investigation_hash
            ) VALUES (
                :id, :inboxId, :incidentId, :generation,
                :fingerprint, :status, CAST(:labels AS jsonb), CAST(:annotations AS jsonb),
                :startsAt, :endsAt, :payloadHash, :investigationHash
            )
            """;

    private static final String SELECT_BY_INCIDENT = """
            SELECT * FROM alert_event WHERE incident_id = :incidentId ORDER BY recorded_at
            """;

    private final JdbcClient jdbc;
    private final ObjectMapper mapper;

    public PostgresAlertEventRepository(JdbcClient jdbc, ObjectMapper mapper) {
        this.jdbc = Objects.requireNonNull(jdbc);
        this.mapper = Objects.requireNonNull(mapper);
    }

    @Override
    public void append(AlertEvent event) {
        Timestamp endsAt = event.endsAt() == null ? null : Timestamp.from(event.endsAt());
        jdbc.sql(INSERT_SQL)
                .param("id", event.id())
                .param("inboxId", event.inboxId())
                .param("incidentId", event.incidentId())
                .param("generation", event.generation())
                .param("fingerprint", event.fingerprint())
                .param("status", event.status().raw())
                .param("labels", toJson(event.labels()))
                .param("annotations", toJson(event.annotations()))
                .param("startsAt", Timestamp.from(event.startsAt()))
                .param("endsAt", endsAt)
                .param("payloadHash", event.payloadHash().value())
                .param("investigationHash", event.investigationHash().value())
                .update();
    }

    @Override
    public boolean existsByDedup(String fingerprint, Digest payloadHash, Instant startsAt) {
        return jdbc.sql("""
                SELECT 1 FROM alert_event
                 WHERE fingerprint = :fingerprint AND payload_hash = :payloadHash
                   AND starts_at = :startsAt
                 LIMIT 1
                """)
                .param("fingerprint", fingerprint)
                .param("payloadHash", payloadHash.value())
                .param("startsAt", Timestamp.from(startsAt))
                .query(Integer.class)
                .list().size() > 0;
    }

    @Override
    public List<AlertEvent> findByIncidentId(UUID incidentId) {
        return jdbc.sql(SELECT_BY_INCIDENT)
                .param("incidentId", incidentId)
                .query(this::mapRow)
                .list();
    }

    private AlertEvent mapRow(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
        Timestamp endsAt = rs.getTimestamp("ends_at");
        return new AlertEvent(
                rs.getObject("id", UUID.class),
                rs.getObject("inbox_id", UUID.class),
                rs.getObject("incident_id", UUID.class),
                rs.getInt("generation"),
                rs.getString("fingerprint"),
                AlertFiringStatus.fromRaw(rs.getString("status")),
                fromJson(rs.getString("labels")),
                fromJson(rs.getString("annotations")),
                rs.getTimestamp("starts_at").toInstant(),
                endsAt == null ? null : endsAt.toInstant(),
                new Digest(rs.getString("payload_hash")),
                new Digest(rs.getString("investigation_hash")),
                rs.getTimestamp("recorded_at").toInstant());
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
