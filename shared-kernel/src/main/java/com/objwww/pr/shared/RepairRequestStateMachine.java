package com.objwww.pr.shared;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;

/** M2 v1.1 §4.1 七态修复单的唯一合法迁移表。 */
public final class RepairRequestStateMachine {

    private static final Map<RepairRequestState, EnumSet<RepairRequestState>> ALLOWED =
            new EnumMap<>(RepairRequestState.class);

    static {
        ALLOWED.put(RepairRequestState.PENDING, EnumSet.of(RepairRequestState.APPROVED,
                RepairRequestState.DISPATCHED, RepairRequestState.RETRY_WAIT,
                RepairRequestState.FAILED_TERMINAL, RepairRequestState.EXPIRED));
        ALLOWED.put(RepairRequestState.APPROVED, EnumSet.of(RepairRequestState.DISPATCHED,
                RepairRequestState.RETRY_WAIT, RepairRequestState.FAILED_TERMINAL,
                RepairRequestState.EXPIRED));
        ALLOWED.put(RepairRequestState.DISPATCHED, EnumSet.of(RepairRequestState.REPAIRED,
                RepairRequestState.FAILED_TERMINAL, RepairRequestState.EXPIRED));
        ALLOWED.put(RepairRequestState.RETRY_WAIT, EnumSet.of(RepairRequestState.DISPATCHED,
                RepairRequestState.RETRY_WAIT, RepairRequestState.FAILED_TERMINAL,
                RepairRequestState.EXPIRED));
        ALLOWED.put(RepairRequestState.REPAIRED, EnumSet.noneOf(RepairRequestState.class));
        ALLOWED.put(RepairRequestState.FAILED_TERMINAL, EnumSet.noneOf(RepairRequestState.class));
        ALLOWED.put(RepairRequestState.EXPIRED, EnumSet.noneOf(RepairRequestState.class));
    }

    private RepairRequestStateMachine() {
    }

    public static boolean canTransition(RepairRequestState from, RepairRequestState to) {
        return from != null && to != null && ALLOWED.get(from).contains(to);
    }

    public static RepairRequestState transition(RepairRequestState from, RepairRequestState to) {
        if (!canTransition(from, to)) {
            throw new IllegalStateException("非法 repair_request 状态迁移: " + from + " -> " + to);
        }
        return to;
    }
}
