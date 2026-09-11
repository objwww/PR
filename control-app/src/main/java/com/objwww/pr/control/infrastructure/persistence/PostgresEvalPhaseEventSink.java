package com.objwww.pr.control.infrastructure.persistence;

import com.objwww.pr.control.eval.domain.repository.EvalPhaseEventSink;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * {@link EvalPhaseEventSink} 的 Postgres 实现（EV-04；V80 建表，eval_app 授权面 =
 * select,insert——类内不存在任何 UPDATE/DELETE 语句，insert-only 由代码与授权双保险）。
 */
public class PostgresEvalPhaseEventSink implements EvalPhaseEventSink {

    private final JdbcClient jdbc;

    public PostgresEvalPhaseEventSink(JdbcClient jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc);
    }

    @Override
    public void record(UUID evalRunId, String phase, Instant enteredAt,
                       String workerId, String detailJson) {
        jdbc.sql("""
                INSERT INTO eval_phase_event(id, eval_run_id, phase, entered_at,
                                             worker_id, detail)
                VALUES (:id, :runId, :phase, :at, :worker, CAST(:detail AS jsonb))
                """)
                .param("id", UUID.randomUUID())
                .param("runId", evalRunId)
                .param("phase", phase)
                .param("at", Timestamp.from(enteredAt))
                .param("worker", workerId)
                .param("detail", detailJson)
                .update();
    }
}
