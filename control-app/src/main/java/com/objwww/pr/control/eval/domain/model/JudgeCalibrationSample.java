package com.objwww.pr.control.eval.domain.model;

import java.util.Objects;

/**
 * 裁判校准集标注记录（ME-T09/D09 步骤 2；纯数据，L0 零框架依赖）。
 *
 * <p>六型样本覆盖校准面：明确正例/明确反例/信息不足/关键词堆砌/引用内容相反/
 * 裁判提示注入。双人独立标注 + 争议仲裁字段：annotatorA 与 annotatorB 不一致
 * （争议子集）时 arbitrated 必填——不允许只拿「同一裁判重跑一致」冒充准确性
 * （重跑一致只测稳定性，双人一致率 + 对金标准一致率才测准确，见
 * {@code JudgeCalibrationStats}）。
 */
public record JudgeCalibrationSample(String sampleId,
                                     SampleType type,
                                     CalibrationLabel goldLabel,
                                     CalibrationLabel annotatorA,
                                     CalibrationLabel annotatorB,
                                     CalibrationLabel arbitrated) {

    /** 校准样本六型（D09 步骤 2 冻结词表） */
    public enum SampleType {
        /** 明确正例（证据充分且支持） */
        CLEAR_POSITIVE,
        /** 明确反例（证据确证不支撑） */
        CLEAR_NEGATIVE,
        /** 信息不足（正确裁决 = UNKNOWN） */
        INSUFFICIENT_INFO,
        /** 关键词堆砌（表面命中但无实质支持） */
        KEYWORD_STUFFING,
        /** 引用内容相反（引用真实存在但与断言相反） */
        QUOTE_CONTRADICTS,
        /** 裁判提示注入（被评文本内嵌指挥裁判的指令） */
        JUDGE_PROMPT_INJECTION
    }

    /** 校准标注三态（UNKNOWN 是一等标注值，不是缺席） */
    public enum CalibrationLabel {
        PASS,
        FAIL,
        UNKNOWN
    }

    public JudgeCalibrationSample {
        Objects.requireNonNull(sampleId, "sampleId 不得为 null");
        if (sampleId.isBlank()) {
            throw new IllegalArgumentException("sampleId 不得为 blank");
        }
        Objects.requireNonNull(type, "type 不得为 null");
        Objects.requireNonNull(goldLabel, "goldLabel 不得为 null");
        Objects.requireNonNull(annotatorA, "annotatorA 不得为 null");
        Objects.requireNonNull(annotatorB, "annotatorB 不得为 null");
        if (annotatorA != annotatorB && arbitrated == null) {
            throw new IllegalArgumentException(
                    "双人标注不一致的争议样本必须仲裁: " + sampleId);
        }
    }

    /** 争议子集判定（双人独立标注不一致） */
    public boolean disputed() {
        return annotatorA != annotatorB;
    }

    /** 最终标注（争议样本取仲裁值，否则取双人一致值） */
    public CalibrationLabel finalLabel() {
        return arbitrated != null ? arbitrated : annotatorA;
    }
}
