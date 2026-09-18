package com.objwww.pr.control.eval.domain.repository;

import java.util.List;

/**
 * LLM-judge 裁决端口（P6-G7 第三判定式）：只判报告自然语言质量维（美团 rubric
 * 二元化——每题是/否），四率主指标零接触（结论正确性仍归确定性规则，拒评纪律不变）。
 *
 * <p>fail-closed：judge 未配置（base-url/api-key 缺席）→ {@code judge()} 返回 empty，
 * 调用侧不落行（缺席=未评如实，与 eval_case_safety 同律）；模型调用/解析失败落
 * ERROR 行（尝试面审计），同样不影响评分主链。
 */
public interface EvalReportJudge {

    /** 裁决结果（rubricVersion 标注版本锚；answers 逐题二元答案原文） */
    record JudgeOutcome(String rubricVersion, String model, List<Answer> answers,
                        int passed, int total) {
    }

    record Answer(String id, String question, boolean yes) {
    }

    /**
     * 裁决报告原文。empty = judge 未启用（诚实缺席）；抛异常由调用方折算 ERROR 行。
     */
    java.util.Optional<JudgeOutcome> judge(String reportText);
}
