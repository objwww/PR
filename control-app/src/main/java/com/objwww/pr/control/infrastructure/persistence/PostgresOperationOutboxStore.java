package com.objwww.pr.control.infrastructure.persistence;

import com.objwww.pr.control.alert.application.mutation.OperationOutboxStore;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionOperations;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * V117 operation_outbox 的 Postgres 实现（PB-B4，notify_outbox 同型）：claimNext =
 * PENDING 公平序 + FOR UPDATE SKIP LOCKED + 租约 CAS（多实例安全，at-least-once）。
 */
public class PostgresOperationOutboxStore implements OperationOutboxStore {

    private final JdbcClient jdbc;
    private final TransactionOperations tx;

    public PostgresOperationOutboxStore(JdbcClient jdbc, TransactionOperations tx) {
        this.jdbc = Objects.requireNonNull(jdbc);
        this.tx = Objects.requireNonNull(tx);
    }

    @Override
    public void insert(UUID outboxId, UUID operationId, Instant now) {
        tx.executeWithoutResult(status -> jdbc.sql("""
                insert into operation_outbox(outbox_id, operation_id, state, available_at,
                    created_at, updated_at)
                values (:id, :opId, 'PENDING', :now, :now, :now)
                """)
                .param("id", outboxId)
                .param("opId", operationId)
                .param("now", Timestamp.from(now))
                .update());
    }

    @Override
    public Optional<Claimed> claimNext(String owner, Duration lease, Instant now) {
        return tx.execute(status -> jdbc.sql("""
                with victim as (
                    select outbox_id from operation_outbox
                     where state = 'PENDING' and available_at <= :now
                     order by available_at, created_at
                     limit 1 for update skip locked
                )
                update operation_outbox o
                   set state = 'CLAIMED', lease_owner = :owner,
                       lease_until = :leaseUntil, lease_epoch = o.lease_epoch + 1,
                       attempt_count = o.attempt_count + 1, updated_at = now()
                  from victim
                 where o.outbox_id = victim.outbox_id
                returning o.outbox_id, o.operation_id, o.lease_epoch, o.attempt_count
                """)
                .param("now", Timestamp.from(now))
                .param("owner", owner)
                .param("leaseUntil", Timestamp.from(now.plus(lease)))
                .query((rs, n) -> {
                    UUID operationId = rs.getObject("operation_id", UUID.class);
                    UUID runId = jdbc.sql("select run_id from rca_operation"
                                    + " where operation_id = :id")
                            .param("id", operationId)
                            .query((rs2, n2) -> rs2.getObject("run_id", UUID.class))
                            .single();
                    return new Claimed(rs.getObject("outbox_id", UUID.class), operationId,
                            runId, rs.getLong("lease_epoch"), rs.getInt("attempt_count"));
                })
                .optional());
    }

    @Override
    public int reclaimExpiredLeases(Instant now) {
        Integer updated = tx.execute(status -> jdbc.sql("""
                update operation_outbox
                   set state = 'PENDING', lease_owner = null, lease_until = null,
                       last_error = jsonb_build_object('reason', 'LEASE_EXPIRED'),
                       updated_at = now()
                 where state = 'CLAIMED' and lease_until < :now
                """)
                .param("now", Timestamp.from(now))
                .update());
        return updated == null ? 0 : updated;
    }

    @Override
    public boolean markDispatched(UUID outboxId, Instant now) {
        return Boolean.TRUE.equals(tx.execute(status -> {
            Integer updated = jdbc.sql("""
                    update operation_outbox
                       set state = 'DISPATCHED', dispatched_at = :now, updated_at = now()
                     where outbox_id = :id
                    """)
                    .param("now", Timestamp.from(now))
                    .param("id", outboxId)
                    .update();
            return updated != null && updated == 1;
        }));
    }

    @Override
    public boolean backToPending(UUID operationId, Instant now) {
        return Boolean.TRUE.equals(tx.execute(status -> {
            Integer updated = jdbc.sql("""
                    update operation_outbox
                       set state = 'PENDING', lease_owner = null, lease_until = null,
                           available_at = :now, updated_at = now()
                     where operation_id = :id
                    """)
                    .param("now", Timestamp.from(now))
                    .param("id", operationId)
                    .update();
            return updated != null && updated == 1;
        }));
    }

    @Override
    public Optional<OutboxView> findByOperation(UUID operationId) {
        return tx.execute(status -> jdbc.sql("""
                select outbox_id, operation_id, state, attempt_count, lease_epoch
                  from operation_outbox where operation_id = :id
                """)
                .param("id", operationId)
                .query((rs, n) -> new OutboxView(rs.getObject("outbox_id", UUID.class),
                        rs.getObject("operation_id", UUID.class), rs.getString("state"),
                        rs.getInt("attempt_count"), rs.getLong("lease_epoch")))
                .optional());
    }
}
