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
                             String failureSampleJson) {

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
    }
}
