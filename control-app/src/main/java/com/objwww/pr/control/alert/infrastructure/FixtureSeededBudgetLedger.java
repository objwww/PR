package com.objwww.pr.control.alert.infrastructure;

import com.objwww.pr.control.alert.domain.budget.BudgetKind;
import com.objwww.pr.control.alert.domain.budget.BudgetProbe;
import com.objwww.pr.control.alert.domain.budget.ReservationKey;
import com.objwww.pr.control.alert.domain.budget.RunBudgetLedger;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * BA-108 测试便利面 fixture 账本（仅供 {@link SingleToolEvidenceAgent} 假件构造器）：
 * 首次触碰某 (runId, kind) 时<b>显式播种</b>慷慨限额 {@value #FIXTURE_LIMIT}，此后
 * 语义与 {@link InMemoryRunBudgetLedger} 完全一致——有行强制、超限拒绝、无行拒绝。
 *
 * <p>与被 BA-108 废止的"无行=不限"失真面不同：限额存在、有限、可见（本类常量）。
 * 假件构造器不可知 runId（构造期无 run、investigate 期才有），播种只能延后到首次
 * 储备——这是 fixture 的显式代办面，不是准入语义；生产装配禁止（全参构造唯一入口，
 * PG 账本，准入面真实播种）。
 */
public final class FixtureSeededBudgetLedger implements RunBudgetLedger {

    /** fixture 慷慨限额（有限可见；超限同样走 PG 同款拒绝语义） */
    public static final long FIXTURE_LIMIT = 1_000_000L;

    private final InMemoryRunBudgetLedger delegate = new InMemoryRunBudgetLedger();
    private final Set<String> seeded = ConcurrentHashMap.newKeySet();

    private void seedOnce(UUID runId, BudgetKind kind) {
        if (seeded.add(runId + ":" + kind.name())) {
            delegate.ensureLimit(runId, kind, FIXTURE_LIMIT);
        }
    }

    @Override
    public void ensureLimit(UUID runId, BudgetKind kind, long limitUnits) {
        delegate.ensureLimit(runId, kind, limitUnits);
    }

    @Override
    public BudgetProbe reserve(ReservationKey key, long units) {
        seedOnce(key.runId(), key.budgetKind());
        return delegate.reserve(key, units);
    }

    @Override
    public void commit(ReservationKey key, long actualUnits) {
        delegate.commit(key, actualUnits);
    }

    @Override
    public void release(ReservationKey key) {
        delegate.release(key);
    }

    @Override
    public void provisional(ReservationKey key) {
        delegate.provisional(key);
    }

    @Override
    public void markUnmatched(ReservationKey key) {
        delegate.markUnmatched(key);
    }

    @Override
    public List<ReservationKey> findStaleReservations(Instant olderThan) {
        return delegate.findStaleReservations(olderThan);
    }
}
