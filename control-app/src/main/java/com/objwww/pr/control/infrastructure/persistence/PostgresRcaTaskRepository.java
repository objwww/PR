package com.objwww.pr.control.infrastructure.persistence;

import com.objwww.pr.control.alert.domain.model.RcaTask;
import com.objwww.pr.control.alert.domain.model.RcaTaskState;
import com.objwww.pr.control.alert.domain.repository.RcaTaskRepository;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * rca_task 的 Postgres 实现。claimNext = SLA 晋升排序 + SKIP LOCKED + 租约翻转
 * （§6.2 ORDER BY 原文落 SQL；CT-A02 并发互斥实证）。
 */
public class PostgresRcaTaskRepository implements RcaTaskRepository {

    /** 端口契约原文（§6.2）：(now() >= deadline_at) DESC, priority DESC, deadline_at, created_at, id */
    private static final String CLAIM_SQL = """
            UPDATE rca_task SET
                state = 'LEASED',
                lease_owner = :owner,
                lease_until = :leaseUntil,
                lease_epoch = lease_epoch + 1,
                attempt_count = attempt_count + 1,
                updated_at = :now
            WHERE id = (
                SELECT id FROM rca_task
                 WHERE state IN ('READY', 'RETRY_WAIT') AND available_at <= :now
                 ORDER BY (now() >= deadline_at) DESC, priority DESC, deadline_at, created_at, id
                 LIMIT 1 FOR UPDATE SKIP LOCKED
            )
            RETURNING *
            """;

    private final JdbcClient jdbc;

    public PostgresRcaTaskRepository(JdbcClient jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc);
    }

    @Override
    public void insert(RcaTask task) {
        jdbc.sql("""
                INSERT INTO rca_task (
                    id, run_id, task_key, state, priority,
                    available_at, ready_since, deadline_at,
                    lease_owner, lease_until, lease_epoch,
                    attempt_count, max_attempts, created_at, updated_at
                ) VALUES (
                    :id, :runId, :taskKey, :state, :priority,
                    :availableAt, :readySince, :deadlineAt,
                    :leaseOwner, :leaseUntil, :leaseEpoch,
                    :attemptCount, :maxAttempts, :createdAt, :updatedAt
                )
                """)
                .param("id", task.id())
                .param("runId", task.runId())
                .param("taskKey", task.taskKey())
                .param("state", task.state().name())
                .param("priority", task.priority())
                .param("availableAt", Timestamp.from(task.availableAt()))
                .param("readySince", Timestamp.from(task.readySince()))
                .param("deadlineAt", deadlineParam(task.deadlineAt()))
                .param("leaseOwner", task.leaseOwner())
                .param("leaseUntil", ts(task.leaseUntil()))
                .param("leaseEpoch", task.leaseEpoch())
                .param("attemptCount", task.attemptCount())
                .param("maxAttempts", task.maxAttempts())
                .param("createdAt", Timestamp.from(task.createdAt()))
                .param("updatedAt", Timestamp.from(task.updatedAt()))
                .update();
    }

    @Override
    public Optional<RcaTask> claimNext(String owner, Instant now, Duration lease) {
        List<RcaTask> rows = jdbc.sql(CLAIM_SQL)
                .param("owner", owner)
                .param("now", Timestamp.from(now))
                .param("leaseUntil", Timestamp.from(now.plus(lease)))
                .query(this::mapRow)
                .list();
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    @Override
    public boolean requireCurrentLease(UUID id, String owner, long leaseEpoch) {
        return jdbc.sql("""
                UPDATE rca_task SET updated_at = updated_at
                 WHERE id = :id AND state = 'LEASED'
                   AND lease_owner = :owner AND lease_epoch = :epoch
                """)
                .param("id", id).param("owner", owner).param("epoch", leaseEpoch)
                .update() > 0;
    }

    @Override
    public boolean update(RcaTask task) {
        return jdbc.sql("""
                UPDATE rca_task SET
                    state = :state, priority = :priority,
                    available_at = :availableAt, ready_since = :readySince, deadline_at = :deadlineAt,
                    lease_owner = :leaseOwner, lease_until = :leaseUntil, lease_epoch = :leaseEpoch,
                    attempt_count = :attemptCount, updated_at = :updatedAt
                 WHERE id = :id
                """)
                .param("state", task.state().name())
                .param("priority", task.priority())
                .param("availableAt", Timestamp.from(task.availableAt()))
                .param("readySince", Timestamp.from(task.readySince()))
                .param("deadlineAt", deadlineParam(task.deadlineAt()))
                .param("leaseOwner", task.leaseOwner())
                .param("leaseUntil", ts(task.leaseUntil()))
                .param("leaseEpoch", task.leaseEpoch())
                .param("attemptCount", task.attemptCount())
                .param("updatedAt", Timestamp.from(task.updatedAt()))
                .param("id", task.id())
                .update() > 0;
    }

    @Override
    public void heartbeat(UUID id, String owner, long leaseEpoch, Instant now, Duration extend) {
        jdbc.sql("""
                UPDATE rca_task SET lease_until = :leaseUntil, updated_at = :now
                 WHERE id = :id AND state = 'LEASED'
                   AND lease_owner = :owner AND lease_epoch = :epoch
                """)
                .param("leaseUntil", Timestamp.from(now.plus(extend)))
                .param("now", Timestamp.from(now))
                .param("id", id).param("owner", owner).param("epoch", leaseEpoch)
                .update();
    }

    @Override
    public List<RcaTask> findExpiredLeased(Instant now) {
        return jdbc.sql("SELECT * FROM rca_task WHERE state = 'LEASED' AND lease_until < :now")
                .param("now", Timestamp.from(now))
                .query(this::mapRow)
                .list();
    }

    @Override
    public Optional<RcaTask> findById(UUID id) {
        List<RcaTask> rows = jdbc.sql("SELECT * FROM rca_task WHERE id = :id")
                .param("id", id)
                .query(this::mapRow)
                .list();
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    @Override
    public int countQueued() {
        return jdbc.sql("SELECT count(*) FROM rca_task WHERE state IN ('READY', 'RETRY_WAIT')")
                .query(Integer.class).single();
    }

    private static Timestamp ts(Instant instant) {
        return instant == null ? null : Timestamp.from(instant);
    }

    /**
     * deadline 绑定：Instant.MAX（critical 永不到期，§6.2）必须走 pgjdbc 的 infinity 约定
     * （Timestamp millis==Long.MAX_VALUE ⇔ timestamptz 'infinity'）。直接
     * Timestamp.from(Instant.MAX) 会静默溢出环绕成负毫秒（实测 -5.3e18，落库为史前时刻），
     * "永不到期"退化为"永远已过期"——BA-05。
     */
    private static Object deadlineParam(Instant deadline) {
        return Instant.MAX.equals(deadline) ? new Timestamp(Long.MAX_VALUE) : Timestamp.from(deadline);
    }

    /** deadline 读回：infinity（millis==Long.MAX_VALUE）→ Instant.MAX 还原域语义 */
    private static Instant deadline(Timestamp ts) {
        return ts.getTime() == Long.MAX_VALUE ? Instant.MAX : ts.toInstant();
    }

    private RcaTask mapRow(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
        Timestamp leaseUntil = rs.getTimestamp("lease_until");
        return new RcaTask(
                rs.getObject("id", UUID.class),
                rs.getObject("run_id", UUID.class),
                rs.getString("task_key"),
                RcaTaskState.valueOf(rs.getString("state")),
                rs.getInt("priority"),
                rs.getTimestamp("available_at").toInstant(),
                rs.getTimestamp("ready_since").toInstant(),
                deadline(rs.getTimestamp("deadline_at")),
                rs.getString("lease_owner"),
                leaseUntil == null ? null : leaseUntil.toInstant(),
                rs.getLong("lease_epoch"),
                rs.getInt("attempt_count"),
                rs.getInt("max_attempts"),
                rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("updated_at").toInstant());
    }
}
