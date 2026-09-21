package com.objwww.pr.control.eval.domain.model;

/**
 * 试次归因词表（ME-T08/D08 步骤 4/5，对齐 D09 步骤 6 六值口径）：
 * 区分失败/无效的归因归属，总计划分母不消失——排除性归因的试次不计入模型
 * 能力评分，但保留在批次完整性统计（分别报告模型成功率与实验有效覆盖）。
 *
 * <p>ToolSelectionEvaluation.attribution 语义：null = 正常计入模型评分（PASS/FAIL
 * 归模型行为本身）；非 null = 排除性归因，该试次不得直接定模型败。
 */
public enum TrialAttribution {
    /** 模型行为失败（由 failureLabels 承载细分，attribution 面不重复置位） */
    AGENT_FAILURE,
    /** 评测设施/实验规程错误（如跨 trial 污染——重置环境后再测） */
    HARNESS_ERROR,
    /** 环境无效（注入 ack 成功但故障未实际出现——案例不可测，不作模型误诊） */
    ENVIRONMENT_ERROR,
    /** 合理新路径缺 replay 录制——记录缺口人工判断，不混成模型工具选择失败 */
    REPLAY_COVERAGE_GAP,
    /** 评分器自身错误（v1 保留值，ToolSelectionEvaluator 不产生） */
    GRADER_ERROR,
    /** 未评/证据不足（v1 保留值；检查级粒度由 BehaviorCheckStatus 承载） */
    NOT_ASSESSED
}
