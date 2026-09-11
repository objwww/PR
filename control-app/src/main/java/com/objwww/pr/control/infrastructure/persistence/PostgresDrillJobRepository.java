package com.objwww.pr.control.infrastructure.persistence;

import com.objwww.pr.control.drill.domain.model.DrillJob;
import com.objwww.pr.control.drill.domain.repository.DrillJobRepository;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * {@link DrillJobRepository} 的 Postgres 实现（DR-02，V86；JdbcClient 手写 SQL，
 * 沿 PostgresEvalRunCommandRepository 惯例）。
 *
 * <p>同一实现服务两个 DB 身份（授权面 V86 在库侧收口）：
 * <ul>
 *   <li>control_app（/api/drills 面）：insert / find 系 / list / count 系 /
 *       requestStop——requestStop 只写 stop 两列（不碰 updated_at，列级授权内）；</li>
 *   <li>eval_app（worker）：claimNext/advance/finalize/requeue/findOrphanedClaims——
 *       列级 update 授权内，正文列零开口。</li>
 * </ul>
 * claim/推进全部单语句 CAS（FOR UPDATE SKIP LOCKED + state/revision 双对账）。
 */
public class PostgresDrillJobRepository implements DrillJobRepository {

    private static final String COLS =
            "id, scenario_id, scenario_name, template_digest, target_env, operator,"
                    + " state, outcome, terminal_reason, params::text as params,"
                    + " payload_hash, idempotency_key, stop_idempotency_key,"
                    + " stop_requested_at, worker_id, claimed_at, revision,"
                    + " related_incident_id, related_run_id, created_at, updated_at,"
                    + " closed_at";

    /** 活动占位集（与 V86 部分唯一索引一致；RECOVERY_FAILED 保留占位，DU15） */
    private static final String ACTIVE_STATES =
            "('QUEUED','PRECHECK','INJECTING','OBSERVING','RECOVERING','VERIFYING',"
                    + "'RECOVERY_FAILED')";

    private static final String INSERT_SQL = """
            INSERT INTO drill_job (
                id, scenario_id, scenario_name, template_digest, target_env, operator,
                state, params, payload_hash, idempotency_key, created_at, updated_at
            ) VALUES (
                :id, :scenarioId, :scenarioName, :templateDigest, :targetEnv, :operator,
                'QUEUED', CAST(:params AS jsonb), :payloadHash, :idempotencyKey,
                :createdAt, :updatedAt
            )
            """;

    /** 领取 = 单语句 CAS：锁定并标记最老 QUEUED；SKIP LOCKED 防多 worker 撞同一行。
     *  RETURNING 后的空格不能写在 text block 行尾（编译期剥离，BA-123：拼成 RETURNINGid），
     *  由块外 " " 显式补齐。 */
    private static final String CLAIM_SQL = """
            UPDATE drill_job SET worker_id = :worker, claimed_at = :at,
                revision = revision + 1, updated_at = :at
            WHERE id = (
                SELECT id FROM drill_job
                WHERE state = 'QUEUED'
                ORDER BY created_at, id LIMIT 1
                FOR UPDATE SKIP LOCKED
            )
            RETURNING""" + " " + COLS;

    private final JdbcClient jdbc;

    public PostgresDrillJobRepository(JdbcClient jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc);
    }

    @Override
    public void insert(DrillJob job) {
        jdbc.sql(INSERT_SQL)
                .param("id", job.id())
                .param("scenarioId", job.scenarioId())
                .param("scenarioName", job.scenarioName())
                .param("templateDigest", job.templateDigest())
                .param("targetEnv", job.targetEnv())
                .param("operator", job.operator())
                .param("params", job.paramsJson())
                .param("payloadHash", job.payloadHash())
                .param("idempotencyKey", job.idempotencyKey())
                .param("createdAt", Timestamp.from(job.createdAt()))
                .param("updatedAt", Timestamp.from(job.updatedAt()))
                .update();
    }

    @Override
    public Optional<DrillJob> findById(UUID id) {
        return jdbc.sql("SELECT " + COLS + " FROM drill_job WHERE id = :id")
                .param("id", id).query(this::map).optional();
    }

    @Override
    public Optional<DrillJob> findByIdempotencyKey(String idempotencyKey) {
        return jdbc.sql("SELECT " + COLS + " FROM drill_job WHERE idempotency_key = :key")
                .param("key", idempotencyKey).query(this::map).optional();
    }

    @Override
    public Optional<DrillJob> findByStopKey(String stopIdempotencyKey) {
        return jdbc.sql("SELECT " + COLS + " FROM drill_job"
                        + " WHERE stop_idempotency_key = :key")
                .param("key", stopIdempotencyKey).query(this::map).optional();
    }

    @Override
    public Optional<DrillJob> findActiveOccupant(String targetEnv, UUID excludeId) {
        String sql = "SELECT " + COLS + " FROM drill_job WHERE target_env = :env"
                + " AND state IN " + ACTIVE_STATES
                + (excludeId == null ? "" : " AND id <> :exclude")
                + " ORDER BY created_at, id LIMIT 1";
        JdbcClient.StatementSpec spec = jdbc.sql(sql).param("env", targetEnv);
        if (excludeId != null) {
            spec = spec.param("exclude", excludeId);
        }
        return spec.query(this::map).optional();
    }

    @Override
    public List<DrillJob> list(String state, String cursor, int limit) {
        StringBuilder sql = new StringBuilder("SELECT " + COLS + " FROM drill_job");
        List<String> where = new java.util.ArrayList<>();
        if (state != null) {
            where.add("state = :state");
        }
        Instant cursorAt = null;
        UUID cursorId = null;
        if (cursor != null && !cursor.isBlank()) {
            String[] parts = cursor.split("\\|");
            if (parts.length != 2) {
                throw new IllegalArgumentException("cursor 非法: " + cursor);
            }
            try {
                long micros = Long.parseLong(parts[0]);
                cursorAt = Instant.ofEpochSecond(micros / 1_000_000,
                        (micros % 1_000_000) * 1000);
                cursorId = UUID.fromString(parts[1]);
            } catch (RuntimeException e) {
                throw new IllegalArgumentException("cursor 非法: " + cursor);
            }
            where.add("(created_at, id) < (:cursorAt, :cursorId)");
        }
        if (!where.isEmpty()) {
            sql.append(" WHERE ").append(String.join(" AND ", where));
        }
        sql.append(" ORDER BY created_at DESC, id DESC LIMIT :limit");
        JdbcClient.StatementSpec spec = jdbc.sql(sql.toString());
        if (state != null) {
            spec = spec.param("state", state);
        }
        if (cursorAt != null) {
            spec = spec.param("cursorAt", Timestamp.from(cursorAt))
                    .param("cursorId", cursorId);
        }
        return spec.param("limit", limit).query(this::map).list();
    }

    @Override
    public long countActive() {
        return jdbc.sql("SELECT count(*) FROM drill_job WHERE state IN "
                        + "('QUEUED','PRECHECK','INJECTING','OBSERVING','RECOVERING',"
                        + "'VERIFYING')")
                .query(Long.class).single();
    }

    @Override
    public long countRecoveryFailed() {
        return jdbc.sql("SELECT count(*) FROM drill_job WHERE state = 'RECOVERY_FAILED'")
                .query(Long.class).single();
    }

    /**
     * 停止受理 CAS（control_app 面）：只写 stop 两列——列级授权边界，不碰 updated_at；
     * 已受理/已终态/恢复异常占位自然落空（重复停止幂等的库侧兜底，DU14）。
     */
    @Override
    public boolean requestStop(UUID id, String stopIdempotencyKey,
                               Instant stopRequestedAt) {
        return jdbc.sql("""
                        UPDATE drill_job SET stop_idempotency_key = :key,
                            stop_requested_at = :at
                        WHERE id = :id AND stop_requested_at IS NULL
                          AND state IN ('QUEUED','PRECHECK','INJECTING','OBSERVING',
                                        'RECOVERING','VERIFYING')
                        """)
                .param("key", stopIdempotencyKey)
                .param("at", Timestamp.from(stopRequestedAt))
                .param("id", id)
                .update() > 0;
    }

    @Override
    public Optional<DrillJob> claimNext(String workerId, Instant claimedAt) {
        return jdbc.sql(CLAIM_SQL)
                .param("worker", workerId)
                .param("at", Timestamp.from(claimedAt))
                .query(this::map).optional();
    }

    @Override
    public boolean advance(UUID id, long expectedRevision, DrillJob.State from,
                           DrillJob.State to, Instant updatedAt) {
        return jdbc.sql("""
                        UPDATE drill_job SET state = :to, revision = revision + 1,
                            updated_at = :at
                        WHERE id = :id AND state = :from AND revision = :rev
                        """)
                .param("to", to.name())
                .param("at", Timestamp.from(updatedAt))
                .param("id", id)
                .param("from", from.name())
                .param("rev", expectedRevision)
                .update() > 0;
    }

    @Override
    public boolean finalize(UUID id, long expectedRevision, DrillJob.State from,
                            DrillJob.State to, String terminalReason, String outcome,
                            Instant closedAt, Instant updatedAt) {
        return jdbc.sql("""
                        UPDATE drill_job SET state = :to, terminal_reason = :reason,
                            outcome = :outcome, closed_at = :closedAt,
                            revision = revision + 1, updated_at = :at
                        WHERE id = :id AND state = :from AND revision = :rev
                        """)
                .param("to", to.name())
                .param("reason", terminalReason)
                .param("outcome", outcome)
                .param("closedAt", closedAt == null ? null : Timestamp.from(closedAt))
                .param("at", Timestamp.from(updatedAt))
                .param("id", id)
                .param("from", from.name())
                .param("rev", expectedRevision)
                .update() > 0;
    }

    @Override
    public List<DrillJob> findOrphanedClaims(Instant claimedBefore) {
        return jdbc.sql("SELECT " + COLS + " FROM drill_job"
                        + " WHERE worker_id IS NOT NULL AND claimed_at < :before"
                        + " AND state IN ('QUEUED','PRECHECK','INJECTING','OBSERVING',"
                        + "'RECOVERING','VERIFYING') ORDER BY claimed_at, id")
                .param("before", Timestamp.from(claimedBefore))
                .query(this::map).list();
    }

    @Override
    public boolean requeue(UUID id, long expectedRevision, Instant updatedAt) {
        return jdbc.sql("""
                        UPDATE drill_job SET state = 'QUEUED', worker_id = NULL,
                            claimed_at = NULL, revision = revision + 1, updated_at = :at
                        WHERE id = :id AND revision = :rev
                          AND state IN ('QUEUED', 'PRECHECK')
                        """)
                .param("at", Timestamp.from(updatedAt))
                .param("id", id)
                .param("rev", expectedRevision)
                .update() > 0;
    }

    private DrillJob map(ResultSet rs, int rowNum) throws SQLException {
        return new DrillJob(
                rs.getObject("id", UUID.class),
                rs.getString("scenario_id"),
                rs.getString("scenario_name"),
                rs.getString("template_digest"),
                rs.getString("target_env"),
                rs.getString("operator"),
                DrillJob.State.valueOf(rs.getString("state")),
                rs.getString("outcome"),
                rs.getString("terminal_reason"),
                rs.getString("params"),
                rs.getString("payload_hash"),
                rs.getString("idempotency_key"),
                rs.getString("stop_idempotency_key"),
                ts(rs, "stop_requested_at"),
                rs.getString("worker_id"),
                ts(rs, "claimed_at"),
                rs.getLong("revision"),
                rs.getObject("related_incident_id", UUID.class),
                rs.getObject("related_run_id", UUID.class),
                rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("updated_at").toInstant(),
                ts(rs, "closed_at"));
    }

    private static Instant ts(ResultSet rs, String column) throws SQLException {
        Timestamp t = rs.getTimestamp(column);
        return t == null ? null : t.toInstant();
    }
}
