package com.objwww.pr.control.domain.statemachine;

import com.objwww.pr.shared.IllegalTransitionException;
import com.objwww.pr.shared.RunState;

import java.util.EnumMap;
import java.util.Map;
import java.util.Set;

/**
 * RunState 最小迁移校验。按 V1 ck_review_run_state 与 M0 主链路收敛；
 * 终态 COMPLETED/COMPLETED_WITH_WARNINGS/FAILED/CANCELLED/SUPERSEDED 不再出迁。
 * 任何非终态 → FAILED/CANCELLED/SUPERSEDED 均合法（失败、人工取消、换届）。
 */
public final class RunStateMachine {

    private static final Map<RunState, Set<RunState>> TRANSITIONS = new EnumMap<>(RunState.class);

    static {
        TRANSITIONS.put(RunState.CREATED, Set.of(
                RunState.SNAPSHOTTING, RunState.REVIEWING,
                RunState.FAILED, RunState.CANCELLED, RunState.SUPERSEDED));
        TRANSITIONS.put(RunState.SNAPSHOTTING, Set.of(
                RunState.REVIEWING, RunState.FAILED, RunState.CANCELLED, RunState.SUPERSEDED));
        TRANSITIONS.put(RunState.REVIEWING, Set.of(
                RunState.REVIEW_COMPLETE, RunState.FAILED, RunState.CANCELLED, RunState.SUPERSEDED));
        TRANSITIONS.put(RunState.REVIEW_COMPLETE, Set.of(
                RunState.READY_TO_PUBLISH, RunState.COMPLETED, RunState.COMPLETED_WITH_WARNINGS,
                RunState.FAILED, RunState.CANCELLED, RunState.SUPERSEDED));
        // M5 预留段
        TRANSITIONS.put(RunState.PATCH_PROPOSED, Set.of(
                RunState.WAITING_APPROVAL, RunState.VERIFYING,
                RunState.FAILED, RunState.CANCELLED, RunState.SUPERSEDED));
        TRANSITIONS.put(RunState.WAITING_APPROVAL, Set.of(
                RunState.VERIFYING, RunState.FAILED, RunState.CANCELLED, RunState.SUPERSEDED));
        TRANSITIONS.put(RunState.VERIFYING, Set.of(
                RunState.READY_TO_PUBLISH, RunState.FAILED, RunState.CANCELLED, RunState.SUPERSEDED));
        TRANSITIONS.put(RunState.READY_TO_PUBLISH, Set.of(
                RunState.PUBLISHING, RunState.FAILED, RunState.CANCELLED, RunState.SUPERSEDED));
        TRANSITIONS.put(RunState.PUBLISHING, Set.of(
                RunState.COMPLETED, RunState.COMPLETED_WITH_WARNINGS,
                RunState.FAILED, RunState.CANCELLED, RunState.SUPERSEDED));
        // 终态：无出边
        TRANSITIONS.put(RunState.COMPLETED, Set.of());
        TRANSITIONS.put(RunState.COMPLETED_WITH_WARNINGS, Set.of());
        TRANSITIONS.put(RunState.FAILED, Set.of());
        TRANSITIONS.put(RunState.CANCELLED, Set.of());
        TRANSITIONS.put(RunState.SUPERSEDED, Set.of());
    }

    private RunStateMachine() {
    }

    public static RunState transition(RunState from, RunState to) {
        if (!canTransition(from, to)) {
            throw new IllegalTransitionException(from, to);
        }
        return to;
    }

    public static boolean canTransition(RunState from, RunState to) {
        return TRANSITIONS.getOrDefault(from, Set.of()).contains(to);
    }

    public static boolean isTerminal(RunState state) {
        return TRANSITIONS.getOrDefault(state, Set.of()).isEmpty();
    }
}
