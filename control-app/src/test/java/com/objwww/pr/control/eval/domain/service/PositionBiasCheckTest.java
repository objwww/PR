package com.objwww.pr.control.eval.domain.service;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * PositionBiasCheck 机制面（ME-T09/D09 步骤 3 + JUDGE-04）：交换 A/B 顺序后
 * 结论应镜像（胜者跟候选不跟位置）；非镜像计位置偏差，偏差率超预登记容忍值
 * → FORBID_AUTO_VERDICT 禁自动裁决；分子/分母全记录。
 */
class PositionBiasCheckTest {

    private static PositionBiasCheck.SwapPair pair(String id,
                                                   PositionBiasCheck.Judgment original,
                                                   PositionBiasCheck.Judgment swapped) {
        return new PositionBiasCheck.SwapPair(id, original, swapped);
    }

    // ---------------- JUDGE-04：镜像一致允许自动裁决 ----------------

    @Test
    void mirroredVerdictsAreConsistentAndAllowAutoVerdict() {
        List<PositionBiasCheck.SwapPair> pairs = List.of(
                pair("p1", PositionBiasCheck.Judgment.A_BETTER,
                        PositionBiasCheck.Judgment.B_BETTER),
                pair("p2", PositionBiasCheck.Judgment.B_BETTER,
                        PositionBiasCheck.Judgment.A_BETTER),
                pair("p3", PositionBiasCheck.Judgment.TIE,
                        PositionBiasCheck.Judgment.TIE));

        PositionBiasCheck.PositionBiasReport report = PositionBiasCheck.check(pairs, 0.1);

        assertThat(report.pairs()).isEqualTo(3);
        assertThat(report.consistentPairs()).isEqualTo(3);
        assertThat(report.biasedPairs()).isEqualTo(0);
        assertThat(report.biasRate()).isEqualTo(0.0);
        assertThat(report.tolerance()).isEqualTo(0.1);
        assertThat(report.verdict()).isEqualTo(PositionBiasCheck.Verdict.AUTO_VERDICT_ALLOWED);
    }

    // ---------------- JUDGE-04：非镜像 = 位置偏差；超容忍禁自动裁决 ----------------

    @Test
    void nonMirroredSwapCountsAsPositionBias() {
        // p1: 原序 A 胜，交换后仍 A 胜 → 胜者跟位置 = 偏差
        // p2: 原序 A 胜，交换后 TIE → 非镜像 = 偏差
        List<PositionBiasCheck.SwapPair> pairs = List.of(
                pair("p1", PositionBiasCheck.Judgment.A_BETTER,
                        PositionBiasCheck.Judgment.A_BETTER),
                pair("p2", PositionBiasCheck.Judgment.A_BETTER,
                        PositionBiasCheck.Judgment.TIE),
                pair("p3", PositionBiasCheck.Judgment.TIE,
                        PositionBiasCheck.Judgment.TIE),
                pair("p4", PositionBiasCheck.Judgment.B_BETTER,
                        PositionBiasCheck.Judgment.A_BETTER));

        PositionBiasCheck.PositionBiasReport report = PositionBiasCheck.check(pairs, 0.1);

        assertThat(report.biasedPairs()).isEqualTo(2);
        assertThat(report.biasRate()).isEqualTo(0.5);
        assertThat(report.verdict()).isEqualTo(PositionBiasCheck.Verdict.FORBID_AUTO_VERDICT);
    }

    @Test
    void biasRateAtToleranceBoundaryStillAllowsAutoVerdict() {
        // 严格大于容忍值才禁：1/4 = 0.25 不偏出 0.25 容忍 → 允许
        List<PositionBiasCheck.SwapPair> pairs = List.of(
                pair("p1", PositionBiasCheck.Judgment.A_BETTER,
                        PositionBiasCheck.Judgment.A_BETTER),
                pair("p2", PositionBiasCheck.Judgment.A_BETTER,
                        PositionBiasCheck.Judgment.B_BETTER),
                pair("p3", PositionBiasCheck.Judgment.TIE,
                        PositionBiasCheck.Judgment.TIE),
                pair("p4", PositionBiasCheck.Judgment.B_BETTER,
                        PositionBiasCheck.Judgment.A_BETTER));

        PositionBiasCheck.PositionBiasReport report = PositionBiasCheck.check(pairs, 0.25);

        assertThat(report.biasRate()).isEqualTo(0.25);
        assertThat(report.verdict()).isEqualTo(PositionBiasCheck.Verdict.AUTO_VERDICT_ALLOWED);
    }

    // ---------------- 输入契约防呆 ----------------

    @Test
    void rejectsEmptyPairsAndInvalidTolerance() {
        assertThatThrownBy(() -> PositionBiasCheck.check(List.of(), 0.1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> PositionBiasCheck.check(
                List.of(pair("p1", PositionBiasCheck.Judgment.TIE,
                        PositionBiasCheck.Judgment.TIE)), 1.0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new PositionBiasCheck.SwapPair(" ",
                PositionBiasCheck.Judgment.TIE, PositionBiasCheck.Judgment.TIE))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
