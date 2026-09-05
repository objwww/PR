package com.objwww.pr.control.eval.domain;

import java.util.List;

/**
 * 三指标计算器（AM3 v3.0 §6.4 冻结公式；M3-11~13 纯函数，拒绝 LLM-as-judge）。
 *
 * <pre>
 * coverage             = 给出可判定根因的场景数 / 总场景数
 * conditional_accuracy = 根因正确的场景数 / 给出可判定根因的场景数
 * end_to_end_hit_rate  = 根因正确的场景数 / 总场景数
 * </pre>
 *
 * <p>冻结语义逐条：
 * <ul>
 *   <li>UNRESOLVED（谨慎拒答）：coverage 算未覆盖；<b>不进 conditional_accuracy 分母</b>；
 *       end_to_end 自然未命中；单独报告合理拒答率 unresolvedRate（可区分"乱猜高覆盖"
 *       与"谨慎高准确"）；</li>
 *   <li>STRUCTURE_REJECTED（REJECTED_*）：计 0 分入总场景分母（明确失败，非超时）；</li>
 *   <li>TIMEOUT_OR_ABSENT（缺席报告/轮询超时）：单独标注计数，不混入结构失败。</li>
 * </ul>
 *
 * <p>零分母约定：total=0 或 decidable=0 时对应比率为 0.0（不产生 NaN——报告面拒绝
 * 非有限数）；snapshot 同时携带全部原始计数（M3-14 记分子分母不只存小数）。
 */
public final class ScenarioMetrics {

    private ScenarioMetrics() {
    }

    /** 单场景评分输入：verdict 分四类；rootCauseHit 仅 DECIDABLE 有意义（其余恒 false） */
    public record ScenarioScore(String scenarioId, ScoringVerdict verdict, boolean rootCauseHit) {
    }

    public enum ScoringVerdict {DECIDABLE, UNRESOLVED, STRUCTURE_REJECTED, TIMEOUT_OR_ABSENT}

    /** 公式快照：原始计数（分子分母）与三比率 + 合理拒答率，一次计算全部可复现字段 */
    public record Snapshot(int total, int decidable, int hits,
                           int unresolved, int structureRejected, int timeoutOrAbsent,
                           double coverage, double conditionalAccuracy,
                           double endToEndHitRate, double unresolvedRate) {
    }

    public static Snapshot of(List<ScenarioScore> scores) {
        int total = scores.size();
        int decidable = 0;
        int hits = 0;
        int unresolved = 0;
        int rejected = 0;
        int absent = 0;
        for (ScenarioScore s : scores) {
            switch (s.verdict()) {
                case DECIDABLE -> {
                    decidable++;
                    if (s.rootCauseHit()) {
                        hits++;
                    }
                }
                case UNRESOLVED -> unresolved++;
                case STRUCTURE_REJECTED -> rejected++;
                case TIMEOUT_OR_ABSENT -> absent++;
            }
        }
        return new Snapshot(total, decidable, hits, unresolved, rejected, absent,
                ratio(decidable, total),                            // coverage
                ratio(hits, decidable),                             // conditional_accuracy
                ratio(hits, total),                                 // end_to_end_hit_rate
                ratio(unresolved, total));
    }

    private static double ratio(int numerator, int denominator) {
        return denominator == 0 ? 0.0 : (double) numerator / denominator;
    }
}
