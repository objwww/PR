package com.objwww.pr.control.alert.domain.statemachine;

import com.objwww.pr.control.alert.domain.model.RcaTaskState;

/**
 * rca_task 11 态机（AM1 六态迁移边不变；AM4 additive 扩边，V12 同步 DB CHECK）：
 * READY→{LEASED, CANCELLED, DEAD}；
 * LEASED→{READY（主动释放/租约过期回收）, RUNNING（进入执行）, RETRY_WAIT, DONE, CANCELLED, DEAD,
 * STALE（M4-07 generation fence：run 已出活跃集，结果作废）}；
 * RUNNING→{READY（执行失败回退重排）, RETRY_WAIT（可重试失败退避）, DONE, CANCELLED, DEAD, STALE（同上）}；
 * BLOCKED→{READY（前置了断放行）, SKIPPED（OPTIONAL 前置失败跳过）, CANCELLED}；
 * RETRY_WAIT→{READY（退避结束）, DEAD（attempt 耗尽）, CANCELLED}；
 * DONE/CANCELLED/DEAD/SKIPPED/FAILED_TERMINAL/STALE 六终态无出边
 * （FAILED_TERMINAL 确定性失败不重试；STALE generation 过期由新 run 新任务承接，不复活——
 * 未领取态（READY/RETRY_WAIT/BLOCKED）不入 STALE：claim SQL 栅栏隔离其领取，回收面只处理 LEASED）。
 */
public final class RcaTaskStateMachine {

    private static final TransitionTable<RcaTaskState> TABLE =
            TransitionTable.<RcaTaskState>forEnum(RcaTaskState.class)
                    .allow(RcaTaskState.READY, RcaTaskState.LEASED, RcaTaskState.CANCELLED,
                            RcaTaskState.DEAD)
                    .allow(RcaTaskState.LEASED, RcaTaskState.READY, RcaTaskState.RUNNING,
                            RcaTaskState.RETRY_WAIT, RcaTaskState.DONE, RcaTaskState.CANCELLED,
                            RcaTaskState.DEAD, RcaTaskState.STALE)
                    .allow(RcaTaskState.RUNNING, RcaTaskState.READY, RcaTaskState.RETRY_WAIT,
                            RcaTaskState.DONE, RcaTaskState.CANCELLED, RcaTaskState.DEAD,
                            RcaTaskState.STALE)
                    .allow(RcaTaskState.BLOCKED, RcaTaskState.READY, RcaTaskState.SKIPPED,
                            RcaTaskState.CANCELLED)
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
