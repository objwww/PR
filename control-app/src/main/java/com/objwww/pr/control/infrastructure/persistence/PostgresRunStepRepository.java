package com.objwww.pr.control.infrastructure.persistence;

import com.objwww.pr.control.domain.model.RunStep;
import com.objwww.pr.control.domain.repository.RunStepRepository;
import com.objwww.pr.shared.Digest;
import com.objwww.pr.shared.OperationId;
import com.objwww.pr.shared.StepState;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.sql.Timestamp;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * RunStepRepository 的 Postgres 实现。
 * save = INSERT / ON CONFLICT (id) UPDATE 状态字段；step_key/operation_id 唯一冲突上抛（逻辑幂等键）。
 *
 * <p>刻意不加 Spring 注解：接线见 infrastructure/config/PersistenceConfig（docker profile）。
 */
public class PostgresRunStepRepository implements RunStepRepository {

    private static final String UPSERT_SQL = """
            INSERT INTO run_step (
                id, review_run_id, parent_step_id, step_key, operation_id, execution_scope,
                step_type, state, ordinal,
                input_artifact_digest, output_artifact_digest,
                max_attempts, timeout_seconds, version,
                created_at, updated_at, completed_at
            ) VALUES (
                :id, :reviewRunId, :parentStepId, :stepKey, :operationId, :executionScope,
                :stepType, :state, :ordinal,
                :inputArtifactDigest, :outputArtifactDigest,
                :maxAttempts, :timeoutSeconds, :version,
                :createdAt, :updatedAt, :completedAt
            )
            ON CONFLICT (id) DO UPDATE SET
                state                  = EXCLUDED.state,
                output_artifact_digest = EXCLUDED.output_artifact_digest,
                version                = run_step.version + 1,
                updated_at             = EXCLUDED.updated_at,
                completed_at           = EXCLUDED.completed_at
            """;

    private static final String SELECT_COLUMNS = """
            id, review_run_id, parent_step_id, step_key, operation_id, execution_scope,
            step_type, state, ordinal,
            input_artifact_digest, output_artifact_digest,
            max_attempts, timeout_seconds, version,
            created_at, updated_at, completed_at
            """;

    private final JdbcClient jdbc;

    public PostgresRunStepRepository(JdbcClient jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc);
    }

    @Override
    public void save(RunStep step) {
        Objects.requireNonNull(step, "step");
        jdbc.sql(UPSERT_SQL)
                .param("id", step.getId())
                .param("reviewRunId", step.getReviewRunId())
                .param("parentStepId", step.getParentStepId())
                .param("stepKey", step.getStepKey())
                .param("operationId", step.getOperationId().value())
                .param("executionScope", step.getExecutionScope())
                .param("stepType", step.getStepType())
                .param("state", step.getState().name())
                .param("ordinal", step.getOrdinal())
                .param("inputArtifactDigest",
                        step.getInputArtifactDigest() == null ? null : step.getInputArtifactDigest().value())
                .param("outputArtifactDigest",
                        step.getOutputArtifactDigest() == null ? null : step.getOutputArtifactDigest().value())
                .param("maxAttempts", step.getMaxAttempts())
                .param("timeoutSeconds", step.getTimeoutSeconds())
                .param("version", step.getVersion())
                .param("createdAt", Timestamp.from(step.getCreatedAt()))
                .param("updatedAt", Timestamp.from(step.getUpdatedAt()))
                .param("completedAt", step.getCompletedAt() == null ? null : Timestamp.from(step.getCompletedAt()))
                .update();
    }

    @Override
    public Optional<RunStep> findById(UUID id) {
        return jdbc.sql("SELECT " + SELECT_COLUMNS + " FROM run_step WHERE id = :id")
                .param("id", Objects.requireNonNull(id))
                .query(this::map)
                .optional();
    }

    @Override
    public List<RunStep> findByRunId(UUID reviewRunId) {
        return jdbc.sql("SELECT " + SELECT_COLUMNS + """
                        FROM run_step WHERE review_run_id = :reviewRunId ORDER BY ordinal
                        """)
                .param("reviewRunId", Objects.requireNonNull(reviewRunId))
                .query(this::map)
                .list();
    }

    private RunStep map(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
        String inputDigest = rs.getString("input_artifact_digest");
        String outputDigest = rs.getString("output_artifact_digest");
        Timestamp completedAt = rs.getTimestamp("completed_at");
        return new RunStep(
                rs.getObject("id", UUID.class),
                rs.getObject("review_run_id", UUID.class),
                rs.getObject("parent_step_id", UUID.class),
                rs.getString("step_key"),
                new OperationId(rs.getObject("operation_id", UUID.class)),
                rs.getString("execution_scope"),
                rs.getString("step_type"),
                StepState.valueOf(rs.getString("state")),
                rs.getInt("ordinal"),
                inputDigest == null ? null : new Digest(inputDigest),
                outputDigest == null ? null : new Digest(outputDigest),
                rs.getInt("max_attempts"),
                rs.getInt("timeout_seconds"),
                rs.getLong("version"),
                rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("updated_at").toInstant(),
                completedAt == null ? null : completedAt.toInstant());
    }
}
