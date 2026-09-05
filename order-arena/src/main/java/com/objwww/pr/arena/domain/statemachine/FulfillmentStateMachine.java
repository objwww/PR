package com.objwww.pr.arena.domain.statemachine;

import com.objwww.pr.arena.domain.model.FulfillmentState;

/**
 * Fulfillment 机（AM2 v3.0 §3.1）：CONFIRMING→{CONFIRMED, NO_ROOM, CANCELLED}；
 * NO_ROOM→CANCELLED（无房必走向废单；补货重试不在 AM2 范围）；
 * CONFIRMED/CANCELLED 终态。
 */
public final class FulfillmentStateMachine {

    private static final TransitionTable<FulfillmentState> TABLE =
            TransitionTable.<FulfillmentState>forEnum(FulfillmentState.class)
                    .allow(FulfillmentState.CONFIRMING, FulfillmentState.CONFIRMED,
                            FulfillmentState.NO_ROOM, FulfillmentState.CANCELLED)
                    .allow(FulfillmentState.NO_ROOM, FulfillmentState.CANCELLED)
                    .build();

    private FulfillmentStateMachine() {
    }

    public static boolean allowed(FulfillmentState from, FulfillmentState to) {
        return TABLE.allowed(from, to);
    }

    public static void requireTransition(FulfillmentState from, FulfillmentState to) {
        TABLE.requireTransition(from, to);
    }

    public static TransitionTable<FulfillmentState> table() {
        return TABLE;
    }
}
