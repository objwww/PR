package com.objwww.pr.control.eval.domain.service;

import com.objwww.pr.control.eval.domain.model.TrialAttribution;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * TrialAttributionRollup 机制面（ME-T09/D09 步骤 6）：总计划分母不消失——
 * 排除性归因试次不进模型成功率分子分母，但摊薄实验有效覆盖；模型成功率与
 * 有效覆盖分别报告；分母 0 如实 NOT_APPLICABLE。
 */
class TrialAttributionRollupTest {

    private static TrialAttributionRollup.TrialOutcome success() {
        return new TrialAttributionRollup.TrialOutcome(true, null);
    }

    private static TrialAttributionRollup.TrialOutcome fail(TrialAttribution attribution) {
        return new TrialAttributionRollup.TrialOutcome(false, attribution);
    }

    @Test
    void exclusionaryAttributionsLeaveModelRateButThinCoverage() {
        // 计划 10：6 次模型有效（4 成 2 败）+ 2 环境无效 + 1 回放缺口 + 1 未执行
        TrialAttributionRollup.AttributionRollup rollup = TrialAttributionRollup.rollup(10,
                List.of(success(), success(), success(), success(),
                        fail(null), fail(TrialAttribution.AGENT_FAILURE),
                        fail(TrialAttribution.ENVIRONMENT_ERROR),
                        fail(TrialAttribution.ENVIRONMENT_ERROR),
                        fail(TrialAttribution.REPLAY_COVERAGE_GAP)));

        assertThat(rollup.plannedTrials()).isEqualTo(10);
        assertThat(rollup.observedTrials()).isEqualTo(9);
        assertThat(rollup.unobservedTrials()).isEqualTo(1);
        assertThat(rollup.modelSuccesses()).isEqualTo(4);
        assertThat(rollup.modelFailures()).isEqualTo(2);
        assertThat(rollup.excludedTrials()).isEqualTo(3);
        // 模型成功率 = 4/6（排除性归因不进分母）
        assertThat(rollup.modelSuccessRate().numerator()).isEqualTo(4);
        assertThat(rollup.modelSuccessRate().denominator()).isEqualTo(6);
        assertThat(rollup.modelSuccessRate().status()).isEqualTo(
                TrialAttributionRollup.Ratio.OK);
        // 实验有效覆盖 = 6/10（环境/回放问题摊薄覆盖，分母 = 总计划不消失）
        assertThat(rollup.validCoverage().numerator()).isEqualTo(6);
        assertThat(rollup.validCoverage().denominator()).isEqualTo(10);
        assertThat(rollup.attributionCounts())
                .containsEntry(TrialAttribution.ENVIRONMENT_ERROR, 2)
                .containsEntry(TrialAttribution.REPLAY_COVERAGE_GAP, 1)
                .containsEntry(TrialAttribution.AGENT_FAILURE, 1);
    }

    @Test
    void zeroModelScoredTrialsGiveNotApplicableSuccessRateButCoverageHolds() {
        // 全部试次排除性归因：模型成功率分母 0 → NOT_APPLICABLE；覆盖 0/4 照常出
        TrialAttributionRollup.AttributionRollup rollup = TrialAttributionRollup.rollup(4,
                List.of(fail(TrialAttribution.HARNESS_ERROR),
                        fail(TrialAttribution.GRADER_ERROR),
                        fail(TrialAttribution.NOT_ASSESSED),
                        fail(TrialAttribution.ENVIRONMENT_ERROR)));

        assertThat(rollup.modelSuccessRate().status()).isEqualTo(
                TrialAttributionRollup.Ratio.NOT_APPLICABLE);
        assertThat(rollup.modelSuccessRate().denominator()).isEqualTo(0);
        assertThat(rollup.validCoverage().numerator()).isEqualTo(0);
        assertThat(rollup.validCoverage().denominator()).isEqualTo(4);
        assertThat(rollup.validCoverage().status()).isEqualTo(TrialAttributionRollup.Ratio.OK);
    }

    @Test
    void rejectsOverPlanOutcomesAndAttributedSuccess() {
        assertThatThrownBy(() -> TrialAttributionRollup.rollup(1,
                List.of(success(), success())))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("分母");
        assertThatThrownBy(() -> new TrialAttributionRollup.TrialOutcome(true,
                TrialAttribution.ENVIRONMENT_ERROR))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
