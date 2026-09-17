package com.objwww.pr.control.eval.domain;

import com.objwww.pr.control.alert.domain.model.TypedRootCause;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * 场景×轮逐案例评分记录（M3-14/M3-16）：insert-only，落库即冻结
 * （无 UPDATE 授权；重评 = 换新 EvalRun）。
 *
 * <p>评分对象选择规则（M3-16 冻结，评审 P0-7）：scoredAttemptId/scoredReportId
 * 必须是该 RCA Run 状态机最终选定的唯一报告——由 selectionPolicyVersion 声明所用
 * 规则版本，禁止 evaluator 从多 attempt 挑最优。verdict 形态约束见迁移
 * ck_eval_case_verdict_shape（DECIDABLE/UNRESOLVED 必有报告；结构失败/缺席无报告）。
 *
 * <p>P3 三维评分+过程计数（V140，全列可空=null 该维未评——不填 0 冒充）：
 * <ul>
 *   <li>定因：causeComponentHit/causeFaultHit/causeReasonHit 逐维同义词命中
 *       （仅 DECIDABLE 有值；全中=rootCauseHit，部分中=部分分）；</li>
 *   <li>路径：checkpointsTotal/Covered + checkpointMatchesJson 逐点命中明细
 *       （null total = 该案例无 GT 证据检查点）；</li>
 *   <li>结论复核：conclusionGrounded ∈ GROUNDED/UNGROUNDED/NOT_APPLICABLE
 *       （TRUE 根因 claim 是否带证据引用——ungrounded diagnosis 探针）；</li>
 *   <li>过程：toolCallsTotal/toolCallsUnique（评分 attempt 的 tool_call 账本
 *       计数；total?unique=重复调用观测）。</li>
 * </ul>
 *
 * <p>P6-G8 难度分层：difficulty（RCA-Bench L1–L4，案例定义面标注随评分落档；
 * null=未标注如实不出数）——读面按难度聚合（G8 分层报表）。
 */
public record EvalCaseResult(UUID id,
                             UUID evalRunId,
                             String scenarioId,
                             int roundNo,
                             String selectionPolicyVersion,
                             UUID rcaRunId,
                             UUID scoredAttemptId,
                             UUID scoredReportId,
                             ScenarioMetrics.ScoringVerdict verdict,
                             boolean rootCauseHit,
                             TypedRootCause expectedRootCause,
                             TypedRootCause actualRootCause,
                             List<String> expectedSymptomCodes,
                             List<String> actualSymptomCodes,
                             int tpCount,
                             int fpCount,
                             int fnCount,
                             Long latencyMs,
                             boolean silencePenalty,
                             String failureSampleJson,
                             Boolean causeComponentHit,
                             Boolean causeFaultHit,
                             Boolean causeReasonHit,
                             Integer checkpointsTotal,
                             Integer checkpointsCovered,
                             String checkpointMatchesJson,
                             String conclusionGrounded,
                             Integer toolCallsTotal,
                             Integer toolCallsUnique,
                             String difficulty) {

    /** 结论有据性词表（conclusionGrounded 值域） */
    public static final String GROUNDED = "GROUNDED";
    public static final String UNGROUNDED = "UNGROUNDED";
    public static final String GROUNDED_NA = "NOT_APPLICABLE";

    public EvalCaseResult {
        Objects.requireNonNull(id, "id 不得为 null");
        Objects.requireNonNull(evalRunId, "eval_run_id 不得为 null");
        Objects.requireNonNull(scenarioId, "scenario_id 不得为 null");
        Objects.requireNonNull(selectionPolicyVersion, "selection_policy_version 不得为 null");
        Objects.requireNonNull(verdict, "verdict 不得为 null");
        Objects.requireNonNull(expectedRootCause, "expected_root_cause 不得为 null");
        expectedSymptomCodes = List.copyOf(expectedSymptomCodes);
        actualSymptomCodes = actualSymptomCodes == null ? List.of() : List.copyOf(actualSymptomCodes);
        if (roundNo < 1) {
            throw new IllegalArgumentException("round_no 必须从 1 起");
        }
        if (tpCount < 0 || fpCount < 0 || fnCount < 0) {
            throw new IllegalArgumentException("TP/FP/FN 计数不得为负");
        }
        if (rootCauseHit && verdict != ScenarioMetrics.ScoringVerdict.DECIDABLE) {
            throw new IllegalArgumentException("root_cause_hit 仅 DECIDABLE 可为 true");
        }
        boolean hasReport = scoredReportId != null;
        boolean reportRequired = verdict == ScenarioMetrics.ScoringVerdict.DECIDABLE
                || verdict == ScenarioMetrics.ScoringVerdict.UNRESOLVED;
        if (reportRequired != hasReport) {
            throw new IllegalArgumentException(
                    "verdict=" + verdict + " 与 scored_report_id 存在性不一致");
        }
        if (causeComponentHit != null && verdict != ScenarioMetrics.ScoringVerdict.DECIDABLE) {
            throw new IllegalArgumentException("定因逐维命中仅 DECIDABLE 可有值");
        }
        if (checkpointsCovered != null && (checkpointsTotal == null
                || checkpointsCovered > checkpointsTotal || checkpointsCovered < 0)) {
            throw new IllegalArgumentException("检查点覆盖数必须落在 [0, total]");
        }
        if (toolCallsTotal != null && toolCallsUnique != null
                && (toolCallsUnique > toolCallsTotal || toolCallsUnique < 0)) {
            throw new IllegalArgumentException("unique 调用数必须落在 [0, total]");
        }
    }

    /**
     * M3-16 兼容构造（P3 前调用面）：三维/过程计数 = 全 null（未评，不填 0）。
     */
    public EvalCaseResult(UUID id, UUID evalRunId, String scenarioId, int roundNo,
                          String selectionPolicyVersion, UUID rcaRunId, UUID scoredAttemptId,
                          UUID scoredReportId, ScenarioMetrics.ScoringVerdict verdict,
                          boolean rootCauseHit, TypedRootCause expectedRootCause,
                          TypedRootCause actualRootCause, List<String> expectedSymptomCodes,
                          List<String> actualSymptomCodes, int tpCount, int fpCount,
                          int fnCount, Long latencyMs, boolean silencePenalty,
                          String failureSampleJson) {
        this(id, evalRunId, scenarioId, roundNo, selectionPolicyVersion, rcaRunId,
                scoredAttemptId, scoredReportId, verdict, rootCauseHit, expectedRootCause,
                actualRootCause, expectedSymptomCodes, actualSymptomCodes, tpCount, fpCount,
                fnCount, latencyMs, silencePenalty, failureSampleJson,
                null, null, null, null, null, null, null, null, null, null);
    }
}
