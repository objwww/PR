package com.objwww.pr.control.eval.domain.service;

import com.objwww.pr.control.eval.domain.model.TrialAttribution;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 试次归因聚合纯函数（ME-T09/D09 步骤 6；L0 不触网——ME-T08 六值词表
 * {@link TrialAttribution} 进评分投影的出数面）。
 *
 * <p>总计划分母不消失：plannedTrials 恒为总锚；排除性归因（HARNESS_ERROR/
 * ENVIRONMENT_ERROR/REPLAY_COVERAGE_GAP/GRADER_ERROR/NOT_ASSESSED）的试次
 * 不计入模型能力评分，但保留在批次完整性统计。两面分别报告：
 * <ul>
 *   <li>modelSuccessRate = 模型成功 /（模型成功 + AGENT_FAILURE）——只含可归
 *       模型的试次，分母 0 如实 NOT_APPLICABLE；</li>
 *   <li>validCoverage =（模型成功 + AGENT_FAILURE）/ plannedTrials——实验有效
 *       覆盖，环境/设施/评分器问题摊薄的是覆盖而非成功率。</li>
 * </ul>
 */
public final class TrialAttributionRollup {

    /** 单试次观测：success = 任务是否成功；attribution 为 null 或 AGENT_FAILURE
     *  = 计入模型评分（成功/失败归模型行为本身）；其余四值为排除性归因。
     *  success=true 不允许携带任何归因（成功即正常计入，无归因可挂）。 */
    public record TrialOutcome(boolean success, TrialAttribution attribution) {

        public TrialOutcome {
            if (success && attribution != null) {
                throw new IllegalArgumentException(
                        "成功试次不得携带归因（成功即正常计入模型评分）: " + attribution);
            }
        }

        /** 计入模型评分面（成功或模型行为失败） */
        boolean modelScored() {
            return attribution == null || attribution == TrialAttribution.AGENT_FAILURE;
        }
    }

    /** 比率分子/分母 + 状态（分母 0 = NOT_APPLICABLE，如实不约分） */
    public record Ratio(long numerator, long denominator, String status) {

        public static final String OK = "OK";
        public static final String NOT_APPLICABLE = "NOT_APPLICABLE";

        static Ratio of(long numerator, long denominator) {
            return new Ratio(numerator, denominator,
                    denominator == 0 ? NOT_APPLICABLE : OK);
        }
    }

    /** 归因汇总（计数全记录；双比率分母口径见类级 javadoc） */
    public record AttributionRollup(int plannedTrials,
                                    int observedTrials,
                                    int unobservedTrials,
                                    int modelSuccesses,
                                    int modelFailures,
                                    int excludedTrials,
                                    Map<TrialAttribution, Integer> attributionCounts,
                                    Ratio modelSuccessRate,
                                    Ratio validCoverage) {
    }

    private TrialAttributionRollup() {
    }

    public static AttributionRollup rollup(int plannedTrials, List<TrialOutcome> outcomes) {
        if (plannedTrials < 0) {
            throw new IllegalArgumentException("plannedTrials 不得为负: " + plannedTrials);
        }
        Objects.requireNonNull(outcomes, "outcomes 不得为 null");
        if (outcomes.size() > plannedTrials) {
            throw new IllegalArgumentException("观测试次数超过计划分母: " + outcomes.size()
                    + " > " + plannedTrials + "（分母不消失，超计划须先修订计划）");
        }
        Map<TrialAttribution, Integer> counts = new EnumMap<>(TrialAttribution.class);
        int successes = 0;
        int failures = 0;
        int excluded = 0;
        for (TrialOutcome outcome : outcomes) {
            if (outcome.attribution() != null) {
                counts.merge(outcome.attribution(), 1, Integer::sum);
            }
            if (outcome.modelScored()) {
                if (outcome.success()) {
                    successes++;
                } else {
                    failures++;
                }
            } else {
                excluded++;
            }
        }
        long modelScored = (long) successes + failures;
        return new AttributionRollup(plannedTrials, outcomes.size(),
                plannedTrials - outcomes.size(), successes, failures, excluded,
                Map.copyOf(counts),
                Ratio.of(successes, modelScored),
                Ratio.of(modelScored, plannedTrials));
    }
}
