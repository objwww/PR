package com.objwww.pr.control.alert.domain.statemachine;

import com.objwww.pr.control.alert.domain.model.RcaRunState;

/**
 * rca_run 9 态机（AM1 六态迁移边不变；AM4 additive 扩边，V12 同步 DB CHECK）：
 * QUEUED→{RUNNING, REPORTING, CANCELLED, SUPERSEDED, SUCCEEDED, FAILED}；
 * RUNNING→{REPORTING（全部任务了断进入组装）, SUCCEEDED, FAILED, PARTIAL, EXPIRED, CANCELLED,
 * SUPERSEDED}（SUPERSEDED = rerun 收尾时未完成被新 run 取代；PARTIAL = 预算/SLA 耗尽部分完成收尾；
 * EXPIRED = deadline 到期强制收尾）；
 * REPORTING→{SUCCEEDED, FAILED, PARTIAL, EXPIRED, CANCELLED, SUPERSEDED}（组装完成或组装期失败/取消）；
 * QUEUED→{SUCCEEDED, FAILED} = finishTask 退化路径（G0-06）：收尾算法（§6.7）接受任意活跃态
 * 进入终态——未经 markRunRunning 的直接 finishTask 调用路径 run 仍为 QUEUED；
 * QUEUED→REPORTING 为同款退化路径的 AM4 形态（无任务执行直接进入组装）；
 * SUCCEEDED/FAILED/CANCELLED/SUPERSEDED/PARTIAL/EXPIRED 六终态无出边。
 */
public final class RcaRunStateMachine {

    private static final TransitionTable<RcaRunState> TABLE =
            TransitionTable.<RcaRunState>forEnum(RcaRunState.class)
                    .allow(RcaRunState.QUEUED, RcaRunState.RUNNING, RcaRunState.REPORTING,
                            RcaRunState.CANCELLED, RcaRunState.SUPERSEDED,
                            RcaRunState.SUCCEEDED, RcaRunState.FAILED)
                    .allow(RcaRunState.RUNNING, RcaRunState.REPORTING, RcaRunState.SUCCEEDED,
                            RcaRunState.FAILED, RcaRunState.PARTIAL, RcaRunState.EXPIRED,
                            RcaRunState.CANCELLED, RcaRunState.SUPERSEDED)
                    .allow(RcaRunState.REPORTING, RcaRunState.SUCCEEDED, RcaRunState.FAILED,
                            RcaRunState.PARTIAL, RcaRunState.EXPIRED, RcaRunState.CANCELLED,
                            RcaRunState.SUPERSEDED)
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
