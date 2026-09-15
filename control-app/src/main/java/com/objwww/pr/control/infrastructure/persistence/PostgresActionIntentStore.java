package com.objwww.pr.control.infrastructure.persistence;

import com.objwww.pr.control.alert.application.mutation.ActionIntentStore;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionOperations;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.sql.Timestamp;

/**
 * V114 action_intent 的读/解析推进实现（PB-B2）。markResolved 以
 * {@code resolved_resource_uid IS NULL} 为 CAS 条件——解析是一次性事实，
 * 并发重复解析恰一个赢家（快照锚不可被覆盖）。
 */
public class PostgresActionIntentStore implements ActionIntentStore {

    private final JdbcClient jdbc;
    private final TransactionOperations tx;

    public PostgresActionIntentStore(JdbcClient jdbc, TransactionOperations tx) {
        this.jdbc = Objects.requireNonNull(jdbc);
        this.tx = Objects.requireNonNull(tx);
    }

    @Override
    public Optional<IntentView> findById(UUID intentId) {
        return tx.execute(status -> jdbc.sql("""
                select intent_id, run_id, task_id, action_digest, tool_name, risk,
                       resolved_resource_uid, scope_snapshot_hash,
                       args_json::text as args_json
                  from action_intent where intent_id = :id
                """)
                .param("id", intentId)
                .query((rs, n) -> new IntentView(
                        UUID.fromString(rs.getString("intent_id")),
                        rs.getObject("run_id", UUID.class),
                        rs.getObject("task_id", UUID.class),
                        rs.getString("action_digest"),
                        rs.getString("tool_name"),
                        rs.getString("risk"),
                        rs.getString("resolved_resource_uid"),
                        rs.getString("scope_snapshot_hash"),
                        rs.getString("args_json")))
                .optional());
    }

    @Override
    public boolean markResolved(UUID intentId, String resourceUid, String snapshotJson,
            String snapshotHash, Instant at) {
        Integer updated = tx.execute(status -> jdbc.sql("""
                update action_intent
                   set resolved_resource_uid = :uid,
                       scope_snapshot = CAST(:snapshot AS jsonb),
                       scope_snapshot_hash = :hash,
                       resolved_at = :at,
                       updated_at = now()
                 where intent_id = :id and resolved_resource_uid is null
                """)
                .param("uid", resourceUid)
                .param("snapshot", snapshotJson)
                .param("hash", snapshotHash)
                .param("at", Timestamp.from(at))
                .param("id", intentId)
                .update());
        return updated != null && updated == 1;
    }

    @Override
    public boolean markPlanned(UUID intentId, UUID operationId, Instant at) {
        Integer updated = tx.execute(status -> jdbc.sql("""
                update action_intent
                   set status = 'PLANNED', operation_id = :opId, updated_at = now()
                 where intent_id = :id and status = 'OPEN'
                   and resolved_resource_uid is not null
                """)
                .param("opId", operationId)
                .param("id", intentId)
                .update());
        return updated != null && updated == 1;
    }
}
