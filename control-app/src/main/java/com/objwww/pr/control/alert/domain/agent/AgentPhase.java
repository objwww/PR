package com.objwww.pr.control.alert.domain.agent;

/**
 * 角色阶段（R7 v2.1 §十一.2）：角色在调查链中的职责阶段。
 * 编译/调度的阶段判定以本字段为准，不依赖名字（§十 PlanCompiler 落点修订方向）。
 *
 * <p>v2.1 拓扑：主调查角色（PRIMARY，唯一必选，直接调查+按需委派+唯一模型 Claim
 * 提案出口）；专家按需（INVESTIGATE）；复核（REVIEW）与报告后处理（POSTPROCESS）
 * 为后期可选卡阶段。DIAGNOSE 仅旧版兼容——v2.1 取消独立诊断 LLM 调用，诊断职责
 * 合入主 Agent（§五.2/§十三 X11），本值只为旧 Profile 形态保留，新角色禁止使用。
 */
public enum AgentPhase {
    PRIMARY,
    INVESTIGATE,
    REVIEW,
    POSTPROCESS,

    /** 旧版兼容：v2.1 已无独立诊断阶段，新角色禁用（编译准入拒绝） */
    @Deprecated DIAGNOSE
}
