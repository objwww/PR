package com.objwww.pr.control.eval.domain.service;

import com.objwww.pr.control.eval.domain.model.EvalPreregistration;

import java.util.List;
import java.util.Objects;

/**
 * A/B 位置偏差检查纯函数（ME-T09/D09 步骤 3 + JUDGE-04 矩阵；L0 不触网——
 * 交换 A/B 顺序与匿名标签的一致性统计面，真实模型跑批归后续专项）。
 *
 * <p>口径：同一对判定以原序与交换序各跑一次，一致 = 交换后结论恰为原结论的
 * 镜像（A_BETTER ↔ B_BETTER，TIE ↔ TIE）——胜者跟随候选而非位置；非镜像即
 * 一记位置偏差。biasRate 超过预登记容忍值（{@link EvalPreregistration#positionBiasTolerance()}，
 * 严格大于）→ {@link Verdict#FORBID_AUTO_VERDICT}：禁自动裁决，转人工。
 * 分子/分母全记录（比率不约分）。
 */
public final class PositionBiasCheck {

    /** 单方向裁决（盲化标签面：A/B 为匿名位，不携带候选身份） */
    public enum Judgment {
        A_BETTER,
        B_BETTER,
        TIE
    }

    /** 一对位置交换判定（同输入两跑：原序结论 + 交换序结论） */
    public record SwapPair(String pairId, Judgment originalOrder, Judgment swappedOrder) {

        public SwapPair {
            Objects.requireNonNull(pairId, "pairId 不得为 null");
            if (pairId.isBlank()) {
                throw new IllegalArgumentException("pairId 不得为 blank");
            }
            Objects.requireNonNull(originalOrder, "originalOrder 不得为 null");
            Objects.requireNonNull(swappedOrder, "swappedOrder 不得为 null");
        }

        /** 一致 = 交换序结论为原序结论的镜像（胜者跟候选不跟位置） */
        boolean consistent() {
            return mirror(originalOrder) == swappedOrder;
        }

        private static Judgment mirror(Judgment judgment) {
            return switch (judgment) {
                case A_BETTER -> Judgment.B_BETTER;
                case B_BETTER -> Judgment.A_BETTER;
                case TIE -> Judgment.TIE;
            };
        }
    }

    public enum Verdict {
        /** 偏差在预登记容忍值内，允许自动裁决 */
        AUTO_VERDICT_ALLOWED,
        /** 偏差超容忍，禁自动裁决转人工（JUDGE-04） */
        FORBID_AUTO_VERDICT
    }

    /** 位置偏差报告（分子/分母/容忍值/判定全记录 = 复现锚） */
    public record PositionBiasReport(int pairs, int consistentPairs, int biasedPairs,
                                     double biasRate, double tolerance, Verdict verdict) {
    }

    private PositionBiasCheck() {
    }

    public static PositionBiasReport check(List<SwapPair> pairs, double tolerance) {
        Objects.requireNonNull(pairs, "pairs 不得为 null");
        if (pairs.isEmpty()) {
            throw new IllegalArgumentException("pairs 不得为空（无交换对无偏差统计）");
        }
        if (tolerance < 0 || tolerance >= 1) {
            throw new IllegalArgumentException("tolerance 须在 [0,1): " + tolerance);
        }
        int biased = 0;
        for (SwapPair pair : pairs) {
            if (!pair.consistent()) {
                biased++;
            }
        }
        double biasRate = (double) biased / pairs.size();
        Verdict verdict = biasRate > tolerance
                ? Verdict.FORBID_AUTO_VERDICT : Verdict.AUTO_VERDICT_ALLOWED;
        return new PositionBiasReport(pairs.size(), pairs.size() - biased, biased,
                biasRate, tolerance, verdict);
    }
}
