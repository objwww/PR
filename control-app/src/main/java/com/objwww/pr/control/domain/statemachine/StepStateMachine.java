package com.objwww.pr.control.domain.statemachine;

import com.objwww.pr.shared.IllegalTransitionException;
import com.objwww.pr.shared.StepState;

import java.util.EnumMap;
import java.util.Map;
import java.util.Set;

/**
 * StepState 最小迁移校验。主链路 READY→RUNNING→SUCCEEDED/FAILED；
 * WAITING 可回流 READY（等待解除后重新领取）；终态不再出迁。
 */
public final class StepStateMachine {

    private static final Map<StepState, Set<StepState>> TRANSITIONS = new EnumMap<>(StepState.class);

    static {
        TRANSITIONS.put(StepState.READY, Set.of(
                StepState.RUNNING, StepState.WAITING, StepState.CANCELLED, StepState.SUPERSEDED));
        TRANSITIONS.put(StepState.RUNNING, Set.of(
                StepState.SUCCEEDED, StepState.FAILED, StepState.WAITING,
                StepState.CANCELLED, StepState.SUPERSEDED));
        TRANSITIONS.put(StepState.WAITING, Set.of(
                StepState.READY, StepState.CANCELLED, StepState.SUPERSEDED));
        // 终态：无出边
        TRANSITIONS.put(StepState.SUCCEEDED, Set.of());
        TRANSITIONS.put(StepState.FAILED, Set.of());
        TRANSITIONS.put(StepState.CANCELLED, Set.of());
        TRANSITIONS.put(StepState.SUPERSEDED, Set.of());
    }

    private StepStateMachine() {
    }

    public static StepState transition(StepState from, StepState to) {
        if (!canTransition(from, to)) {
            throw new IllegalTransitionException(from, to);
        }
        return to;
    }

    public static boolean canTransition(StepState from, StepState to) {
        return TRANSITIONS.getOrDefault(from, Set.of()).contains(to);
    }

    public static boolean isTerminal(StepState state) {
        return TRANSITIONS.getOrDefault(state, Set.of()).isEmpty();
    }
}
