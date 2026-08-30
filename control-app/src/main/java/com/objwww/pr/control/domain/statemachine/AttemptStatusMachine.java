package com.objwww.pr.control.domain.statemachine;

import com.objwww.pr.shared.AttemptStatus;
import com.objwww.pr.shared.IllegalTransitionException;

import java.util.EnumMap;
import java.util.Map;
import java.util.Set;

/**
 * AttemptStatus 最小迁移校验：STARTED 单向收敛到五个终态之一，终态不再出迁。
 * STALE = 晚到结果（lease_epoch 过期），只记录不推进（I11）。
 */
public final class AttemptStatusMachine {

    private static final Map<AttemptStatus, Set<AttemptStatus>> TRANSITIONS = new EnumMap<>(AttemptStatus.class);

    static {
        TRANSITIONS.put(AttemptStatus.STARTED, Set.of(
                AttemptStatus.SUCCEEDED, AttemptStatus.FAILED_RETRYABLE,
                AttemptStatus.FAILED_TERMINAL, AttemptStatus.ABANDONED, AttemptStatus.STALE));
        TRANSITIONS.put(AttemptStatus.SUCCEEDED, Set.of());
        TRANSITIONS.put(AttemptStatus.FAILED_RETRYABLE, Set.of());
        TRANSITIONS.put(AttemptStatus.FAILED_TERMINAL, Set.of());
        TRANSITIONS.put(AttemptStatus.ABANDONED, Set.of());
        TRANSITIONS.put(AttemptStatus.STALE, Set.of());
    }

    private AttemptStatusMachine() {
    }

    public static AttemptStatus transition(AttemptStatus from, AttemptStatus to) {
        if (!canTransition(from, to)) {
            throw new IllegalTransitionException(from, to);
        }
        return to;
    }

    public static boolean canTransition(AttemptStatus from, AttemptStatus to) {
        return TRANSITIONS.getOrDefault(from, Set.of()).contains(to);
    }

    public static boolean isTerminal(AttemptStatus status) {
        return TRANSITIONS.getOrDefault(status, Set.of()).isEmpty();
    }
}
