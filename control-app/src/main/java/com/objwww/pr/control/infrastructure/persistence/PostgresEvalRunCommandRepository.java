package com.objwww.pr.control.infrastructure.persistence;

import com.objwww.pr.control.eval.domain.model.EvalRunCommand;
import com.objwww.pr.control.eval.domain.repository.EvalRunCommandRepository;
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
 * {@link EvalRunCommandRepository} 的 Postgres 实现（EV-04，V81；JdbcClient 手写 SQL，
 * 沿 PostgresOperatorCommandRepository 惯例）。
 *
 * <p>同一实现服务两个 DB 身份（授权面在库侧收口，V81）：
 * <ul>
 *   <li>control_app（/api/eval 写面）：只用 insert/findByKey/cancelAccepted/
 *       cancelRequestedAt——select,insert 授权内；</li>
 *   <li>eval_app（worker）：claim/finish/requeue/findOrphanedClaims——列级 update
 *       （state/worker_id/claimed_at/finished_at）授权内，正文列零开口。</li>
 * </ul>
 * claim 单语句 CAS（FOR UPDATE SKIP LOCKED）：多 worker 并发恰一人领到同一命令。
 */
public class PostgresEvalRunCommandRepository implements EvalRunCommandRepository {

    private static final String INSERT_SQL = """
            INSERT INTO eval_run_command (
                id, command_type, eval_run_id, idempotency_key, payload, payload_hash,
                state, actor, created_at
            ) VALUES (
                :id, :type, :runId, :key, CAST(:payload AS jsonb), :hash,
                'PENDING', :actor, :createdAt
            )
            """;

    /** 领取 = 单语句 CAS：锁定并推进最老 PENDING LAUNCH；SKIP LOCKED 防多 worker 撞同一行 */
    private static final String CLAIM_SQL = """
            UPDATE eval_run_command SET state = 'CLAIMED', worker_id = :worker,
                claimed_at = :at
            WHERE id = (
                SELECT id FROM eval_run_command
                WHERE state = 'PENDING' AND command_type = 'LAUNCH'
                ORDER BY created_at, id LIMIT 1
                FOR UPDATE SKIP LOCKED
            )
            RETURNING *
            """;

    private static final String COLS =
            "id, command_type, eval_run_id, idempotency_key, payload::text as payload,"
                    + " payload_hash, state, actor, worker_id, created_at, claimed_at,"
                    + " finished_at";

    private final JdbcClient jdbc;

    public PostgresEvalRunCommandRepository(JdbcClient jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc);
    }

    @Override
    public void insert(EvalRunCommand command) {
        jdbc.sql(INSERT_SQL)
                .param("id", command.id())
                .param("type", command.commandType().name())
                .param("runId", command.evalRunId())
                .param("key", command.idempotencyKey())
                .param("payload", command.payloadJson())
                .param("hash", command.payloadHash())
                .param("actor", command.actor())
                .param("createdAt", Timestamp.from(command.createdAt()))
                .update();
    }

    @Override
    public Optional<EvalRunCommand> findByKey(EvalRunCommand.Type type, String idempotencyKey) {
        return jdbc.sql("SELECT " + COLS + " FROM eval_run_command"
                        + " WHERE command_type = :type AND idempotency_key = :key")
                .param("type", type.name())
                .param("key", idempotencyKey)
                .query(this::map).optional();
    }

    @Override
    public boolean cancelAccepted(UUID evalRunId) {
        return Boolean.TRUE.equals(jdbc.sql("""
                        SELECT EXISTS(
                            SELECT 1 FROM eval_run_command
                            WHERE eval_run_id = :runId AND command_type = 'CANCEL'
                              AND state IN ('PENDING', 'CLAIMED', 'DONE'))
                        """)
                .param("runId", evalRunId)
                .query(Boolean.class).single());
    }

    @Override
    public Optional<Instant> cancelRequestedAt(UUID evalRunId) {
        return jdbc.sql("""
                        SELECT min(created_at) FROM eval_run_command
                        WHERE eval_run_id = :runId AND command_type = 'CANCEL'
                          AND state IN ('PENDING', 'CLAIMED', 'DONE')
                        """)
                .param("runId", evalRunId)
                .query((rs, i) -> {
                    Timestamp t = rs.getTimestamp(1);
                    return t == null ? null : t.toInstant();
                }).optional();
    }

    @Override
    public Optional<EvalRunCommand> claimNextLaunch(String workerId, Instant claimedAt) {
        return jdbc.sql(CLAIM_SQL)
                .param("worker", workerId)
                .param("at", Timestamp.from(claimedAt))
                .query(this::map).optional();
    }

    @Override
    public boolean finish(UUID id, EvalRunCommand.State terminal, Instant finishedAt) {
        if (terminal != EvalRunCommand.State.DONE && terminal != EvalRunCommand.State.FAILED
                && terminal != EvalRunCommand.State.REJECTED) {
            throw new IllegalArgumentException("finish 只接受 DONE/FAILED/REJECTED: " + terminal);
        }
        return jdbc.sql("""
                        UPDATE eval_run_command SET state = :state, finished_at = :at
                        WHERE id = :id AND state IN ('PENDING', 'CLAIMED')
                        """)
                .param("state", terminal.name())
                .param("at", Timestamp.from(finishedAt))
                .param("id", id)
                .update() > 0;
    }

    @Override
    public List<EvalRunCommand> findOrphanedClaims(Instant before) {
        return jdbc.sql("SELECT " + COLS + " FROM eval_run_command"
                        + " WHERE state = 'CLAIMED' AND claimed_at < :before"
                        + " ORDER BY claimed_at, id")
                .param("before", Timestamp.from(before))
                .query(this::map).list();
    }

    @Override
    public boolean requeue(UUID id) {
        return jdbc.sql("""
                        UPDATE eval_run_command SET state = 'PENDING',
                            worker_id = NULL, claimed_at = NULL
                        WHERE id = :id AND state = 'CLAIMED'
                        """)
                .param("id", id)
                .update() > 0;
    }

    private EvalRunCommand map(ResultSet rs, int rowNum) throws SQLException {
        return new EvalRunCommand(
                rs.getObject("id", UUID.class),
                EvalRunCommand.Type.valueOf(rs.getString("command_type")),
                rs.getObject("eval_run_id", UUID.class),
                rs.getString("idempotency_key"),
                rs.getString("payload"),
                rs.getString("payload_hash"),
                EvalRunCommand.State.valueOf(rs.getString("state")),
                rs.getString("actor"),
                rs.getString("worker_id"),
                rs.getTimestamp("created_at").toInstant(),
                ts(rs, "claimed_at"),
                ts(rs, "finished_at"));
    }

    private static Instant ts(ResultSet rs, String column) throws SQLException {
        Timestamp t = rs.getTimestamp(column);
        return t == null ? null : t.toInstant();
    }
}
