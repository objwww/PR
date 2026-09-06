package com.objwww.pr.control.alert.domain.budget;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Run 级预算账本端口（AM4 M4-08，V13 落库）。实现方（PG）每个方法自含行锁短事务——
 * <b>调用方拿到返回值时锁已释放</b>，网络调用期间绝不持有 SELECT FOR UPDATE。
 *
 * <p>三段式：{@link #reserve}（原子预增+幂等）→ {@link #commit}（服务端 usage 实扣平账）/
 * {@link #release}（发送前取消全额退款）/ {@link #provisional}（发送后取消，不动账面等对账）
 * / {@link #markUnmatched}（usage 缺失，不伪造零退款）。
 */
public interface RunBudgetLedger {

    /** 建/改 (run, kind) 限额行（run 开局时一次性设定；幂等） */
    void ensureLimit(UUID runId, BudgetKind kind, long limitUnits);

    /**
     * 原子预增：单语句读-判-扣-写，超预算即拒（0 行 = 不预留不落账）。
     * 同幂等键重试：返回既有预留的账面（不重复扣、不重复落 entry）。
     */
    BudgetProbe reserve(ReservationKey key, long units);

    /** 调用完成按服务端 usage 实扣平账：consumed += (actual - reserved)，可软超限（真实消耗不可撤） */
    void commit(ReservationKey key, long actualUnits);

    /** 发送前取消：全额退款，entry → RELEASED */
    void release(ReservationKey key);

    /** 发送后取消：账面不动（不按 input floor 平账），entry → PROVISIONAL 等对账 */
    void provisional(ReservationKey key);

    /** usage 缺失：entry → UNMATCHED，账面不动（不伪造零） */
    void markUnmatched(ReservationKey key);

    /** 崩溃对账面：滞留超龄的 RESERVED/PROVISIONAL 预留（M4-37 Reconciler 的输入） */
    List<ReservationKey> findStaleReservations(Instant olderThan);
}
