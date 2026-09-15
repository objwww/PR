package com.objwww.pr.control.infrastructure.persistence;

import com.objwww.pr.control.alert.application.mutation.ResourceLockStore;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionOperations;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * V116 resource_mutation_lock/counter 的 Postgres 实现（PB-B3，§2.9 语义 SQL 化）：
 *
 * <ul>
 *   <li>acquire：counter 行锁内领代（同资源并发领锁串行化）→ INSERT 锁行，
 *       任何已存在行（HELD/ORPHANED）= BUSY（唯一约束兜底并发窗）；</li>
 *   <li>releaseOnTerminalState：DELETE 的 WHERE 子查询绑定 Operation 释放集——
 *       状态闸在 SQL 层，不靠调用方自觉；</li>
 *   <li>markOrphanedExpired：HELD 且 ttl 过期 → ORPHANED（锁行保留 = 不让渡）。</li>
 * </ul>
 */
public class PostgresResourceLockStore implements ResourceLockStore {

    private final JdbcClient jdbc;
    private final TransactionOperations tx;

    public PostgresResourceLockStore(JdbcClient jdbc, TransactionOperations tx) {
        this.jdbc = Objects.requireNonNull(jdbc);
        this.tx = Objects.requireNonNull(tx);
    }

    @Override
    public Acquire acquire(String resourceUid, UUID operationId, UUID runId, Duration ttl,
            Instant now) {
        return tx.execute(status -> {
            // counter 行锁：同资源并发 acquire 串行化，代数严格单调
            Long epoch = jdbc.sql("""
                    insert into resource_mutation_counter(resource_uid, last_epoch)
                    values (:uid, 1)
                    on conflict (resource_uid) do update
                        set last_epoch = resource_mutation_counter.last_epoch + 1
                    returning last_epoch
                    """)
                    .param("uid", resourceUid)
                    .query(Long.class)
                    .single();
            Integer inserted = jdbc.sql("""
                    insert into resource_mutation_lock(resource_uid, operation_id, run_id,
                        resource_epoch, state, ttl_expires_at, acquired_at)
                    values (:uid, :opId, :runId, :epoch, 'HELD', :ttl, :now)
                    on conflict (resource_uid) do nothing
                    """)
                    .param("uid", resourceUid)
                    .param("opId", operationId)
                    .param("runId", runId)
                    .param("epoch", epoch)
                    .param("ttl", Timestamp.from(now.plus(ttl)))
                    .param("now", Timestamp.from(now))
                    .update();
            return inserted != null && inserted == 1
                    ? new Acquire(resourceUid, true, epoch)
                    : Acquire.busy(resourceUid);
        });
    }

    @Override
    public boolean releaseOnTerminalState(String resourceUid, UUID operationId) {
        return Boolean.TRUE.equals(tx.execute(status -> {
            Integer deleted = jdbc.sql("""
                    delete from resource_mutation_lock l
                     where l.resource_uid = :uid and l.operation_id = :opId
                       and exists (
                           select 1 from rca_operation o
                            where o.operation_id = l.operation_id
                              and o.status in ('VERIFIED','COMPLETED',
                                               'FAILED_CONFIRMED',
                                               'CANCELLED_BEFORE_DISPATCH'))
                    """)
                    .param("uid", resourceUid)
                    .param("opId", operationId)
                    .update();
            return deleted != null && deleted == 1;
        }));
    }

    @Override
    public int markOrphanedExpired(Instant now) {
        Integer updated = tx.execute(status -> jdbc.sql("""
                update resource_mutation_lock
                   set state = 'ORPHANED', orphaned_at = :now, updated_at = now()
                 where state = 'HELD' and ttl_expires_at < :now
                """)
                .param("now", Timestamp.from(now))
                .update());
        return updated == null ? 0 : updated;
    }

    @Override
    public List<OrphanedLock> orphanedLocks() {
        return tx.execute(status -> jdbc.sql("""
                select resource_uid, operation_id, run_id, resource_epoch,
                       ttl_expires_at
                  from resource_mutation_lock
                 where state = 'ORPHANED'
                 order by ttl_expires_at
                """)
                .query((rs, n) -> new OrphanedLock(rs.getString("resource_uid"),
                        rs.getObject("operation_id", UUID.class),
                        rs.getObject("run_id", UUID.class),
                        rs.getLong("resource_epoch"),
                        rs.getTimestamp("ttl_expires_at").toInstant()))
                .list());
    }

    @Override
    public Optional<LockView> find(String resourceUid) {
        return tx.execute(status -> jdbc.sql("""
                select resource_uid, operation_id, run_id, resource_epoch, state
                  from resource_mutation_lock where resource_uid = :uid
                """)
                .param("uid", resourceUid)
                .query((rs, n) -> new LockView(rs.getString("resource_uid"),
                        rs.getObject("operation_id", UUID.class),
                        rs.getObject("run_id", UUID.class),
                        rs.getLong("resource_epoch"), rs.getString("state")))
                .optional());
    }
}
