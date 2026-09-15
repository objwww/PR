package com.objwww.pr.control.infrastructure.persistence;

import com.objwww.pr.control.alert.application.mutation.OperationLedgerStore;
import com.objwww.pr.control.alert.domain.mutation.OperationStatus;
import com.objwww.pr.control.alert.domain.mutation.OperationStateMachine;
import com.objwww.pr.control.alert.domain.mutation.RcaOperation;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionOperations;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * V114 rca_operation 的 Postgres 实现（PB-B4）。transition = 域状态机先验 + DB
 * 当前态 CAS（WHERE status = :expected）双闸：并发推进恰一赢家，败方幂等让步。
 */
public class PostgresOperationLedgerStore implements OperationLedgerStore {

    private final JdbcClient jdbc;
    private final TransactionOperations tx;

    public PostgresOperationLedgerStore(JdbcClient jdbc, TransactionOperations tx) {
        this.jdbc = Objects.requireNonNull(jdbc);
        this.tx = Objects.requireNonNull(tx);
    }

    @Override
    public void insert(RcaOperation operation) {
        tx.executeWithoutResult(status -> jdbc.sql("""
                insert into rca_operation(operation_id, intent_id, run_id, task_id,
                    action_id, action_digest, resource_uid, resource_epoch, status,
                    dry_run, params_json, created_at, prepared_at)
                values (:id, :intentId, :runId, :taskId, :actionId, :digest, :uid,
                    :epoch, :status, :dryRun, CAST(:params AS jsonb), :now, :now)
                """)
                .param("id", operation.operationId())
                .param("intentId", operation.intentId())
                .param("runId", operation.runId())
                .param("taskId", operation.taskId())
                .param("actionId", operation.actionId())
                .param("digest", operation.actionDigest())
                .param("uid", operation.resourceUid())
                .param("epoch", operation.resourceEpoch())
                .param("status", operation.status().name())
                .param("dryRun", operation.dryRun())
                .param("params", operation.paramsJson())
                .param("now", Timestamp.from(operation.createdAt()))
                .update());
    }

    @Override
    public void updateResourceEpoch(UUID operationId, long resourceEpoch) {
        tx.executeWithoutResult(status -> jdbc.sql(
                        "update rca_operation set resource_epoch = :epoch, updated_at = now()"
                                + " where operation_id = :id")
                .param("epoch", resourceEpoch)
                .param("id", operationId)
                .update());
    }

    @Override
    public Optional<RcaOperation> findById(UUID operationId) {
        return tx.execute(status -> jdbc.sql("""
                select operation_id, intent_id, run_id, task_id, action_id, action_digest,
                       resource_uid, resource_epoch, status, dry_run, params_json,
                       created_at, prepared_at, dispatched_at, ack_at, verified_at,
                       completed_at
                  from rca_operation where operation_id = :id
                """)
                .param("id", operationId)
                .query((rs, n) -> new RcaOperation(
                        rs.getObject("operation_id", UUID.class),
                        rs.getObject("intent_id", UUID.class),
                        rs.getObject("run_id", UUID.class),
                        rs.getObject("task_id", UUID.class),
                        rs.getString("action_id"),
                        rs.getString("action_digest"),
                        rs.getString("resource_uid"),
                        rs.getLong("resource_epoch"),
                        OperationStatus.valueOf(rs.getString("status")),
                        rs.getBoolean("dry_run"),
                        rs.getString("params_json"),
                        rs.getTimestamp("created_at").toInstant(),
                        ts(rs, "prepared_at"), ts(rs, "dispatched_at"), ts(rs, "ack_at"),
                        ts(rs, "verified_at"), ts(rs, "completed_at")))
                .optional());
    }
    @Override
    public boolean transition(UUID operationId, OperationStatus expected, OperationStatus next,
            Instant at) {
        OperationStateMachine.checkTransition(expected, next);
        return Boolean.TRUE.equals(tx.execute(status -> {
            String timestampColumn = switch (next) {
                case DISPATCHED -> "dispatched_at";
                case ACKNOWLEDGED -> "ack_at";
                case VERIFIED -> "verified_at";
                case COMPLETED -> "completed_at";
                default -> null;
            };
            String sql = "update rca_operation set status = :next, updated_at = now()"
                    + (timestampColumn == null ? "" : ", " + timestampColumn + " = :at")
                    + " where operation_id = :id and status = :expected";
            var query = jdbc.sql(sql)
                    .param("next", next.name())
                    .param("id", operationId)
                    .param("expected", expected.name());
            if (timestampColumn != null) {
                query = query.param("at", Timestamp.from(at));
            }
            Integer updated = query.update();
            return updated != null && updated == 1;
        }));
    }

    private static Instant ts(java.sql.ResultSet rs, String column) throws java.sql.SQLException {
        Timestamp value = rs.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }
}
