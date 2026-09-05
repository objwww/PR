package com.objwww.pr.control.alert.domain.dag;

import java.util.EnumSet;
import java.util.Set;

/**
 * DAG 推进器视角的任务状态（AM4 §3.1 service/DagPromoter 的输入契约）。
 * BLOCKED 待推进；READY/RUNNING 在途；SUCCEEDED/SKIPPED/FAILED_TERMINAL/DEAD 为终态。
 *
 * <p>注意：本枚举是推进判定的纯逻辑视图，不等同持久层 rca_task 状态机
 * （既有 RcaTaskState 六态）；AM4 状态全集扩展（BLOCKED/STALE/WAITING_APPROVAL…）
 * 落地时由 statemachine 包对齐映射。
 */
public enum DagTaskState {
    BLOCKED, READY, RUNNING, SUCCEEDED, SKIPPED, FAILED_TERMINAL, DEAD;

    /** 终态集：OPTIONAL 前置到达任一终态即视为"已了断" */
    public static final Set<DagTaskState> TERMINAL =
            Set.copyOf(EnumSet.of(SUCCEEDED, SKIPPED, FAILED_TERMINAL, DEAD));

    public boolean isTerminal() {
        return TERMINAL.contains(this);
    }
}
