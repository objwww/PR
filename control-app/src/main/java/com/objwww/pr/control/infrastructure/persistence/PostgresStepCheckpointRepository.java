package com.objwww.pr.control.infrastructure.persistence;

import com.objwww.pr.control.domain.model.StepCheckpoint;
import com.objwww.pr.control.domain.repository.StepCheckpointRepository;
import com.objwww.pr.shared.Digest;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.sql.Timestamp;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** checkpoint PG 适配器；租约校验与 upsert 是同一条 SQL，禁止 check-then-write。 */
public final class PostgresStepCheckpointRepository implements StepCheckpointRepository {

    private static final String UPSERT_SQL = """
            INSERT INTO step_checkpoint (
                id, step_id, checkpoint_key, output_artifact_digest,
                model_response_digest, checkpoint_contract_digest,
                prompt_template_version, finding_schema_version, mapper_contract_version,
                context_builder_version, model_identity,
                lease_epoch, attempt_no, created_at
            )
            SELECT :id, :stepId, :checkpointKey, :outputDigest,
                   :modelDigest, :contractDigest, :promptVersion, :schemaVersion,
                   :mapperVersion, :contextVersion, :modelIdentity,
                   :leaseEpoch, :attemptNo, :createdAt
              FROM work_item wi
             WHERE wi.id = :workItemId
               AND wi.step_id = :stepId
               AND wi.state = 'LEASED'
               AND wi.lease_owner = :leaseOwner
               AND wi.lease_epoch = :leaseEpoch
               AND now() <= wi.lease_until
            ON CONFLICT (step_id, checkpoint_key) DO UPDATE SET
                output_artifact_digest = EXCLUDED.output_artifact_digest,
                model_response_digest = EXCLUDED.model_response_digest,
                checkpoint_contract_digest = EXCLUDED.checkpoint_contract_digest,
                prompt_template_version = EXCLUDED.prompt_template_version,
                finding_schema_version = EXCLUDED.finding_schema_version,
                mapper_contract_version = EXCLUDED.mapper_contract_version,
                context_builder_version = EXCLUDED.context_builder_version,
                model_identity = EXCLUDED.model_identity,
                lease_epoch = EXCLUDED.lease_epoch,
                attempt_no = EXCLUDED.attempt_no,
                created_at = EXCLUDED.created_at
            WHERE EXISTS (
                SELECT 1 FROM work_item wi
                 WHERE wi.id = :workItemId
                   AND wi.step_id = :stepId
                   AND wi.state = 'LEASED'
                   AND wi.lease_owner = :leaseOwner
                   AND wi.lease_epoch = :leaseEpoch
                   AND now() <= wi.lease_until
            )
            """;

    private final JdbcClient jdbc;

    public PostgresStepCheckpointRepository(JdbcClient jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc);
    }

    @Override
    public Optional<StepCheckpoint> find(UUID stepId, String checkpointKey) {
        return jdbc.sql("""
                        SELECT id, step_id, checkpoint_key, output_artifact_digest,
                               model_response_digest, checkpoint_contract_digest,
                               prompt_template_version, finding_schema_version,
                               mapper_contract_version, context_builder_version, model_identity,
                               lease_epoch, attempt_no, created_at
                          FROM step_checkpoint
                         WHERE step_id = :stepId AND checkpoint_key = :checkpointKey
                        """)
                .param("stepId", Objects.requireNonNull(stepId))
                .param("checkpointKey", Objects.requireNonNull(checkpointKey))
                .query((rs, rowNum) -> new StepCheckpoint(
                        rs.getObject("id", UUID.class), rs.getObject("step_id", UUID.class),
                        rs.getString("checkpoint_key"),
                        new Digest(rs.getString("output_artifact_digest").trim()),
                        new Digest(rs.getString("model_response_digest").trim()),
                        new Digest(rs.getString("checkpoint_contract_digest").trim()),
                        rs.getString("prompt_template_version"), rs.getString("finding_schema_version"),
                        rs.getString("mapper_contract_version"), rs.getString("context_builder_version"),
                        rs.getString("model_identity"),
                        rs.getLong("lease_epoch"), rs.getInt("attempt_no"),
                        rs.getTimestamp("created_at").toInstant()))
                .optional();
    }

    @Override
    public boolean upsertIfLeaseCurrent(StepCheckpoint checkpoint, UUID workItemId, String leaseOwner) {
        Objects.requireNonNull(checkpoint);
        return jdbc.sql(UPSERT_SQL)
                .param("id", checkpoint.id())
                .param("stepId", checkpoint.stepId())
                .param("checkpointKey", checkpoint.checkpointKey())
                .param("outputDigest", checkpoint.outputArtifactDigest().value())
                .param("modelDigest", checkpoint.modelResponseDigest().value())
                .param("contractDigest", checkpoint.checkpointContractDigest().value())
                .param("promptVersion", checkpoint.promptTemplateVersion())
                .param("schemaVersion", checkpoint.findingSchemaVersion())
                .param("mapperVersion", checkpoint.mapperContractVersion())
                .param("contextVersion", checkpoint.contextBuilderVersion())
                .param("modelIdentity", checkpoint.modelIdentity())
                .param("leaseEpoch", checkpoint.leaseEpoch())
                .param("attemptNo", checkpoint.attemptNo())
                .param("createdAt", Timestamp.from(checkpoint.createdAt()))
                .param("workItemId", Objects.requireNonNull(workItemId))
                .param("leaseOwner", Objects.requireNonNull(leaseOwner))
                .update() == 1;
    }
}
