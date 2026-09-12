package com.objwww.pr.control.alert.infrastructure;

import com.objwww.pr.control.alert.domain.budget.BudgetKind;
import com.objwww.pr.control.alert.domain.budget.BudgetProbe;
import com.objwww.pr.control.alert.domain.budget.ReservationKey;
import com.objwww.pr.control.alert.domain.budget.RunBudgetLedger;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 预算账本的内存实现（EX-A1）：与 {@code PostgresRunBudgetLedger} 同语义的假件——
 * 单语句原子性由 synchronized 兜底（同 JVM 串行），供单元测试假件环境使用。
 * <b>无行 fail-closed 拒绝</b>（BA-108 对齐）：无限额行时 reserve 拒绝且不落 entry、
 * probe remaining=0——与 PG 侧"单语句条件 UPDATE 无行=0 行更新=拒绝"同语义
 * （RunBudgetLedger javadoc 以 PG 语义为参照）；测试需先 openRun/ensureLimit 播种。
 *
 * <p>终态迁移与 PG 同形：RESERVED → COMMITTED/RELEASED/PROVISIONAL；
 * PROVISIONAL → UNMATCHED（usage 缺失不猜零）；落空显式抛。
 */
public class InMemoryRunBudgetLedger implements RunBudgetLedger {

    private static final class Entry {
        long reservedUnits;
        long committedUnits;
        String state;
    }

    private static final class LimitRow {
        long limitUnits;
        long consumedUnits;
    }

    private final Map<ReservationKey, Entry> entries = new ConcurrentHashMap<>();
    private final Map<String, LimitRow> limits = new ConcurrentHashMap<>();

    @Override
    public void ensureLimit(UUID runId, BudgetKind kind, long limitUnits) {
        limits.compute(absKey(runId, kind), (k, row) -> {
            if (row == null) {
                row = new LimitRow();
            }
            row.limitUnits = limitUnits;
            return row;
        });
    }

    @Override
    public synchronized BudgetProbe reserve(ReservationKey key, long units) {
        if (units < 1) {
            throw new IllegalArgumentException("预留量必须 ≥1，实际: " + units);
        }
        if (entries.containsKey(key)) {
            return probe(key.runId(), key.budgetKind(), true);
        }
        LimitRow row = limits.get(absKey(key.runId(), key.budgetKind()));
        // BA-108 对齐（PG 同语义）：无限额行 = fail-closed 拒绝且不落 entry
        if (row == null) {
            return probe(key.runId(), key.budgetKind(), false);
        }
        if (row.consumedUnits + units > row.limitUnits) {
            return probe(key.runId(), key.budgetKind(), false);
        }
        row.consumedUnits += units;
        Entry entry = new Entry();
        entry.reservedUnits = units;
        entry.state = "RESERVED";
        entries.put(key, entry);
        return probe(key.runId(), key.budgetKind(), true);
    }

    @Override
    public synchronized void commit(ReservationKey key, long actualUnits) {
        if (actualUnits < 0) {
            throw new IllegalArgumentException("实扣量必须 ≥0，实际: " + actualUnits);
        }
        Entry entry = settleFrom(key, "COMMITTED", "RESERVED", "PROVISIONAL");
        entry.committedUnits = actualUnits;
        adjust(key, actualUnits - entry.reservedUnits);
    }

    @Override
    public synchronized void release(ReservationKey key) {
        Entry entry = settleFrom(key, "RELEASED", "RESERVED");
        adjust(key, -entry.reservedUnits);
    }

    @Override
    public synchronized void provisional(ReservationKey key) {
        settleFrom(key, "PROVISIONAL", "RESERVED");
    }

    @Override
    public synchronized void markUnmatched(ReservationKey key) {
        settleFrom(key, "UNMATCHED", "RESERVED", "PROVISIONAL");
    }

    @Override
    public List<ReservationKey> findStaleReservations(Instant olderThan) {
        return List.of();
    }

    // ------------------------------------------------------------------ 观测面（测试断言用）

    public String stateOf(ReservationKey key) {
        Entry entry = entries.get(Objects.requireNonNull(key));
        return entry == null ? null : entry.state;
    }

    public long consumedOf(UUID runId, BudgetKind kind) {
        LimitRow row = limits.get(absKey(runId, kind));
        return row == null ? 0L : row.consumedUnits;
    }

    public int entryCount() {
        return entries.size();
    }

    // ------------------------------------------------------------------ 内部

    private Entry settleFrom(ReservationKey key, String target, String... fromStates) {
        Entry entry = entries.get(key);
        if (entry == null || !List.of(fromStates).contains(entry.state)) {
            throw new IllegalStateException("预算结算落空（from=" + String.join("/", fromStates)
                    + " to=" + target + "）: " + key);
        }
        entry.state = target;
        return entry;
    }

    private void adjust(ReservationKey key, long delta) {
        LimitRow row = limits.get(absKey(key.runId(), key.budgetKind()));
        if (row != null) {
            row.consumedUnits += delta;
        }
    }

    private BudgetProbe probe(UUID runId, BudgetKind kind, boolean allowed) {
        LimitRow row = limits.get(absKey(runId, kind));
        // BA-108 对齐：无限额行 remaining=0（与 PG"无行拒绝"同语义观测面）
        long consumed = row == null ? 0 : row.consumedUnits;
        long remaining = row == null ? 0 : row.limitUnits - consumed;
        return allowed ? BudgetProbe.allowed(consumed, remaining)
                : BudgetProbe.rejected(consumed, remaining);
    }

    private static String absKey(UUID runId, BudgetKind kind) {
        return runId + ":" + kind.name();
    }
}
