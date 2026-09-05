package com.objwww.pr.arena.domain.statemachine;

import com.objwww.pr.arena.domain.model.RefundState;

/**
 * Refund 机（M2-11）：REQUESTED→{APPROVED, REJECTED, CANCELLED}；
 * APPROVED→{REFUNDING, CANCELLED}；REFUNDING→{SUCCEEDED, FAILED}；
 * FAILED→REFUNDING（渠道失败重试，补偿 worker 驱动）；
 * SUCCEEDED/REJECTED/CANCELLED 终态。
 */
public final class RefundStateMachine {

    private static final TransitionTable<RefundState> TABLE =
            TransitionTable.<RefundState>forEnum(RefundState.class)
                    .allow(RefundState.REQUESTED, RefundState.APPROVED, RefundState.REJECTED,
                            RefundState.CANCELLED)
                    .allow(RefundState.APPROVED, RefundState.REFUNDING, RefundState.CANCELLED)
                    .allow(RefundState.REFUNDING, RefundState.SUCCEEDED, RefundState.FAILED)
                    .allow(RefundState.FAILED, RefundState.REFUNDING)
                    .build();

    private RefundStateMachine() {
    }

    public static boolean allowed(RefundState from, RefundState to) {
        return TABLE.allowed(from, to);
    }

    public static void requireTransition(RefundState from, RefundState to) {
        TABLE.requireTransition(from, to);
    }

    public static TransitionTable<RefundState> table() {
        return TABLE;
    }
}
