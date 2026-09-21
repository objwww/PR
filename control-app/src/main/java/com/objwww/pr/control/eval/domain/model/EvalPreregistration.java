package com.objwww.pr.control.eval.domain.model;

import java.util.List;
import java.util.Objects;

/**
 * 实验预登记冻结记录（ME-T09/D09 步骤 5；纯数据，L0 零框架依赖）。
 *
 * <p>跑批之前冻结：主要质量指标、关键分层、非劣容忍度、成本预算（外加位置
 * 偏差容忍值与最小簇数——JUDGE-04/STAT-01 的判定锚同源于此，避免跑完再挑
 * 最有利指标/容忍值）。值对象 + 版本：任何字段变更 = 新
 * {@link #preregistrationVersion} 新记录，旧版本永不改写；多模型反复调参需
 * 独立 HOLDOUT，不能无限复用同一验证集（登记面声明，执行面归批次编排）。
 */
public record EvalPreregistration(String preregistrationVersion,
                                  String primaryMetric,
                                  List<String> keyStrata,
                                  double nonInferiorityMargin,
                                  long costBudgetMicros,
                                  double positionBiasTolerance,
                                  int minClusters) {

    public EvalPreregistration {
        Objects.requireNonNull(preregistrationVersion, "preregistrationVersion 不得为 null");
        if (preregistrationVersion.isBlank()) {
            throw new IllegalArgumentException("preregistrationVersion 不得为 blank（版本锚）");
        }
        Objects.requireNonNull(primaryMetric, "primaryMetric 不得为 null");
        if (primaryMetric.isBlank()) {
            throw new IllegalArgumentException("primaryMetric 不得为 blank（主要指标必登记）");
        }
        Objects.requireNonNull(keyStrata, "keyStrata 不得为 null");
        keyStrata = List.copyOf(keyStrata);
        if (keyStrata.isEmpty()) {
            throw new IllegalArgumentException("keyStrata 不得为空（关键分层必登记）");
        }
        if (keyStrata.stream().anyMatch(s -> s == null || s.isBlank())) {
            throw new IllegalArgumentException("keyStrata 元素不得为 blank");
        }
        if (nonInferiorityMargin < 0 || nonInferiorityMargin > 1) {
            throw new IllegalArgumentException(
                    "nonInferiorityMargin 须在 [0,1]: " + nonInferiorityMargin);
        }
        if (costBudgetMicros < 0) {
            throw new IllegalArgumentException("costBudgetMicros 不得为负: " + costBudgetMicros);
        }
        if (positionBiasTolerance < 0 || positionBiasTolerance >= 1) {
            throw new IllegalArgumentException(
                    "positionBiasTolerance 须在 [0,1): " + positionBiasTolerance);
        }
        if (minClusters < 1) {
            throw new IllegalArgumentException("minClusters 须 ≥ 1: " + minClusters);
        }
    }
}
