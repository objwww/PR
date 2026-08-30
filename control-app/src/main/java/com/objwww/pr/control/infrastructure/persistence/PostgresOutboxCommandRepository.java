package com.objwww.pr.control.infrastructure.persistence;

import com.objwww.pr.control.domain.repository.OutboxCommandRepository;
import com.objwww.pr.shared.DependencyMode;
import com.objwww.pr.shared.OperationId;
import com.objwww.pr.shared.OutboxCommand;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Objects;

/**
 * OutboxCommandRepository 的 Postgres 实现——Control 侧只有 INSERT（DB 角色不给 UPDATE 权，I10）。
 * lease/attempt/reconcile/remote_id 等执行期列全部走表默认值，由 Publisher（T3）推进；
 * 本类刻意不提供任何 SELECT/UPDATE 方法（命令创建后 Control 不再触碰其状态，AFT-06）。
 *
 * <p>刻意不加 Spring 注解：接线见 infrastructure/config/PersistenceConfig（docker profile）。
 */
public class PostgresOutboxCommandRepository implements OutboxCommandRepository {

    private static final String INSERT_SQL = """
            INSERT INTO outbox_command (
                operation_id, pr_subject_id, review_run_id, pr_revision_id,
                aggregate_key, aggregate_sequence, publication_epoch, fence_mode,
                command_type, state, policy_version,
                payload_artifact_digest, payload_hash, remote_identity_type,
                created_at, updated_at
            ) VALUES (
                :operationId, :prSubjectId, :reviewRunId, :prRevisionId,
                :aggregateKey, :aggregateSequence, :publicationEpoch, :fenceMode,
                :commandType, :state, :policyVersion,
                :payloadArtifactDigest, :payloadHash, :remoteIdentityType,
                :createdAt, :updatedAt
            )
            """;

    private static final String INSERT_DEPENDENCY_SQL = """
            INSERT INTO outbox_dependency (operation_id, depends_on_operation_id, dependency_mode, created_at)
            VALUES (:operationId, :dependsOnOperationId, :dependencyMode, :createdAt)
            """;

    private final JdbcClient jdbc;

    public PostgresOutboxCommandRepository(JdbcClient jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc);
    }

    @Override
    public void insert(OutboxCommand command) {
        Objects.requireNonNull(command, "command");
        Timestamp now = Timestamp.from(Instant.now());
        jdbc.sql(INSERT_SQL)
                .param("operationId", command.operationId().value())
                .param("prSubjectId", command.prSubjectId())
                .param("reviewRunId", command.reviewRunId())
                .param("prRevisionId", command.prRevisionId())
                .param("aggregateKey", command.aggregateKey())
                .param("aggregateSequence", command.aggregateSequence())
                .param("publicationEpoch", command.publicationEpoch())
                .param("fenceMode", command.fenceMode().name())
                .param("commandType", command.commandType().name())
                .param("state", command.state().name())
                .param("policyVersion", command.policyVersion())
                .param("payloadArtifactDigest",
                        command.payloadArtifactDigest() == null ? null : command.payloadArtifactDigest().value())
                .param("payloadHash", command.payloadHash().value())
                .param("remoteIdentityType", command.remoteIdentityType().name())
                .param("createdAt", now)
                .param("updatedAt", now)
                .update();
    }

    @Override
    public void insertDependency(OperationId operationId, OperationId dependsOnOperationId,
                                 DependencyMode mode, Instant createdAt) {
        jdbc.sql(INSERT_DEPENDENCY_SQL)
                .param("operationId", Objects.requireNonNull(operationId).value())
                .param("dependsOnOperationId", Objects.requireNonNull(dependsOnOperationId).value())
                .param("dependencyMode", Objects.requireNonNull(mode).name())
                .param("createdAt", Timestamp.from(Objects.requireNonNull(createdAt)))
                .update();
    }
}
