package com.objwww.pr.control.eval.domain.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * EV-04 发起计划：必填/正数校验、canonical 固定字段序（同计划同 digest——EU09
 * 幂等冲突判据；字段序变化即 digest 变化）、空值与 0 可区分。
 */
class EvalLaunchPlanTest {

    private static EvalLaunchPlan plan(String name, String mode, String dataset) {
        return new EvalLaunchPlan(name, mode, dataset, null, null, null, null, null, null);
    }

    @Test
    @DisplayName("同计划同 payloadHash；任一字段变化 → hash 变化（异 payload 409 判据）")
    void canonicalHashStableAndSensitive() {
        EvalLaunchPlan a = plan("实验甲", "L", "eval-ds-1");
        EvalLaunchPlan b = plan("实验甲", "L", "eval-ds-1");
        assertThat(a.payloadHash()).isEqualTo(b.payloadHash());

        assertThat(plan("实验乙", "L", "eval-ds-1").payloadHash()).isNotEqualTo(a.payloadHash());
        assertThat(plan("实验甲", "E", "eval-ds-1").payloadHash()).isNotEqualTo(a.payloadHash());
        assertThat(new EvalLaunchPlan("实验甲", "L", "eval-ds-1", "glm-5", null,
                null, null, null, null).payloadHash()).isNotEqualTo(a.payloadHash());
        // "未填"与"填具体值"可区分
        assertThat(new EvalLaunchPlan("实验甲", "L", "eval-ds-1", null, null,
                1000L, null, null, null).payloadHash()).isNotEqualTo(a.payloadHash());
    }

    @Test
    @DisplayName("必填校验：displayName/datasetVersion 空白与非法 mode 一律拒")
    void requiredFieldsValidated() {
        assertThatThrownBy(() -> plan(" ", "L", "eval-ds-1"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("displayName");
        assertThatThrownBy(() -> plan(null, "L", "eval-ds-1"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> plan("实验甲", "X", "eval-ds-1"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("mode");
        assertThatThrownBy(() -> plan("实验甲", "L", ""))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("datasetVersion");
    }

    @Test
    @DisplayName("预算/轮次必须为正（0 与负值拒；null = 未填放行）")
    void positiveNumbersOrNull() {
        assertThatThrownBy(() -> new EvalLaunchPlan("n", "E", "d", null, null,
                0L, null, null, null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new EvalLaunchPlan("n", "E", "d", null, null,
                null, -1, null, null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new EvalLaunchPlan("n", "E", "d", null, null,
                null, null, null, 0)).isInstanceOf(IllegalArgumentException.class);
        // 全 null 可选面合法
        plan("n", "E", "d");
    }
}
