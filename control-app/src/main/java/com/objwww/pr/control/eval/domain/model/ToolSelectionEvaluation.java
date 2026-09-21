package com.objwww.pr.control.eval.domain.model;

import java.util.List;
import java.util.Objects;

/**
 * 工具选择对照评测结果值对象（ME-T08/D08；纯数据，L0 零框架依赖）。
 *
 * <p>语义冻结：
 * <ul>
 *   <li>checks 统一五态（{@link BehaviorCheckStatus}），缺证据不猜通过——观测缺失
 *       落 NOT_ASSESSED、分区缺席落 NOT_APPLICABLE，均不冒充 PASS/FAIL；</li>
 *   <li>failureLabels = FAIL 检查的机器码标签（PARAM_SEMANTIC_MISMATCH/
 *       EMPTY_SET_BLIND_RETRY/EMPTY_SET_DISGUISED_AS_FAILURE/
 *       DEGRADED_OUTCOME_HIDDEN/REPLAY_DEGRADED_TO_LIVE/
 *       TOOL_CALL_OUTSIDE_VALID_PATHS/TERMINAL_STATE_TEXT_OVERRIDE/
 *       CROSS_TRIAL_CONTAMINATION/TOOL_CHOICE_OUTSIDE_EQUIVALENTS/
 *       UNJUSTIFIED_NO_CALL）；</li>
 *   <li>attribution = 试次排除性归因（{@link TrialAttribution}；null = 正常计入
 *       模型评分）。非 null 试次保留在批次完整性统计但不得直接定模型败
 *       （TOOL-05 覆盖缺口/TOOL-07 环境无效/TOOL-08 跨 trial 污染）。</li>
 * </ul>
 */
public record ToolSelectionEvaluation(String graderVersion,
                                      List<BehaviorEvaluation.Check> checks,
                                      List<BehaviorEvaluation.Metric> metrics,
                                      List<String> failureLabels,
                                      TrialAttribution attribution) {

    public ToolSelectionEvaluation {
        Objects.requireNonNull(graderVersion, "graderVersion 不得为 null");
        Objects.requireNonNull(checks, "checks 不得为 null");
        checks = List.copyOf(checks);
        Objects.requireNonNull(metrics, "metrics 不得为 null");
        metrics = List.copyOf(metrics);
        Objects.requireNonNull(failureLabels, "failureLabels 不得为 null");
        failureLabels = List.copyOf(failureLabels);
    }
}
