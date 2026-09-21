package com.objwww.pr.control.eval.domain.model;

import java.util.List;
import java.util.Objects;

/**
 * 死循环评测结果值对象（ME-T05/D05 第 7 条；纯数据，L0 零框架依赖）。
 *
 * <p>观测记录四件：stopReason（LOOP_NO_PROGRESS = 循环检出停 / BUDGET_EXHAUSTED =
 * 预算兜底停，<b>不冒充</b>循环检出 / COMPLETED = 正常完成 / null = 无可评轨迹）、
 * firstNoProgressEventIndex（导致停止的无进展窗口起点=首次无进展事件）、
 * detectionEventIndex（检出事件，null=未检出）、postStopNewActions（停止被接受后
 * 新发起的工具/模型动作数，目标 0；在途晚到结果单列 postStopInFlightLate 核验，
 * 不算新动作）。
 *
 * <p>成本观测：physicalCallsFromOnset（loop_onset 至终态的物理调用数）；
 * tokensFromOnset/secondsFromOnset 可空——成本/时钟数据缺失如实 null 不出数。
 * checks 统一五态（{@link BehaviorCheckStatus}），缺证据不猜通过；metrics 带
 * 分子/分母。
 */
public record LoopEvaluation(String graderVersion,
                             String caseId,
                             boolean loopCase,
                             Integer loopOnsetEventIndex,
                             Integer firstNoProgressEventIndex,
                             Integer detectionEventIndex,
                             String stopReason,
                             int postStopNewActions,
                             int postStopInFlightLate,
                             long physicalCallsFromOnset,
                             Long tokensFromOnset,
                             Long secondsFromOnset,
                             List<BehaviorEvaluation.Check> checks,
                             List<BehaviorEvaluation.Metric> metrics,
                             List<String> failureLabels) {

    public static final String STOP_LOOP_NO_PROGRESS = "LOOP_NO_PROGRESS";
    public static final String STOP_BUDGET_EXHAUSTED = "BUDGET_EXHAUSTED";
    public static final String STOP_COMPLETED = "COMPLETED";

    public LoopEvaluation {
        Objects.requireNonNull(graderVersion, "graderVersion 不得为 null");
        Objects.requireNonNull(caseId, "caseId 不得为 null");
        Objects.requireNonNull(checks, "checks 不得为 null");
        checks = List.copyOf(checks);
        Objects.requireNonNull(metrics, "metrics 不得为 null");
        metrics = List.copyOf(metrics);
        Objects.requireNonNull(failureLabels, "failureLabels 不得为 null");
        failureLabels = List.copyOf(failureLabels);
    }

    /** 本案是否检出循环（detectionEventIndex 非空） */
    public boolean detected() {
        return detectionEventIndex != null;
    }

    /** 本案是否在硬预算/deadline 内进入允许终态（安全停止） */
    public boolean safelyStopped() {
        return STOP_LOOP_NO_PROGRESS.equals(stopReason) || STOP_BUDGET_EXHAUSTED.equals(stopReason);
    }

    /** 正常对照是否未被误伤地完成（正常任务成功） */
    public boolean normalCompleted() {
        return !loopCase && !detected() && STOP_COMPLETED.equals(stopReason);
    }
}
