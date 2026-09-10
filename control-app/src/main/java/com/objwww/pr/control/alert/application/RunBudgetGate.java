package com.objwww.pr.control.alert.application;

import com.objwww.pr.control.alert.domain.budget.BudgetExhaustedException;
import com.objwww.pr.control.alert.domain.budget.BudgetKind;
import com.objwww.pr.control.alert.domain.budget.BudgetProbe;
import com.objwww.pr.control.alert.domain.budget.ReservationKey;
import com.objwww.pr.control.alert.domain.budget.RunBudgetLedger;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Predicate;
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
 *
 * <p>EX-A1（P1-02）补三维，所有者地位不变（A0 §4.1）：
 * <ul>
 *   <li><b>多维一次准入</b>：{@code call(Map, base, remote, usageFn, releaseOn)}——
 *       各维按给定序 reserve，任一维拒 → 已预留维逐个 {@code release} 显式撤销
 *       （账面零残留）→ 抛 {@link BudgetExhaustedException}；</li>
 *   <li><b>未发出撤销</b>：{@code releaseOn} 命中的异常（记录写失败/控制面拒绝=
 *       确证未发出）→ 全维 {@code release} 全额退款；未命中（发送后/未知）→
 *       {@code provisional} 保守占用（不免费重发，P1-03）；</li>
 *   <li><b>usage 缺失不猜零</b>：{@link Usage#unmatched()} → {@code markUnmatched}
 *       落账待对账；run 开局限额 {@link #openRun}。</li>
 * </ul>
 */
public class RunBudgetGate {

    /** 单维实扣值：units=null = 服务端 usage 缺失（UNKNOWN 对账面，不猜零） */
    public record Usage(Long units) {

        public static Usage of(long units) {
            return new Usage(units);
        }

        public static Usage unmatched() {
            return new Usage(null);
        }
    }

    private final RunBudgetLedger ledger;

    public RunBudgetGate(RunBudgetLedger ledger) {
        this.ledger = Objects.requireNonNull(ledger);
    }

    /** run 开局限额（execute 入口一次，幂等 upsert；四维限额行可见性面） */
    public void openRun(UUID runId, Map<BudgetKind, Long> limits) {
        Objects.requireNonNull(runId, "runId");
        Objects.requireNonNull(limits, "limits");
        limits.forEach((kind, limit) -> ledger.ensureLimit(runId, kind, limit));
    }

    /**
     * 有预算的远程调用（冻结签名，A0 §4.1）：单维便捷形，委托多维主路——
     * 异常一律 {@code provisional}（最保守缺省）。
     */
    public <T> T call(ReservationKey key, long estimateUnits, Supplier<T> remote,
            ToLongFunction<T> usageExtractor) {
        if (estimateUnits < 1) {
            throw new IllegalArgumentException("预算估值必须 ≥1，实际: " + estimateUnits);
        }
        return call(Map.of(key.budgetKind(), estimateUnits), key, remote,
                result -> Map.of(key.budgetKind(), Usage.of(usageExtractor.applyAsLong(result))),
                e -> false);
    }

    /**
     * 多维一次准入的远程调用（EX-A1 主路）。
     *
     * @param estimates      各维保守估值（准入序 = 迭代序，建议 LinkedHashMap）
     * @param base           幂等业务键模板（各维键 = 同五元组换 budgetKind）
     * @param remote         远程调用体（网络期无锁；调用记录写失败等未发出异常从
     *                       此段抛出）
     * @param usageExtractor 各维服务端权威 usage（缺失维给 {@link Usage#unmatched()}）
     * @param releaseOn      "确证未发出"异常判别：命中 → 全维 release 退款；
     *                       不命中 → provisional 保守占用
     */
    public <T> T call(Map<BudgetKind, Long> estimates, ReservationKey base,
            Supplier<T> remote, Function<T, Map<BudgetKind, Usage>> usageExtractor,
            Predicate<RuntimeException> releaseOn) {
        Objects.requireNonNull(base, "base");
        if (estimates == null || estimates.isEmpty()) {
            throw new IllegalArgumentException("多维准入至少一维");
        }
        List<BudgetKind> kinds = new ArrayList<>();
        Map<BudgetKind, ReservationKey> keys = new LinkedHashMap<>();
        estimates.forEach((kind, units) -> {
            if (units == null || units < 1) {
                throw new IllegalArgumentException("预算估值必须 ≥1，实际: " + kind + "=" + units);
            }
            kinds.add(kind);
            keys.put(kind, new ReservationKey(base.runId(), base.taskId(),
                    base.attemptId(), base.callSeq(), kind));
        });

        // ① 全维准入：任一维拒 → 已预留维显式撤销（零残留）→ 抛耗尽
        for (BudgetKind kind : kinds) {
            BudgetProbe probe = ledger.reserve(keys.get(kind), estimates.get(kind));
            if (!probe.allowed()) {
                releaseAdmitted(kinds, kind, keys);
                throw BudgetExhaustedException.ofKind(kind,
                        probe.consumed(), probe.remaining(), estimates.get(kind));
            }
        }

        // ② 远程段（无锁）：未发出撤销 vs 保守占用
        T result;
        try {
            result = remote.get();
        } catch (RuntimeException e) {
            if (releaseOn.test(e)) {
                releaseAdmitted(kinds, null, keys);
            } else {
                kinds.forEach(kind -> ledger.provisional(keys.get(kind)));
            }
            throw e;
        }

        // ③ 按维实扣 / 缺 usage 落 UNMATCHED（不猜零）
        Map<BudgetKind, Usage> usage = Objects.requireNonNull(
                usageExtractor.apply(result), "usageExtractor 不得返回 null");
        for (BudgetKind kind : kinds) {
            Usage perDim = usage.get(kind);
            if (perDim == null || perDim.units() == null) {
                ledger.markUnmatched(keys.get(kind));
            } else {
                ledger.commit(keys.get(kind), perDim.units());
            }
        }
        return result;
    }

    /** 撤销已预留各维：admission 拒绝时 stopAt 之前（不含），releaseOn 命中时全部 */
    private void releaseAdmitted(List<BudgetKind> kinds, BudgetKind stopAt,
            Map<BudgetKind, ReservationKey> keys) {
        for (BudgetKind kind : kinds) {
            if (kind.equals(stopAt)) {
                return;
            }
            ledger.release(keys.get(kind));
        }
    }
}
