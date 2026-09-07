package com.objwww.pr.control.infrastructure.persistence;

import com.objwww.pr.control.alert.domain.budget.BudgetKind;
import com.objwww.pr.control.alert.domain.budget.BudgetProbe;
import com.objwww.pr.control.alert.domain.budget.ReservationKey;
import com.objwww.pr.control.alert.domain.budget.RunBudgetLedger;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionOperations;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * V13 预算账本的 Postgres 实现（AM4 M4-08）。
 *
 * <p>锁纪律（技术方案 §6）：多语句路径包在自含短事务（TransactionOperations）里，
 * 方法返回即出锁——网络调用期间不持有任何行锁。reserve 的"读-判-扣-写"是
 * run_budget_state 行上的<b>单语句条件 UPDATE</b>（行锁天然串行化并发预留）；
 * 幂等业务键的落账用 {@code ON CONFLICT DO NOTHING} 判胜负，输家回退自己的预增。
 */
public class PostgresRunBudgetLedger implements RunBudgetLedger {

    private final JdbcClient jdbc;
    private final TransactionOperations tx;

    public PostgresRunBudgetLedger(JdbcClient jdbc, TransactionOperations tx) {
        this.jdbc = Objects.requireNonNull(jdbc);
        this.tx = Objects.requireNonNull(tx);
    }

    @Override
    public void ensureLimit(UUID runId, BudgetKind kind, long limitUnits) {
        jdbc.sql("""
                insert into run_budget_state(run_id, budget_kind, limit_units, consumed_units)
                values (:runId, :kind, :limit, 0)
                on conflict (run_id, budget_kind)
                do update set limit_units = :limit, updated_at = now()
                """)
                .param("runId", runId).param("kind", kind.name()).param("limit", limitUnits)
                .update();
    }

    @Override
    public BudgetProbe reserve(ReservationKey key, long units) {
        if (units < 1) {
            throw new IllegalArgumentException("预留量必须 ≥1，实际: " + units);
        }
        return tx.execute(status -> {
            // 同幂等键重试快路径：读同一笔既有预留（不重复扣——即使预算已打满也必须放行）
            if (entryExists(key)) {
                return probe(key, true);
            }
            int deducted = jdbc.sql("""
                    update run_budget_state
                       set consumed_units = consumed_units + :units, updated_at = now()
                     where run_id = :run and budget_kind = :kind
                       and consumed_units + :units <= limit_units
                    """)
                    .param("units", units).param("run", key.runId())
                    .param("kind", key.budgetKind().name())
                    .update();
            if (deducted == 0) {
                return probe(key, false);
            }
            int inserted = jdbc.sql("""
                    insert into run_budget_entry(id, run_id, task_id, attempt_id, call_seq,
                        budget_kind, state, reserved_units)
                    values (:id, :run, :task, :attempt, :seq, :kind, 'RESERVED', :units)
                    on conflict (run_id, task_id, attempt_id, call_seq, budget_kind)
                    do nothing
                    """)
                    .param("id", UUID.randomUUID()).param("run", key.runId())
                    .param("task", key.taskId()).param("attempt", key.attemptId())
                    .param("seq", key.callSeq()).param("kind", key.budgetKind().name())
                    .param("units", units)
                    .update();
            if (inserted == 0) {
                // 同幂等键已有 reservation（顺序重试/并发竞胜）——回退本次预增，读既有账面
                jdbc.sql("""
                        update run_budget_state
                           set consumed_units = consumed_units - :units, updated_at = now()
                         where run_id = :run and budget_kind = :kind
                        """)
                        .param("units", units).param("run", key.runId())
                        .param("kind", key.budgetKind().name())
                        .update();
            }
            return probe(key, true);
        });
    }

    @Override
    public void commit(ReservationKey key, long actualUnits) {
        if (actualUnits < 0) {
            throw new IllegalArgumentException("实扣量必须 ≥0，实际: " + actualUnits);
        }
        tx.executeWithoutResult(status -> {
            Long reserved = jdbc.sql("""
                    update run_budget_entry
                       set state = 'COMMITTED', committed_units = :actual, settled_at = now()
                     where run_id = :run and task_id = :task and attempt_id = :attempt
                       and call_seq = :seq and budget_kind = :kind
                       and state in ('RESERVED', 'PROVISIONAL')
                    returning reserved_units
                    """)
                    .param("actual", actualUnits).param("run", key.runId())
                    .param("task", key.taskId()).param("attempt", key.attemptId())
                    .param("seq", key.callSeq()).param("kind", key.budgetKind().name())
                    .query(Long.class).optional()
                    .orElseThrow(() -> new IllegalStateException(
                            "commit 落空（无 RESERVED/PROVISIONAL 预留）: " + key));
            adjust(key, actualUnits - reserved);
        });
    }

    @Override
    public void release(ReservationKey key) {
        Long reserved = settleFrom(key, "RELEASED", "'RESERVED'");
        adjust(key, -reserved);
    }

    @Override
    public void provisional(ReservationKey key) {
        settleFrom(key, "PROVISIONAL", "'RESERVED'");
    }

    @Override
    public void markUnmatched(ReservationKey key) {
        settleFrom(key, "UNMATCHED", "'PROVISIONAL'");
    }

    private boolean entryExists(ReservationKey key) {
        return jdbc.sql("""
                select count(*) from run_budget_entry
                 where run_id = :run and task_id = :task and attempt_id = :attempt
                   and call_seq = :seq and budget_kind = :kind
                """)
                .param("run", key.runId()).param("task", key.taskId())
                .param("attempt", key.attemptId()).param("seq", key.callSeq())
                .param("kind", key.budgetKind().name())
                .query(Long.class).single() > 0;
    }

    /** entry 结案迁移（fromStates → targetState），返回原预留量；无满足行即抛（落空必显式）。
     *  V13 ck 分态约束 settled_at 可空性：PROVISIONAL 是悬挂态（settled_at 必空），
     *  终态（RELEASED/UNMATCHED）结算时刻必填——按目标态条件化赋值 */
    private long settleFrom(ReservationKey key, String targetState, String fromStatesSql) {
        String settledAssign = "PROVISIONAL".equals(targetState)
                ? "settled_at = null"
                : "settled_at = now()";
        return jdbc.sql(("""
                update run_budget_entry
                   set state = '%s', %s
                 where run_id = :run and task_id = :task and attempt_id = :attempt
                   and call_seq = :seq and budget_kind = :kind
                   and state in (%s)
                returning reserved_units
                """).formatted(targetState, settledAssign, fromStatesSql))
                .param("run", key.runId()).param("task", key.taskId())
                .param("attempt", key.attemptId()).param("seq", key.callSeq())
                .param("kind", key.budgetKind().name())
                .query(Long.class).optional()
                .orElseThrow(() -> new IllegalStateException(
                        "预算结算落空（from=" + fromStatesSql + " to=" + targetState + "）: " + key));
    }

    private void adjust(ReservationKey key, long delta) {
        jdbc.sql("""
                update run_budget_state
                   set consumed_units = consumed_units + :delta, updated_at = now()
                 where run_id = :run and budget_kind = :kind
                """)
                .param("delta", delta).param("run", key.runId())
                .param("kind", key.budgetKind().name())
                .update();
    }

    private BudgetProbe probe(ReservationKey key, boolean allowed) {
        return jdbc.sql("""
                select consumed_units, limit_units - consumed_units as remaining
                  from run_budget_state where run_id = :run and budget_kind = :kind
                """)
                .param("run", key.runId()).param("kind", key.budgetKind().name())
                .query((rs, n) -> {
                    long consumed = rs.getLong(1);
                    long remaining = rs.getLong(2);
                    return allowed ? BudgetProbe.allowed(consumed, remaining)
                            : BudgetProbe.rejected(consumed, remaining);
                })
                .single();
    }

    @Override
    public List<ReservationKey> findStaleReservations(Instant olderThan) {
        return jdbc.sql("""
                select run_id, task_id, attempt_id, call_seq, budget_kind
                  from run_budget_entry
                 where state in ('RESERVED', 'PROVISIONAL') and created_at < :older
                 order by created_at, id
                """)
                .param("older", olderThan)
                .query((rs, n) -> new ReservationKey(
                        rs.getObject("run_id", UUID.class),
                        rs.getObject("task_id", UUID.class),
                        rs.getObject("attempt_id", UUID.class),
                        rs.getLong("call_seq"),
                        BudgetKind.valueOf(rs.getString("budget_kind"))))
                .list();
    }
}
