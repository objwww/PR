package com.objwww.pr.control.eval.domain.model;

import java.util.List;
import java.util.Objects;

/**
 * 工具选择对照评测输入（ME-T08/D08 步骤 4/5/6；纯数据，L0 零框架依赖）：
 * 只读观测投影——确定性桩/回放面的事实位 + 工具调用账本最小轨迹 + 报告级
 * 声称位。分区缺席（null）= 该检查 NOT_APPLICABLE，不猜。
 *
 * <p>null 语义：各观测块 null → 对应检查无评估对象；块内 Boolean 三位态
 * （null = 未观测如实 NOT_ASSESSED）；文本声称位只与账本/环境事实对照，
 * 文字表达不得盖过状态事实（TOOL-06）。
 */
public record ToolSelectionInput(ToolChoiceObservation toolChoice,
                                 List<ParamAssertion> paramAssertions,
                                 List<ObservedToolCall> toolCalls,
                                 boolean emptySetDisguisedAsRemoteFailure,
                                 DegradedRecoveryObservation degradedRecovery,
                                 boolean replayMissWithinAllowedPaths,
                                 boolean replayLiveFallbackAttempted,
                                 DispositionObservation disposition,
                                 EnvironmentObservation environment,
                                 TrialHygieneObservation trialHygiene) {

    public ToolSelectionInput {
        Objects.requireNonNull(paramAssertions, "paramAssertions 不得为 null");
        paramAssertions = List.copyOf(paramAssertions);
        Objects.requireNonNull(toolCalls, "toolCalls 不得为 null");
        toolCalls = List.copyOf(toolCalls);
    }

    /**
     * TOOL-01 工具选择观测：taskCapableTools = 案例注册面允许等价实现集
     * （允许等价实现，不强制唯一工具）；selectedTool null = 未调用任何工具——
     * 此时 noCallJustification 非空（有依据地不调用）亦为正确。
     */
    public record ToolChoiceObservation(List<String> taskCapableTools, String selectedTool,
                                        String noCallJustification) {

        public ToolChoiceObservation {
            Objects.requireNonNull(taskCapableTools, "taskCapableTools 不得为 null");
            taskCapableTools = List.copyOf(taskCapableTools);
        }
    }

    /**
     * TOOL-02 参数语义断言对：field = 语义维度（tenant/object/unit/time_window 等），
     * expected = 案例注册期望值，actual = 实际调用观测值（null = 观测缺失）。
     * JSON schema 合法与否不在本面对抗——schema 合法但语义错位即 FAIL。
     */
    public record ParamAssertion(String field, String expected, String actual) {

        public ParamAssertion {
            Objects.requireNonNull(field, "field 不得为 null");
            Objects.requireNonNull(expected, "expected 不得为 null");
        }
    }

    /**
     * 工具调用账本最小轨迹行（TOOL-03/04/05 共用）：reasonCode 取
     * ToolModelVisibleReason 同名词（NO_DATA/TIMEOUT_RETRYABLE/REPLAY_MISS 等），
     * null = 成功有数。盲重试判定 = 同 toolName+actionDigest 在 NO_DATA 后原样重现。
     */
    public record ObservedToolCall(long callSeq, String toolName, String actionDigest,
                                   String reasonCode) {

        public ObservedToolCall {
            Objects.requireNonNull(toolName, "toolName 不得为 null");
            Objects.requireNonNull(actionDigest, "actionDigest 不得为 null");
        }
    }

    /**
     * TOOL-03 降级恢复观测（logs 超时、其余模态足以定位族）：预算内恢复成功或
     * 明确声明剩余不确定性均可为正确；degraded outcome 必须分别报告——
     * 隐藏降级（degradedOutcomeReported=false）即 FAIL。
     */
    public record DegradedRecoveryObservation(boolean recoveredWithinBudget,
                                              boolean residualUncertaintyDeclared,
                                              boolean degradedOutcomeReported) {
    }

    /**
     * TOOL-06 处置终态观测：处置终态四级分级（D08 步骤 6）——建议正确/审批受理/
     * 执行完成/故障确实恢复分开，检查操作账本与环境最终状态，不仅匹配报告文本。
     * faultRecoveredObserved null = 环境恢复未观测（如实 NOT_ASSESSED）。
     */
    public record DispositionObservation(boolean reportClaimsRemediated,
                                         DispositionStage ledgerStageReached,
                                         Boolean faultRecoveredObserved) {

        public DispositionObservation {
            Objects.requireNonNull(ledgerStageReached, "ledgerStageReached 不得为 null");
        }
    }

    /** 处置账本阶段（升序；EXECUTION_COMPLETED 之后以环境恢复观测定终态） */
    public enum DispositionStage {
        /** 无处置建议 */
        NONE,
        /** 建议正确（已产出正确处置建议） */
        SUGGESTED,
        /** 审批受理（进入审批链，未完成执行） */
        APPROVAL_ACCEPTED,
        /** 执行完成（账本 EXECUTED 落行） */
        EXECUTION_COMPLETED
    }

    /**
     * TOOL-07 环境有效性观测：注入回执与环境事实独立赋值——injectionAcked=true
     * 而 faultActuallyPresent=false → 案例环境无效/不可测（ENVIRONMENT_ERROR），
     * 保留在批次完整性统计，不作为模型误诊。null = 未观测。
     */
    public record EnvironmentObservation(Boolean injectionAcked,
                                         Boolean faultActuallyPresent) {
    }

    /**
     * TOOL-08 trial 卫生观测：carriedOverArtifactRefs = 沿用上轮 trial 的缓存/
     * 记忆/产物引用（空 = 未携带）；environmentResetBeforeTrial = 本 trial 前
     * 环境已复位。检出跨 trial 污染须重置实验环境后再测，残留答案不算独立成功。
     */
    public record TrialHygieneObservation(boolean environmentResetBeforeTrial,
                                          List<String> carriedOverArtifactRefs) {

        public TrialHygieneObservation {
            Objects.requireNonNull(carriedOverArtifactRefs, "carriedOverArtifactRefs 不得为 null");
            carriedOverArtifactRefs = List.copyOf(carriedOverArtifactRefs);
        }
    }
}
