package com.objwww.pr.control.alert.domain.mutation;

import com.objwww.pr.shared.IllegalTransitionException;

import java.util.Map;
import java.util.Set;

/**
 * Operation 状态机合法迁移表（设计基线 §2.11，PB-B1 域钉）：
 *
 * <pre>
 * PREPARED    → DISPATCHED | CANCELLED_BEFORE_DISPATCH
 * DISPATCHED  → ACKNOWLEDGED | UNKNOWN
 * ACKNOWLEDGED→ VERIFIED | UNKNOWN
 * UNKNOWN     → RECONCILING            （中间态必须被显式穿越，不跳越——§3.3 同律）
 * RECONCILING → VERIFIED | RETRYABLE | ESCALATED | FAILED_CONFIRMED
 * RETRYABLE   → DISPATCHED             （重派，锁保持）
 * VERIFIED    → COMPLETED
 * 终态（COMPLETED/FAILED_CONFIRMED/ESCALATED/CANCELLED_BEFORE_DISPATCH）→ 无出边
 * </pre>
 *
 * <p>跃迁合法性是域层纯函数：消费模板（B4 §2.8）与 reconcile 驱动（B5）共用同一张表，
 * DB 侧 CAS 只对"合法边"发起。任何越边尝试抛 {@link IllegalTransitionException}。
 */
public final class OperationStateMachine {

    private static final Map<OperationStatus, Set<OperationStatus>> EDGES = Map.ofEntries(
            Map.entry(OperationStatus.PREPARED,
                    Set.of(OperationStatus.DISPATCHED,
                            OperationStatus.CANCELLED_BEFORE_DISPATCH)),
            Map.entry(OperationStatus.DISPATCHED,
                    Set.of(OperationStatus.ACKNOWLEDGED, OperationStatus.UNKNOWN)),
            Map.entry(OperationStatus.ACKNOWLEDGED,
                    Set.of(OperationStatus.VERIFIED, OperationStatus.UNKNOWN)),
            Map.entry(OperationStatus.UNKNOWN, Set.of(OperationStatus.RECONCILING)),
            Map.entry(OperationStatus.RECONCILING,
                    Set.of(OperationStatus.VERIFIED, OperationStatus.RETRYABLE,
                            OperationStatus.ESCALATED, OperationStatus.FAILED_CONFIRMED)),
            Map.entry(OperationStatus.RETRYABLE, Set.of(OperationStatus.DISPATCHED)),
            Map.entry(OperationStatus.VERIFIED, Set.of(OperationStatus.COMPLETED)),
            Map.entry(OperationStatus.COMPLETED, Set.of()),
            Map.entry(OperationStatus.FAILED_CONFIRMED, Set.of()),
            Map.entry(OperationStatus.ESCALATED, Set.of()),
            Map.entry(OperationStatus.CANCELLED_BEFORE_DISPATCH, Set.of()));

    private OperationStateMachine() {
    }

    /** 迁移合法性判定（纯函数，不抛异常——消费模板先问后动） */
    public static boolean canTransition(OperationStatus from, OperationStatus to) {
        return EDGES.getOrDefault(from, Set.of()).contains(to);
    }

    /** 跃迁强制校验：非法边抛 {@link IllegalTransitionException}（禁静默） */
    public static void checkTransition(OperationStatus from, OperationStatus to) {
        if (!canTransition(from, to)) {
            throw new IllegalTransitionException(
                    "Operation 非法迁移（禁越边/禁终态复活）: " + from + " → " + to);
        }
    }

    public static boolean isTerminal(OperationStatus status) {
        return status.isTerminal();
    }
}
