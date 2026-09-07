package com.objwww.pr.control.infrastructure.persistence;

import com.objwww.pr.control.alert.domain.budget.BudgetProbe;
import com.objwww.pr.control.alert.domain.budget.IncidentBudgetLedger;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionOperations;

import java.util.Objects;
import java.util.UUID;

/**
 * V13 Incident 窗口预算的 Postgres 实现（AM4 M4-09）。低频路径（run 派生）不维护计数行：
 * 短事务内锁 incident 行 → 按 created_at 过滤 SUM 双窗口 → 判定 → 双窗口各落一笔 entry。
 * 任一存储异常原样上抛（fail-closed，不派生 run）。
 */
public class PostgresIncidentBudgetLedger implements IncidentBudgetLedger {

    private final JdbcClient jdbc;
    private final TransactionOperations tx;

    public PostgresIncidentBudgetLedger(JdbcClient jdbc, TransactionOperations tx) {
        this.jdbc = Objects.requireNonNull(jdbc);
        this.tx = Objects.requireNonNull(tx);
    }

    @Override
    public BudgetProbe admit(UUID incidentId, UUID runId, long estimateUnits,
            long limit24h, long limit7d) {
        if (estimateUnits < 1) {
            throw new IllegalArgumentException("预留量必须 ≥1，实际: " + estimateUnits);
        }
        return tx.execute(status -> {
            // 同 incident 并发 admission 的串行化点（SUM-判-落账 原子）；incident 缺行即显式失败
            jdbc.sql("select id from incident where id = :id for update")
                    .param("id", incidentId)
                    .query(UUID.class).optional()
                    .orElseThrow(() -> new IllegalStateException("incident 不存在: " + incidentId));
            long sum24h = sumWindow(incidentId, "24H");
            long sum7d = sumWindow(incidentId, "7D");
            long consumed = Math.max(sum24h, sum7d);
            long remaining = Math.min(limit24h - sum24h, limit7d - sum7d);
            boolean allowed = sum24h + estimateUnits <= limit24h
                    && sum7d + estimateUnits <= limit7d;
            if (allowed) {
                insertEntry(incidentId, runId, "24H", estimateUnits);
                insertEntry(incidentId, runId, "7D", estimateUnits);
            }
            return allowed ? BudgetProbe.allowed(consumed, remaining)
                    : BudgetProbe.rejected(consumed, remaining);
        });
    }

    /** 双窗口各自 SUM（window_kind 等值用 '24H'/'7D'，interval 字面量用 '24 hours'/'7 days'——
     *  两值域不同，必须分开绑定，不可复用同一参数名） */
    private long sumWindow(UUID incidentId, String windowKind) {
        return jdbc.sql("""
                select coalesce(sum(units), 0) from incident_budget_entry
                 where incident_id = :incident and window_kind = :kind
                   and created_at > now() - CAST(:span AS interval)
                """)
                .param("incident", incidentId)
                .param("kind", windowKind)
                .param("span", windowKind.equals("24H") ? "24 hours" : "7 days")
                .query(Long.class).single();
    }

    private void insertEntry(UUID incidentId, UUID runId, String windowKind, long units) {
        jdbc.sql("""
                insert into incident_budget_entry(id, incident_id, run_id, window_kind, units)
                values (:id, :incident, :run, :window, :units)
                """)
                .param("id", UUID.randomUUID()).param("incident", incidentId)
                .param("run", runId).param("window", windowKind).param("units", units)
                .update();
    }
}
