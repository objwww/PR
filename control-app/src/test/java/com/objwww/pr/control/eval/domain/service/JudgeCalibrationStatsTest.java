package com.objwww.pr.control.eval.domain.service;

import com.objwww.pr.control.eval.domain.model.JudgeCalibrationSample;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * JudgeCalibrationStats 机制面（ME-T09/D09 步骤 2）：六型覆盖如实出数；
 * 双人独立标注一致率与最终标注对金标准一致率分别报告（重跑一致 ≠ 准确性）；
 * 争议样本未仲裁即拒（结构面强制仲裁闭环）。
 */
class JudgeCalibrationStatsTest {

    private static JudgeCalibrationSample sample(String id,
                                                 JudgeCalibrationSample.SampleType type,
                                                 JudgeCalibrationSample.CalibrationLabel gold,
                                                 JudgeCalibrationSample.CalibrationLabel a,
                                                 JudgeCalibrationSample.CalibrationLabel b,
                                                 JudgeCalibrationSample.CalibrationLabel arbitrated) {
        return new JudgeCalibrationSample(id, type, gold, a, b, arbitrated);
    }

    private static List<JudgeCalibrationSample> sixTypeSet() {
        return List.of(
                sample("s1", JudgeCalibrationSample.SampleType.CLEAR_POSITIVE,
                        JudgeCalibrationSample.CalibrationLabel.PASS,
                        JudgeCalibrationSample.CalibrationLabel.PASS,
                        JudgeCalibrationSample.CalibrationLabel.PASS, null),
                sample("s2", JudgeCalibrationSample.SampleType.CLEAR_NEGATIVE,
                        JudgeCalibrationSample.CalibrationLabel.FAIL,
                        JudgeCalibrationSample.CalibrationLabel.FAIL,
                        JudgeCalibrationSample.CalibrationLabel.FAIL, null),
                sample("s3", JudgeCalibrationSample.SampleType.INSUFFICIENT_INFO,
                        JudgeCalibrationSample.CalibrationLabel.UNKNOWN,
                        JudgeCalibrationSample.CalibrationLabel.UNKNOWN,
                        JudgeCalibrationSample.CalibrationLabel.UNKNOWN, null),
                sample("s4", JudgeCalibrationSample.SampleType.KEYWORD_STUFFING,
                        JudgeCalibrationSample.CalibrationLabel.FAIL,
                        JudgeCalibrationSample.CalibrationLabel.FAIL,
                        JudgeCalibrationSample.CalibrationLabel.PASS,
                        JudgeCalibrationSample.CalibrationLabel.FAIL),
                sample("s5", JudgeCalibrationSample.SampleType.QUOTE_CONTRADICTS,
                        JudgeCalibrationSample.CalibrationLabel.FAIL,
                        JudgeCalibrationSample.CalibrationLabel.FAIL,
                        JudgeCalibrationSample.CalibrationLabel.FAIL, null),
                sample("s6", JudgeCalibrationSample.SampleType.JUDGE_PROMPT_INJECTION,
                        JudgeCalibrationSample.CalibrationLabel.FAIL,
                        JudgeCalibrationSample.CalibrationLabel.PASS,
                        JudgeCalibrationSample.CalibrationLabel.PASS, null));
    }

    // ---------------- 六型覆盖 + 双比率 ----------------

    @Test
    void sixTypeCoverageAndBothAgreementRatesReported() {
        JudgeCalibrationStats.CalibrationSummary summary =
                JudgeCalibrationStats.summarize(sixTypeSet());

        assertThat(summary.totalSamples()).isEqualTo(6);
        assertThat(summary.missingTypes()).isEmpty();
        assertThat(summary.typeCounts())
                .containsEntry(JudgeCalibrationSample.SampleType.KEYWORD_STUFFING, 1)
                .containsEntry(JudgeCalibrationSample.SampleType.JUDGE_PROMPT_INJECTION, 1);
        // 双人一致：s1/s2/s3/s5/s6 一致（s6 两人都 PASS，虽与金标准相左），s4 争议
        assertThat(summary.interAnnotatorAgreement().numerator()).isEqualTo(5);
        assertThat(summary.interAnnotatorAgreement().denominator()).isEqualTo(6);
        // 最终标注对金标准：s4 仲裁 FAIL 命中，s6 最终 PASS 偏离金标准 FAIL
        assertThat(summary.finalVsGoldAgreement().numerator()).isEqualTo(5);
        assertThat(summary.finalVsGoldAgreement().denominator()).isEqualTo(6);
        assertThat(summary.disputedCount()).isEqualTo(1);
        assertThat(summary.arbitratedCount()).isEqualTo(1);
    }

    @Test
    void missingTypesAreReportedNotHidden() {
        JudgeCalibrationStats.CalibrationSummary summary = JudgeCalibrationStats.summarize(
                List.of(sample("s1", JudgeCalibrationSample.SampleType.CLEAR_POSITIVE,
                        JudgeCalibrationSample.CalibrationLabel.PASS,
                        JudgeCalibrationSample.CalibrationLabel.PASS,
                        JudgeCalibrationSample.CalibrationLabel.PASS, null)));

        assertThat(summary.missingTypes()).containsExactlyInAnyOrder(
                JudgeCalibrationSample.SampleType.CLEAR_NEGATIVE,
                JudgeCalibrationSample.SampleType.INSUFFICIENT_INFO,
                JudgeCalibrationSample.SampleType.KEYWORD_STUFFING,
                JudgeCalibrationSample.SampleType.QUOTE_CONTRADICTS,
                JudgeCalibrationSample.SampleType.JUDGE_PROMPT_INJECTION);
    }

    // ---------------- 仲裁闭环（结构面强制） ----------------

    @Test
    void disputedSampleWithoutArbitrationIsRejected() {
        assertThatThrownBy(() -> sample("s1", JudgeCalibrationSample.SampleType.CLEAR_POSITIVE,
                JudgeCalibrationSample.CalibrationLabel.PASS,
                JudgeCalibrationSample.CalibrationLabel.PASS,
                JudgeCalibrationSample.CalibrationLabel.FAIL, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("仲裁");
    }

    @Test
    void finalLabelPrefersArbitration() {
        JudgeCalibrationSample s = sample("s1",
                JudgeCalibrationSample.SampleType.QUOTE_CONTRADICTS,
                JudgeCalibrationSample.CalibrationLabel.FAIL,
                JudgeCalibrationSample.CalibrationLabel.PASS,
                JudgeCalibrationSample.CalibrationLabel.UNKNOWN,
                JudgeCalibrationSample.CalibrationLabel.FAIL);

        assertThat(s.disputed()).isTrue();
        assertThat(s.finalLabel()).isEqualTo(JudgeCalibrationSample.CalibrationLabel.FAIL);
    }

    @Test
    void rejectsEmptySampleSet() {
        assertThatThrownBy(() -> JudgeCalibrationStats.summarize(List.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
