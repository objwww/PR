package com.objwww.pr.control.alert.domain.statemachine;

import com.objwww.pr.control.alert.domain.model.RcaTaskState;

/**
 * rca_task 六态机（V1 work_item 同构）：
 * READY→{LEASED, CANCELLED, DEAD}；
 * LEASED→{READY（主动释放/租约过期回收）, RETRY_WAIT, DONE, CANCELLED, DEAD}；
 * RETRY_WAIT→{READY（退避结束）, DEAD（attempt 耗尽）, CANCELLED}；
 * DONE/CANCELLED/DEAD 终态。
 */
public final class RcaTaskStateMachine {

    private static final TransitionTable<RcaTaskState> TABLE =
            TransitionTable.<RcaTaskState>forEnum(RcaTaskState.class)
                    .allow(RcaTaskState.READY, RcaTaskState.LEASED, RcaTaskState.CANCELLED,
                            RcaTaskState.DEAD)
                    .allow(RcaTaskState.LEASED, RcaTaskState.READY, RcaTaskState.RETRY_WAIT,
                            RcaTaskState.DONE, RcaTaskState.CANCELLED, RcaTaskState.DEAD)
                    .allow(RcaTaskState.RETRY_WAIT, RcaTaskState.READY, RcaTaskState.DEAD,
                            RcaTaskState.CANCELLED)
                    .build();

    private RcaTaskStateMachine() {
    }

    public static boolean allowed(RcaTaskState from, RcaTaskState to) {
        return TABLE.allowed(from, to);
    }

    public static void requireTransition(RcaTaskState from, RcaTaskState to) {
        TABLE.requireTransition(from, to);
    }
}
