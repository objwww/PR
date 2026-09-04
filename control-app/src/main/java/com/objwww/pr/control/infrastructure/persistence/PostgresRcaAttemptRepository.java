package com.objwww.pr.control.infrastructure.persistence;

import com.objwww.pr.control.alert.domain.model.RcaAttempt;
import com.objwww.pr.control.alert.domain.model.RcaAttemptStatus;
import com.objwww.pr.control.alert.domain.repository.RcaAttemptRepository;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.sql.Timestamp;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * rca_attempt 的 Postgres 实现（V1 step_attempt 同构）。
 */
public class PostgresRcaAttemptRepository implements RcaAttemptRepository {

    private final JdbcClient jdbc;

    public PostgresRcaAttemptRepository(JdbcClient jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc);
    }

    @Override
    public void insert(RcaAttempt attempt) {
        jdbc.sql("""
                INSERT INTO rca_attempt (
                    id, task_id, attempt_no, lease_epoch, worker_id,
                    status, error_class, error_code, error_detail, started_at
                ) VALUES (
                    :id, :taskId, :attemptNo, :leaseEpoch, :workerId,
                    :status, :errorClass, :errorCode, CAST(:errorDetail AS jsonb), :startedAt
                )
                """)
                .param("id", attempt.id())
                .param("taskId", attempt.taskId())
                .param("attemptNo", attempt.attemptNo())
                .param("leaseEpoch", attempt.leaseEpoch())
                .param("workerId", attempt.workerId())
                .param("status", attempt.status().name())
                .param("errorClass", attempt.errorClass())
                .param("errorCode", attempt.errorCode())
                .param("errorDetail", JsonbText.encode(attempt.errorDetail()))
                .param("startedAt", Timestamp.from(attempt.startedAt()))
                .update();
    }

    @Override
    public boolean update(RcaAttempt attempt) {
        return jdbc.sql("""
                UPDATE rca_attempt SET
                    status = :status, error_class = :errorClass,
                    error_code = :errorCode, error_detail = CAST(:errorDetail AS jsonb),
                    finished_at = :finishedAt
                 WHERE id = :id
                """)
                .param("status", attempt.status().name())
                .param("errorClass", attempt.errorClass())
                .param("errorCode", attempt.errorCode())
                .param("errorDetail", JsonbText.encode(attempt.errorDetail()))
                .param("finishedAt", ts(attempt.finishedAt()))
                .param("id", attempt.id())
                .update() > 0;
    }

    @Override
    public List<RcaAttempt> findByTaskId(UUID taskId) {
        return jdbc.sql("SELECT * FROM rca_attempt WHERE task_id = :taskId ORDER BY started_at")
                .param("taskId", taskId)
                .query(this::mapRow)
                .list();
    }

    private static Timestamp ts(java.time.Instant instant) {
        return instant == null ? null : Timestamp.from(instant);
    }

    private RcaAttempt mapRow(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
        Timestamp finishedAt = rs.getTimestamp("finished_at");
        return new RcaAttempt(
                rs.getObject("id", UUID.class),
                rs.getObject("task_id", UUID.class),
                rs.getInt("attempt_no"),
                rs.getLong("lease_epoch"),
                rs.getString("worker_id"),
                RcaAttemptStatus.valueOf(rs.getString("status")),
                rs.getString("error_class"),
                rs.getString("error_code"),
                JsonbText.decode(rs.getString("error_detail")),
                rs.getTimestamp("started_at").toInstant(),
                finishedAt == null ? null : finishedAt.toInstant());
    }
}
