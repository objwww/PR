package com.objwww.pr.control.alert.domain.dag;

import com.objwww.pr.control.alert.domain.model.RcaTaskState;

import java.util.EnumSet;
import java.util.Set;

/**
 * DAG 推进器视角的任务状态（AM4 §3.1 service/DagPromoter 的输入契约）。
 * BLOCKED 待推进；READY/RUNNING 在途；SUCCEEDED/SKIPPED/FAILED_TERMINAL/DEAD 为终态。
 *
 * <p>本枚举是推进判定的纯逻辑视图；持久层 rca_task 状态经 {@link #fromPersistent}
 * 投影到本视图（M4-01 接缝，穷举 switch 无 default——RcaTaskState 新增取值时
 * 此处编译期红，强制同步映射）。
 */
public enum DagTaskState {
    BLOCKED, READY, RUNNING, SUCCEEDED, SKIPPED, FAILED_TERMINAL, DEAD;

    /** 终态集：OPTIONAL 前置到达任一终态即视为"已了断" */
    public static final Set<DagTaskState> TERMINAL =
            Set.copyOf(EnumSet.of(SUCCEEDED, SKIPPED, FAILED_TERMINAL, DEAD));

    public boolean isTerminal() {
        return TERMINAL.contains(this);
    }

    /**
     * 持久层 11 态 → 推进器视图投影（M4-01 冻结映射）：
     * BLOCKED→BLOCKED；READY→READY；LEASED/RUNNING/RETRY_WAIT→RUNNING（在途即执行中）；
     * DONE→SUCCEEDED；DEAD→DEAD；SKIPPED→SKIPPED；
     * CANCELLED/FAILED_TERMINAL/STALE→FAILED_TERMINAL（三者对推进器而言同为
     * "确定不会再产出结果"，不区分收尾原因）。
     */
    public static DagTaskState fromPersistent(RcaTaskState state) {
        return switch (state) {
            case BLOCKED -> BLOCKED;
            case READY -> READY;
            case LEASED, RUNNING, RETRY_WAIT -> RUNNING;
            case DONE -> SUCCEEDED;
            case DEAD -> DEAD;
            case SKIPPED -> SKIPPED;
            case CANCELLED, FAILED_TERMINAL, STALE -> FAILED_TERMINAL;
        };
    }
}
