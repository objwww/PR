package com.objwww.pr.control.infrastructure.persistence;

import com.objwww.pr.control.alert.application.approval.SuspensionStore;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionOperations;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * V120 approval_suspension 的 Postgres 实现（PC-C3）。同 run 挂起唯一由部分唯一
 * 索引兜底；human_wait = wall clock 差（§2.10 双时钟：人的等待不进系统耗时）。
 */
public class PostgresSuspensionStore implements SuspensionStore {

    private final JdbcClient jdbc;
    private final TransactionOperations tx;

    public PostgresSuspensionStore(JdbcClient jdbc, TransactionOperations tx) {
        this.jdbc = Objects.requireNonNull(jdbc);
        this.tx = Objects.requireNonNull(tx);
    }

    @Override
    public UUID insertSuspension(UUID runId, UUID requestId, Instant at) {
        return tx.execute(status -> {
            UUID id = UUID.randomUUID();
            jdbc.sql("""
                    insert into approval_suspension(suspension_id, run_id, request_id,
                        state, suspended_at)
                    values (:id, :runId, :requestId, 'SUSPENDED', :at)
                    """)
                    .param("id", id)
                    .param("runId", runId)
                    .param("requestId", requestId)
                    .param("at", Timestamp.from(at))
                    .update();
            return id;
        });
    }

    @Override
    public boolean resumeSuspension(UUID runId, Instant at) {
        return Boolean.TRUE.equals(tx.execute(status -> jdbc.sql("""
                        update approval_suspension
                           set state = 'RESUMED', resumed_at = :at,
                               human_wait_seconds =
                                   extract(epoch from (:at - suspended_at))
                         where run_id = :runId and state = 'SUSPENDED'
                        """)
                .param("at", Timestamp.from(at))
                .param("runId", runId)
                .update()) == 1);
    }

    @Override
    public boolean hasActiveSuspension(UUID runId) {
        return Boolean.TRUE.equals(tx.execute(status -> jdbc.sql(
                        "select count(*) > 0 from approval_suspension"
                                + " where run_id = :run and state = 'SUSPENDED'")
                .param("run", runId)
                .query(Boolean.class)
                .single()));
    }

    @Override
    public Optional<SuspensionView> latest(UUID runId) {
        return tx.execute(status -> jdbc.sql("""
                        select suspension_id, run_id, request_id, state, suspended_at, resumed_at
                          from approval_suspension where run_id = :run
                         order by suspended_at desc limit 1
                        """)
                .param("run", runId)
                .query((rs, n) -> new SuspensionView(
                        rs.getObject("suspension_id", UUID.class),
                        rs.getObject("run_id", UUID.class),
                        rs.getObject("request_id", UUID.class),
                        rs.getString("state"),
                        rs.getTimestamp("suspended_at").toInstant(),
                        rs.getTimestamp("resumed_at") == null
                                ? null : rs.getTimestamp("resumed_at").toInstant()))
                .optional());
    }

    @Override
    public SuspensionStats stats() {
        return tx.execute(status -> jdbc.sql("""
                        select count(*) as total,
                               count(*) filter (where state = 'SUSPENDED') as active,
                               coalesce(sum(human_wait_seconds), 0) as wait
                          from approval_suspension
                        """)
                .query((rs, n) -> new SuspensionStats(rs.getLong("total"),
                        rs.getLong("active"), rs.getDouble("wait")))
                .single());
    }
}
