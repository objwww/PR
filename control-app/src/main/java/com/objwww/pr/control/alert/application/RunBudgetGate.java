package com.objwww.pr.control.alert.application;

import com.objwww.pr.control.alert.domain.budget.BudgetExhaustedException;
import com.objwww.pr.control.alert.domain.budget.BudgetKind;
import com.objwww.pr.control.alert.domain.budget.BudgetProbe;
import com.objwww.pr.control.alert.domain.budget.ReservationKey;
import com.objwww.pr.control.alert.domain.budget.RunBudgetLedger;

import java.util.Objects;
import java.util.function.Supplier;
import java.util.function.ToLongFunction;

/**
 * 预算门（AM4 M4-08，INV-AM4-9 执行点）：远程调用唯一过账咽喉。
 *
 * <p>三段式编排：reserve（保守估值）拒 → 抛 {@link BudgetExhaustedException}，
 * <b>零远程调用</b>；remote 成功 → 按服务端 usage 实扣 {@code commit(actual)}；
 * remote 抛（含发送后取消/超时）→ {@code provisional} 等对账、异常原样上抛——
 * 不按估算平账（无法区分"已发出未达"），也不伪造退款。账本方法自含短事务，
 * 故 remote 执行期间不持有任何行锁。
 */
public class RunBudgetGate {

    private final RunBudgetLedger ledger;

    public RunBudgetGate(RunBudgetLedger ledger) {
        this.ledger = Objects.requireNonNull(ledger);
    }

    /**
     * 有预算的远程调用。
     *
     * @param key            幂等业务键（同键重试在账本侧读同一笔预留）
     * @param estimateUnits  保守估值（输入实计+输出最坏值；事前封顶，不声称精确）
     * @param remote         远程调用体（网络期无锁）
     * @param usageExtractor 从结果提取服务端权威 usage（缺失由调用方先转
     *                       {@link RunBudgetLedger#markUnmatched}，不在此伪造零）
     */
    public <T> T call(ReservationKey key, long estimateUnits, Supplier<T> remote,
            ToLongFunction<T> usageExtractor) {
        if (estimateUnits < 1) {
            throw new IllegalArgumentException("预算估值必须 ≥1，实际: " + estimateUnits);
        }
        BudgetProbe probe = ledger.reserve(key, estimateUnits);
        if (!probe.allowed()) {
            throw BudgetExhaustedException.ofKind(key.budgetKind(),
                    probe.consumed(), probe.remaining(), estimateUnits);
        }
        T result;
        try {
            result = remote.get();
        } catch (RuntimeException e) {
            ledger.provisional(key);
            throw e;
        }
        ledger.commit(key, usageExtractor.applyAsLong(result));
        return result;
    }
}
