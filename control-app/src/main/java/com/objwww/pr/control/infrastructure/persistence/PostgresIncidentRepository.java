package com.objwww.pr.control.infrastructure.persistence;

import com.objwww.pr.control.alert.domain.model.Incident;
import com.objwww.pr.control.alert.domain.model.IncidentStatus;
import com.objwww.pr.control.alert.domain.repository.IncidentRepository;
import com.objwww.pr.shared.Digest;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * incident 的 Postgres 实现。findByKeyForUpdate = 行锁（§6.7 投影/收尾算法的 lock incident）。
 */
public class PostgresIncidentRepository implements IncidentRepository {

    private static final String SELECT_BASE = "SELECT * FROM incident";

    private final JdbcClient jdbc;

    public PostgresIncidentRepository(JdbcClient jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc);
    }

    @Override
    public Optional<Incident> findByKeyForUpdate(String incidentKey) {
        List<Incident> rows = jdbc.sql(SELECT_BASE + " WHERE incident_key = :key FOR UPDATE")
                .param("key", incidentKey)
                .query(this::mapRow)
                .list();
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    @Override
    public Optional<Incident> findByIdForUpdate(UUID id) {
        List<Incident> rows = jdbc.sql(SELECT_BASE + " WHERE id = :id FOR UPDATE")
                .param("id", id)
                .query(this::mapRow)
                .list();
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    @Override
    public Optional<Incident> findById(UUID id) {
        List<Incident> rows = jdbc.sql(SELECT_BASE + " WHERE id = :id")
                .param("id", id)
                .query(this::mapRow)
                .list();
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    @Override
    public void insert(Incident incident) {
        jdbc.sql("""
                INSERT INTO incident (
                    id, incident_key, status, generation,
                    episode_started_at, last_firing_starts_at, resolved_at,
                    last_investigation_hash, pending_investigation_hash,
                    received_count, distinct_event_count, notification_count,
                    current_rca_run_id, first_seen_at, last_event_at, created_at, updated_at
                ) VALUES (
                    :id, :incidentKey, :status, :generation,
                    :episodeStartedAt, :lastFiringStartsAt, :resolvedAt,
                    :lastInvestigationHash, :pendingInvestigationHash,
                    :receivedCount, :distinctEventCount, :notificationCount,
                    :currentRcaRunId, :firstSeenAt, :lastEventAt, :createdAt, :updatedAt
                )
                """)
                .param("id", incident.id())
                .param("incidentKey", incident.incidentKey())
                .param("status", incident.status().name())
                .param("generation", incident.generation())
                .param("episodeStartedAt", Timestamp.from(incident.episodeStartedAt()))
                .param("lastFiringStartsAt", ts(incident.lastFiringStartsAt()))
                .param("resolvedAt", ts(incident.resolvedAt()))
                .param("lastInvestigationHash", hash(incident.lastInvestigationHash()))
                .param("pendingInvestigationHash", hash(incident.pendingInvestigationHash()))
                .param("receivedCount", incident.receivedCount())
                .param("distinctEventCount", incident.distinctEventCount())
                .param("notificationCount", incident.notificationCount())
                .param("currentRcaRunId", incident.currentRcaRunId())
                .param("firstSeenAt", Timestamp.from(incident.firstSeenAt()))
                .param("lastEventAt", Timestamp.from(incident.lastEventAt()))
                .param("createdAt", Timestamp.from(incident.createdAt()))
                .param("updatedAt", Timestamp.from(incident.updatedAt()))
                .update();
    }

    @Override
    public boolean update(Incident incident) {
        return jdbc.sql("""
                UPDATE incident SET
                    status = :status, generation = :generation,
                    episode_started_at = :episodeStartedAt,
                    last_firing_starts_at = :lastFiringStartsAt,
                    resolved_at = :resolvedAt,
                    last_investigation_hash = :lastInvestigationHash,
                    pending_investigation_hash = :pendingInvestigationHash,
                    received_count = :receivedCount,
                    distinct_event_count = :distinctEventCount,
                    notification_count = :notificationCount,
                    current_rca_run_id = :currentRcaRunId,
                    last_event_at = :lastEventAt,
                    updated_at = :updatedAt
                 WHERE id = :id
                """)
                .param("status", incident.status().name())
                .param("generation", incident.generation())
                .param("episodeStartedAt", Timestamp.from(incident.episodeStartedAt()))
                .param("lastFiringStartsAt", ts(incident.lastFiringStartsAt()))
                .param("resolvedAt", ts(incident.resolvedAt()))
                .param("lastInvestigationHash", hash(incident.lastInvestigationHash()))
                .param("pendingInvestigationHash", hash(incident.pendingInvestigationHash()))
                .param("receivedCount", incident.receivedCount())
                .param("distinctEventCount", incident.distinctEventCount())
                .param("notificationCount", incident.notificationCount())
                .param("currentRcaRunId", incident.currentRcaRunId())
                .param("lastEventAt", Timestamp.from(incident.lastEventAt()))
                .param("updatedAt", Timestamp.from(incident.updatedAt()))
                .param("id", incident.id())
                .update() > 0;
    }

    @Override
    public int countActive() {
        return jdbc.sql("SELECT count(*) FROM incident WHERE status = 'FIRING'")
                .query(Integer.class).single();
    }

    private static Timestamp ts(Instant instant) {
        return instant == null ? null : Timestamp.from(instant);
    }

    private static String hash(Digest digest) {
        return digest == null ? null : digest.value();
    }

    private Incident mapRow(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
        Timestamp lastFiring = rs.getTimestamp("last_firing_starts_at");
        Timestamp resolvedAt = rs.getTimestamp("resolved_at");
        String lastHash = rs.getString("last_investigation_hash");
        String pendingHash = rs.getString("pending_investigation_hash");

        return new Incident(
                rs.getObject("id", UUID.class),
                rs.getString("incident_key"),
                IncidentStatus.valueOf(rs.getString("status")),
                rs.getInt("generation"),
                rs.getTimestamp("episode_started_at").toInstant(),
                lastFiring == null ? null : lastFiring.toInstant(),
                resolvedAt == null ? null : resolvedAt.toInstant(),
                lastHash == null ? null : new Digest(lastHash),
                pendingHash == null ? null : new Digest(pendingHash),
                rs.getLong("received_count"),
                rs.getLong("distinct_event_count"),
                rs.getLong("notification_count"),
                rs.getObject("current_rca_run_id", UUID.class),
                rs.getTimestamp("first_seen_at").toInstant(),
                rs.getTimestamp("last_event_at").toInstant(),
                rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("updated_at").toInstant());
    }
}
