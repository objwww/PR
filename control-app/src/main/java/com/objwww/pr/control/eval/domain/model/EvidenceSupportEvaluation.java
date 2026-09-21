package com.objwww.pr.control.eval.domain.model;

import java.util.List;
import java.util.Objects;

/**
 * 证据面评测结果值对象（ME-T09/D09 步骤 1；纯数据，L0 零框架依赖）。
 *
 * <p>语义冻结：
 * <ul>
 *   <li>rubricVersion：证据 rubric 版本锚（同报告不同 rubric 版本并存落档，
 *       旧版本行永不改写——EvalRubricRegistry 同律）；</li>
 *   <li>checks 统一五态（{@link BehaviorCheckStatus}）：缺证据落 NOT_ASSESSED
 *       （UNKNOWN 面），无评估对象落 NOT_APPLICABLE，均不冒充 PASS；</li>
 *   <li>failureLabels = FAIL 检查的机器码标签（EVIDENCE_CONTRADICTS_CLAIM/
 *       EVIDENCE_DOES_NOT_SUPPORT/SUMMARY_CLAIM_UNSUPPORTED/
 *       TASK_CONSTRAINT_VIOLATED）。</li>
 * </ul>
 */
public record EvidenceSupportEvaluation(String rubricVersion,
                                        List<BehaviorEvaluation.Check> checks,
                                        List<BehaviorEvaluation.Metric> metrics,
                                        List<String> failureLabels) {

    public EvidenceSupportEvaluation {
        Objects.requireNonNull(rubricVersion, "rubricVersion 不得为 null");
        Objects.requireNonNull(checks, "checks 不得为 null");
        checks = List.copyOf(checks);
        Objects.requireNonNull(metrics, "metrics 不得为 null");
        metrics = List.copyOf(metrics);
        Objects.requireNonNull(failureLabels, "failureLabels 不得为 null");
        failureLabels = List.copyOf(failureLabels);
    }
}
