package com.objwww.pr.control.alert.domain.statemachine;

import com.objwww.pr.control.alert.domain.model.RcaRunState;

/**
 * rca_run 六态机（V7 ck_rca_run_state 对齐）：
 * QUEUED→{RUNNING, CANCELLED, SUCCEEDED, FAILED}；
 * RUNNING→{SUCCEEDED, FAILED, CANCELLED, SUPERSEDED}（SUPERSEDED = rerun 收尾时未完成被新 run 取代）；
 * QUEUED→{SUCCEEDED, FAILED} = finishTask 退化路径（G0-06）：收尾算法（§6.7）接受任意活跃态
 * （QUEUED/RUNNING）进入终态——未经 markRunRunning 的直接 finishTask 调用路径 run 仍为 QUEUED；
 * 四终态无出边。
 */
public final class RcaRunStateMachine {

    private static final TransitionTable<RcaRunState> TABLE =
            TransitionTable.<RcaRunState>forEnum(RcaRunState.class)
                    .allow(RcaRunState.QUEUED, RcaRunState.RUNNING, RcaRunState.CANCELLED,
                            RcaRunState.SUPERSEDED, RcaRunState.SUCCEEDED, RcaRunState.FAILED)
                    .allow(RcaRunState.RUNNING, RcaRunState.SUCCEEDED, RcaRunState.FAILED,
                            RcaRunState.CANCELLED, RcaRunState.SUPERSEDED)
                    .build();

    private RcaRunStateMachine() {
    }

    public static boolean allowed(RcaRunState from, RcaRunState to) {
        return TABLE.allowed(from, to);
    }

    public static void requireTransition(RcaRunState from, RcaRunState to) {
        TABLE.requireTransition(from, to);
    }
}
