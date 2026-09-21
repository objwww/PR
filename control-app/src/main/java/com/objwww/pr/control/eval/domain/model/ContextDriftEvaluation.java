package com.objwww.pr.control.eval.domain.model;

import java.util.List;
import java.util.Objects;

/**
 * 上下文漂移评测结果值对象（ME-T06/D06 第 7 条；纯数据，L0 零框架依赖）。
 *
 * <p>checks 统一五态（{@link BehaviorCheckStatus}），缺证据不猜通过：摘要缺席
 * （未压缩臂）忠实性面 NOT_APPLICABLE，空摘要忠实性面 FAIL（语义不足被测出，
 * 不得凭 refs 完整和长度短判断有效——CTX-08）；行为面未观测 NOT_ASSESSED。
 * metrics 带分子/分母；deferred 如实标注涉真实模型的指标（任务质量变化 C−B
 * 配对、总成本变化真实发送面统计、位置分桶敏感性、注入攻击成功率）——确定性
 * 子集不出这些数，不冒充。
 */
public record ContextDriftEvaluation(String graderVersion,
                                     String caseId,
                                     ContextDriftInput.ConsumptionFace consumption,
                                     List<BehaviorEvaluation.Check> checks,
                                     List<BehaviorEvaluation.Metric> metrics,
                                     List<String> failureLabels,
                                     List<String> deferred) {

    public ContextDriftEvaluation {
        Objects.requireNonNull(graderVersion, "graderVersion 不得为 null");
        Objects.requireNonNull(caseId, "caseId 不得为 null");
        // consumption 可空 = 无消费观测面
        Objects.requireNonNull(checks, "checks 不得为 null");
        checks = List.copyOf(checks);
        Objects.requireNonNull(metrics, "metrics 不得为 null");
        metrics = List.copyOf(metrics);
        Objects.requireNonNull(failureLabels, "failureLabels 不得为 null");
        failureLabels = List.copyOf(failureLabels);
        Objects.requireNonNull(deferred, "deferred 不得为 null");
        deferred = List.copyOf(deferred);
    }

    /** 本案是否存在 FAIL 检查（整体有效性的 L0 判面：语义 FAIL 不得标整体压缩有效） */
    public boolean anyFail() {
        return checks.stream().anyMatch(c -> c.status() == BehaviorCheckStatus.FAIL);
    }
}
