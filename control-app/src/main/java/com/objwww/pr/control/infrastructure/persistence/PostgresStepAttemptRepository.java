package com.objwww.pr.control.infrastructure.persistence;

import com.objwww.pr.control.domain.model.StepAttempt;
import com.objwww.pr.control.domain.repository.StepAttemptRepository;
import com.objwww.pr.shared.AttemptStatus;
import com.objwww.pr.shared.Digest;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.sql.Timestamp;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * StepAttemptRepository 的 Postgres 实现。
 * save = INSERT / ON CONFLICT (id) UPDATE 终态字段；(step_id, attempt_no) 唯一约束兜底重试计数。
 *
 * <p>刻意不加 Spring 注解：接线见 infrastructure/config/PersistenceConfig（docker profile）。
 */
public class PostgresStepAttemptRepository implements StepAttemptRepository {

    private static final String UPSERT_SQL = """
            INSERT INTO step_attempt (
                id, step_id, work_item_id, attempt_no, lease_epoch, worker_id,
                status, actual_model_provider, actual_model,
                input_artifact_digest, output_artifact_digest,
                error_class, error_code, error_detail,
                started_at, finished_at
            ) VALUES (
                :id, :stepId, :workItemId, :attemptNo, :leaseEpoch, :workerId,
                :status, :actualModelProvider, :actualModel,
                :inputArtifactDigest, :outputArtifactDigest,
                :errorClass, :errorCode, to_jsonb(CAST(:errorDetail AS text)),
                :startedAt, :finishedAt
            )
            ON CONFLICT (id) DO UPDATE SET
                status                 = EXCLUDED.status,
                output_artifact_digest = EXCLUDED.output_artifact_digest,
                error_class            = EXCLUDED.error_class,
                error_code             = EXCLUDED.error_code,
                error_detail           = EXCLUDED.error_detail,
                finished_at            = EXCLUDED.finished_at
            """;

    private static final String SELECT_COLUMNS = """
            id, step_id, work_item_id, attempt_no, lease_epoch, worker_id,
            status, actual_model_provider, actual_model,
            input_artifact_digest, output_artifact_digest,
            error_class, error_code, error_detail,
            started_at, finished_at
            """;

    private final JdbcClient jdbc;

    public PostgresStepAttemptRepository(JdbcClient jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc);
    }

    @Override
    public void save(StepAttempt attempt) {
        Objects.requireNonNull(attempt, "attempt");
        jdbc.sql(UPSERT_SQL)
                .param("id", attempt.getId())
                .param("stepId", attempt.getStepId())
                .param("workItemId", attempt.getWorkItemId())
                .param("attemptNo", attempt.getAttemptNo())
                .param("leaseEpoch", attempt.getLeaseEpoch())
                .param("workerId", attempt.getWorkerId())
                .param("status", attempt.getStatus().name())
                .param("actualModelProvider", attempt.getActualModelProvider())
                .param("actualModel", attempt.getActualModel())
                .param("inputArtifactDigest",
                        attempt.getInputArtifactDigest() == null ? null : attempt.getInputArtifactDigest().value())
                .param("outputArtifactDigest",
                        attempt.getOutputArtifactDigest() == null ? null : attempt.getOutputArtifactDigest().value())
                .param("errorClass", attempt.getErrorClass())
                .param("errorCode", attempt.getErrorCode())
                .param("errorDetail", attempt.getErrorDetail())
                .param("startedAt", Timestamp.from(attempt.getStartedAt()))
                .param("finishedAt", attempt.getFinishedAt() == null ? null : Timestamp.from(attempt.getFinishedAt()))
                .update();
    }

    @Override
    public Optional<StepAttempt> findById(UUID id) {
        return jdbc.sql("SELECT " + SELECT_COLUMNS + " FROM step_attempt WHERE id = :id")
                .param("id", Objects.requireNonNull(id))
                .query(this::map)
                .optional();
    }

    @Override
    public List<StepAttempt> findByStepId(UUID stepId) {
        return jdbc.sql("SELECT " + SELECT_COLUMNS + """
                        FROM step_attempt WHERE step_id = :stepId ORDER BY attempt_no
                        """)
                .param("stepId", Objects.requireNonNull(stepId))
                .query(this::map)
                .list();
    }

    private StepAttempt map(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
        String inputDigest = rs.getString("input_artifact_digest");
        String outputDigest = rs.getString("output_artifact_digest");
        Timestamp finishedAt = rs.getTimestamp("finished_at");
        return new StepAttempt(
                rs.getObject("id", UUID.class),
                rs.getObject("step_id", UUID.class),
                rs.getObject("work_item_id", UUID.class),
                rs.getInt("attempt_no"),
                rs.getLong("lease_epoch"),
                rs.getString("worker_id"),
                AttemptStatus.valueOf(rs.getString("status")),
                rs.getString("actual_model_provider"),
                rs.getString("actual_model"),
                inputDigest == null ? null : new Digest(inputDigest),
                outputDigest == null ? null : new Digest(outputDigest),
                rs.getString("error_class"),
                rs.getString("error_code"),
                rs.getString("error_detail"),
                rs.getTimestamp("started_at").toInstant(),
                finishedAt == null ? null : finishedAt.toInstant());
    }
}
