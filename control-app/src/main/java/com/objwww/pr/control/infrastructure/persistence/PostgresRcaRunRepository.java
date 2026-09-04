package com.objwww.pr.control.infrastructure.persistence;

import com.objwww.pr.control.alert.domain.model.RcaRun;
import com.objwww.pr.control.alert.domain.model.RcaRunState;
import com.objwww.pr.control.alert.domain.model.RunTrigger;
import com.objwww.pr.control.alert.domain.repository.RcaRunRepository;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.sql.Timestamp;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * rca_run 的 Postgres 实现。insert 撞 uq_rca_run_active_incident 抛 DuplicateKeyException
 * （INV-AM1-2，CT-A03 并发双铸 23505 实证）。
 */
public class PostgresRcaRunRepository implements RcaRunRepository {

    private final JdbcClient jdbc;

    public PostgresRcaRunRepository(JdbcClient jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc);
    }

    @Override
    public void insert(RcaRun run) {
        jdbc.sql("""
                INSERT INTO rca_run (
                    id, incident_id, generation, trigger_kind, state, investigation_hash,
                    created_at, updated_at, started_at, finished_at, last_error
                ) VALUES (
                    :id, :incidentId, :generation, :trigger, :state, :investigationHash,
                    :createdAt, :updatedAt, :startedAt, :finishedAt, CAST(:lastError AS jsonb)
                )
                """)
                .param("id", run.id())
                .param("incidentId", run.incidentId())
                .param("generation", run.generation())
                .param("trigger", run.trigger().name())
                .param("state", run.state().name())
                .param("investigationHash", run.investigationHash().value())
                .param("createdAt", Timestamp.from(run.createdAt()))
                .param("updatedAt", Timestamp.from(run.updatedAt()))
                .param("startedAt", ts(run.startedAt()))
                .param("finishedAt", ts(run.finishedAt()))
                .param("lastError", JsonbText.encode(run.lastError()))
                .update();
    }

    @Override
    public Optional<RcaRun> findByIdForUpdate(UUID id) {
        List<RcaRun> rows = jdbc.sql("SELECT * FROM rca_run WHERE id = :id FOR UPDATE")
                .param("id", id)
                .query(this::mapRow)
                .list();
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    @Override
    public boolean update(RcaRun run) {
        return jdbc.sql("""
                UPDATE rca_run SET
                    state = :state, started_at = :startedAt, finished_at = :finishedAt,
                    last_error = CAST(:lastError AS jsonb), updated_at = :updatedAt
                 WHERE id = :id
                """)
                .param("state", run.state().name())
                .param("startedAt", ts(run.startedAt()))
                .param("finishedAt", ts(run.finishedAt()))
                .param("lastError", JsonbText.encode(run.lastError()))
                .param("updatedAt", Timestamp.from(run.updatedAt()))
                .param("id", run.id())
                .update() > 0;
    }

    @Override
    public Optional<RcaRun> findActiveByIncidentId(UUID incidentId) {
        List<RcaRun> rows = jdbc.sql("""
                        SELECT * FROM rca_run
                         WHERE incident_id = :incidentId AND state IN ('QUEUED', 'RUNNING')
                         ORDER BY created_at DESC LIMIT 1
                        """)
                .param("incidentId", incidentId)
                .query(this::mapRow)
                .list();
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    private static Timestamp ts(java.time.Instant instant) {
        return instant == null ? null : Timestamp.from(instant);
    }

    private RcaRun mapRow(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
        Timestamp startedAt = rs.getTimestamp("started_at");
        Timestamp finishedAt = rs.getTimestamp("finished_at");
        return new RcaRun(
                rs.getObject("id", UUID.class),
                rs.getObject("incident_id", UUID.class),
                rs.getInt("generation"),
                RunTrigger.valueOf(rs.getString("trigger_kind")),
                RcaRunState.valueOf(rs.getString("state")),
                new com.objwww.pr.shared.Digest(rs.getString("investigation_hash")),
                rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("updated_at").toInstant(),
                startedAt == null ? null : startedAt.toInstant(),
                finishedAt == null ? null : finishedAt.toInstant(),
                JsonbText.decode(rs.getString("last_error")));
    }
}
