package com.objwww.pr.control.eval.domain.model;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * EvalPreregistration 冻结记录契约（ME-T09/D09 步骤 5）：主要指标/关键分层/
 * 非劣容忍度/成本预算/位置偏差容忍/最小簇数必填且取值合法——版本锚 + 值对象，
 * 跑完再挑指标无锚可依。
 */
class EvalPreregistrationTest {

    @Test
    void validPreregistrationFreezesAllFaces() {
        EvalPreregistration prereg = new EvalPreregistration("prereg-2026-09-v1",
                "rootCauseHitRate", List.of("safety", "conflict"), 0.02, 5_000_000L,
                0.1, 5);

        assertThat(prereg.preregistrationVersion()).isEqualTo("prereg-2026-09-v1");
        assertThat(prereg.primaryMetric()).isEqualTo("rootCauseHitRate");
        assertThat(prereg.keyStrata()).containsExactly("safety", "conflict");
        assertThat(prereg.nonInferiorityMargin()).isEqualTo(0.02);
        assertThat(prereg.costBudgetMicros()).isEqualTo(5_000_000L);
        assertThat(prereg.positionBiasTolerance()).isEqualTo(0.1);
        assertThat(prereg.minClusters()).isEqualTo(5);
    }

    @Test
    void rejectsBlankAnchorsAndOutOfRangeValues() {
        assertThatThrownBy(() -> new EvalPreregistration(" ", "m", List.of("s"), 0.0, 0L,
                0.1, 5)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new EvalPreregistration("v1", " ", List.of("s"), 0.0, 0L,
                0.1, 5)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new EvalPreregistration("v1", "m", List.of(), 0.0, 0L,
                0.1, 5)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new EvalPreregistration("v1", "m", List.of("s"), 1.5, 0L,
                0.1, 5)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new EvalPreregistration("v1", "m", List.of("s"), 0.0, -1L,
                0.1, 5)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new EvalPreregistration("v1", "m", List.of("s"), 0.0, 0L,
                1.0, 5)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new EvalPreregistration("v1", "m", List.of("s"), 0.0, 0L,
                0.1, 0)).isInstanceOf(IllegalArgumentException.class);
    }
}
