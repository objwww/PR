package com.objwww.pr.control.infrastructure.persistence;

import com.objwww.pr.control.alert.application.mutation.ScopeExpansionLedger;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionOperations;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * V115 scope_expansion 实现（PB-B2）：APPROVED 必带 approval_id 由 DDL check 强制
 * （ck_scope_expansion_anchor）；应用面只提供 PENDING 落账与已锚定查询。
 */
public class PostgresScopeExpansionLedger implements ScopeExpansionLedger {

    private final JdbcClient jdbc;
    private final TransactionOperations tx;

    public PostgresScopeExpansionLedger(JdbcClient jdbc, TransactionOperations tx) {
        this.jdbc = Objects.requireNonNull(jdbc);
        this.tx = Objects.requireNonNull(tx);
    }

    @Override
    public UUID recordPending(UUID runId, String requestedKey, String resolvedResourceUid,
            String reason, String snapshotJson, String snapshotHash, Instant at) {
        return tx.execute(status -> {
            UUID id = UUID.randomUUID();
            jdbc.sql("""
                    insert into scope_expansion(expansion_id, run_id, requested_resource_key,
                        resolved_resource_uid, reason, scope_snapshot, scope_snapshot_hash,
                        status, created_at)
                    values (:id, :runId, :key, :uid, :reason, CAST(:snapshot AS jsonb),
                        :hash, 'PENDING_APPROVAL', :at)
                    """)
                    .param("id", id)
                    .param("runId", runId)
                    .param("key", requestedKey)
                    .param("uid", resolvedResourceUid)
                    .param("reason", reason)
                    .param("snapshot", snapshotJson)
                    .param("hash", snapshotHash)
                    .param("at", Timestamp.from(at))
                    .update();
            return id;
        });
    }

    @Override
    public boolean hasApprovedExpansion(UUID runId, String resourceUid) {
        Integer hits = tx.execute(status -> jdbc.sql("""
                select count(*) from scope_expansion
                 where run_id = :runId and resolved_resource_uid = :uid
                   and status = 'APPROVED' and approval_id is not null
                """)
                .param("runId", runId)
                .param("uid", resourceUid)
                .query(Integer.class)
                .single());
        return hits != null && hits > 0;
    }
}
