package com.objwww.pr.control.eval.domain.service;

import com.objwww.pr.control.eval.domain.model.JudgeCalibrationSample;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 裁判校准集统计纯函数（ME-T09/D09 步骤 2；L0 不触网——标注记录结构的出数面，
 * 真实双人标注集的建设归后续专项，本类冻结聚合口径）。
 *
 * <p>分子/分母双比率，分母不消失：
 * <ul>
 *   <li>interAnnotatorAgreement：双人独立标注一致率（稳定面上限）；</li>
 *   <li>finalVsGoldAgreement：最终标注（争议取仲裁）对金标准一致率（准确性面——
 *       不只用「同一裁判重跑一致」代替准确性）；</li>
 *   <li>六型覆盖：任一型缺席如实列入 missingTypes（六型不全的校准集不算建成）。</li>
 * </ul>
 */
public final class JudgeCalibrationStats {

    /** 比率分子/分母（分母 0 = 口径内无对象，如实不约分） */
    public record Ratio(long numerator, long denominator) {

        public double value() {
            if (denominator == 0) {
                throw new IllegalStateException("分母为 0 的比率无值（如实缺数不约分）");
            }
            return (double) numerator / denominator;
        }
    }

    /** 校准集汇总（计数全记录；比率字段永远可算——样本空已在入口拒绝） */
    public record CalibrationSummary(int totalSamples,
                                     Map<JudgeCalibrationSample.SampleType, Integer> typeCounts,
                                     List<JudgeCalibrationSample.SampleType> missingTypes,
                                     int disputedCount,
                                     int arbitratedCount,
                                     Ratio interAnnotatorAgreement,
                                     Ratio finalVsGoldAgreement) {
    }

    private JudgeCalibrationStats() {
    }

    public static CalibrationSummary summarize(List<JudgeCalibrationSample> samples) {
        Objects.requireNonNull(samples, "samples 不得为 null");
        if (samples.isEmpty()) {
            throw new IllegalArgumentException("samples 不得为空（无样本无校准统计）");
        }
        Map<JudgeCalibrationSample.SampleType, Integer> typeCounts =
                new EnumMap<>(JudgeCalibrationSample.SampleType.class);
        int agreed = 0;
        int disputed = 0;
        int arbitrated = 0;
        int goldHit = 0;
        for (JudgeCalibrationSample sample : samples) {
            typeCounts.merge(sample.type(), 1, Integer::sum);
            if (sample.disputed()) {
                disputed++;
                if (sample.arbitrated() != null) {
                    arbitrated++;
                }
            } else {
                agreed++;
            }
            if (sample.finalLabel() == sample.goldLabel()) {
                goldHit++;
            }
        }
        List<JudgeCalibrationSample.SampleType> missing = java.util.Arrays.stream(
                        JudgeCalibrationSample.SampleType.values())
                .filter(t -> !typeCounts.containsKey(t)).toList();
        return new CalibrationSummary(samples.size(), Map.copyOf(typeCounts), missing,
                disputed, arbitrated,
                new Ratio(agreed, samples.size()),
                new Ratio(goldHit, samples.size()));
    }
}
