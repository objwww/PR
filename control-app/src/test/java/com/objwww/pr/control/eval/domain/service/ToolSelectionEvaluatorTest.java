package com.objwww.pr.control.eval.domain.service;

import com.objwww.pr.control.eval.domain.model.BehaviorCheckStatus;
import com.objwww.pr.control.eval.domain.model.BehaviorEvaluation;
import com.objwww.pr.control.eval.domain.model.ToolSelectionEvaluation;
import com.objwww.pr.control.eval.domain.model.ToolSelectionInput;
import com.objwww.pr.control.eval.domain.model.TrialAttribution;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ME-T08（REPORT D08）工具选择对照评测纯函数：TOOL-01~08 测试矩阵（脚本化
 * 守卫/评分器面，确定性桩出数；真实模型跑批归夜间专项，不在此断言）。
 */
class ToolSelectionEvaluatorTest {

    private final ToolSelectionEvaluator evaluator = new ToolSelectionEvaluator();

    /** 全分区缺席的基座（各测试按需覆盖对应分区） */
    private static ToolSelectionInput input(ToolSelectionInput.ToolChoiceObservation choice,
                                            List<ToolSelectionInput.ParamAssertion> assertions,
                                            List<ToolSelectionInput.ObservedToolCall> calls,
                                            boolean disguised,
                                            ToolSelectionInput.DegradedRecoveryObservation recovery,
                                            boolean missWithinPaths,
                                            boolean liveFallback,
                                            ToolSelectionInput.DispositionObservation disposition,
                                            ToolSelectionInput.EnvironmentObservation environment,
                                            ToolSelectionInput.TrialHygieneObservation hygiene) {
        return new ToolSelectionInput(choice, assertions, calls, disguised, recovery,
                missWithinPaths, liveFallback, disposition, environment, hygiene);
    }

    private static ToolSelectionInput base() {
        return input(null, List.of(), List.of(), false, null, false, false, null, null, null);
    }

    private static ToolSelectionInput withChoice(
            ToolSelectionInput.ToolChoiceObservation choice) {
        return input(choice, List.of(), List.of(), false, null, false, false, null, null, null);
    }

    private static ToolSelectionInput withAssertions(
            List<ToolSelectionInput.ParamAssertion> assertions) {
        return input(null, assertions, List.of(), false, null, false, false, null, null, null);
    }

    private static ToolSelectionInput withCalls(
            List<ToolSelectionInput.ObservedToolCall> calls, boolean disguised,
            boolean missWithinPaths, boolean liveFallback) {
        return input(null, List.of(), calls, disguised, null, missWithinPaths, liveFallback,
                null, null, null);
    }

    private static BehaviorEvaluation.Check checkOf(ToolSelectionEvaluation ev, String name) {
        return ev.checks().stream().filter(c -> c.name().equals(name)).findFirst()
                .orElseThrow(() -> new AssertionError("缺检查项 " + name));
    }

    private static ToolSelectionInput.ObservedToolCall call(long seq, String tool,
                                                            String digest, String reason) {
        return new ToolSelectionInput.ObservedToolCall(seq, tool, digest, reason);
    }

    // ------------------------------------------------------------------ TOOL-01

    @Test
    @DisplayName("TOOL-01：相近工具名任务不写工具名——等价集内任一实现或有依据不调用均正确")
    void tool01EquivalentChoiceOrJustifiedNoCall() {
        List<String> capable = List.of("metrics.query", "prometheus.query");

        ToolSelectionEvaluation selected = evaluator.evaluate(withChoice(
                new ToolSelectionInput.ToolChoiceObservation(capable, "prometheus.query", null)));
        assertThat(checkOf(selected, ToolSelectionEvaluator.CHECK_TOOL_CHOICE).status())
                .isEqualTo(BehaviorCheckStatus.PASS);
        assertThat(checkOf(selected, ToolSelectionEvaluator.CHECK_TOOL_CHOICE).reasonCode())
                .isEqualTo("TOOL_WITHIN_EQUIVALENTS");

        ToolSelectionEvaluation justified = evaluator.evaluate(withChoice(
                new ToolSelectionInput.ToolChoiceObservation(capable, null,
                        "告警窗内指标已在告警负载中携带，无需重复查询")));
        assertThat(checkOf(justified, ToolSelectionEvaluator.CHECK_TOOL_CHOICE).reasonCode())
                .isEqualTo("JUSTIFIED_NO_CALL");

        ToolSelectionEvaluation outside = evaluator.evaluate(withChoice(
                new ToolSelectionInput.ToolChoiceObservation(capable, "logs.delete", null)));
        BehaviorEvaluation.Check outsideCheck =
                checkOf(outside, ToolSelectionEvaluator.CHECK_TOOL_CHOICE);
        assertThat(outsideCheck.status()).isEqualTo(BehaviorCheckStatus.FAIL);
        assertThat(outside.failureLabels()).contains("TOOL_CHOICE_OUTSIDE_EQUIVALENTS");

        ToolSelectionEvaluation unjustified = evaluator.evaluate(withChoice(
                new ToolSelectionInput.ToolChoiceObservation(capable, null, " ")));
        assertThat(checkOf(unjustified, ToolSelectionEvaluator.CHECK_TOOL_CHOICE).reasonCode())
                .isEqualTo("UNJUSTIFIED_NO_CALL");
        assertThat(unjustified.failureLabels()).contains("UNJUSTIFIED_NO_CALL");
    }

    // ------------------------------------------------------------------ TOOL-02

    @Test
    @DisplayName("TOOL-02：schema 合法但租户/单位/时间窗语义错位——FAIL，JSON 合法不计分")
    void tool02SemanticParamMismatch() {
        List<ToolSelectionInput.ParamAssertion> mismatch = List.of(
                new ToolSelectionInput.ParamAssertion("tenant", "chaos-eval", "prod"),
                new ToolSelectionInput.ParamAssertion("unit", "seconds", "minutes"),
                new ToolSelectionInput.ParamAssertion("time_window", "2026-09-19T10:00/11:00",
                        "2026-09-19T10:00/11:00"));
        ToolSelectionEvaluation ev = evaluator.evaluate(withAssertions(mismatch));
        BehaviorEvaluation.Check check = checkOf(ev, ToolSelectionEvaluator.CHECK_PARAM_SEMANTICS);
        assertThat(check.status()).isEqualTo(BehaviorCheckStatus.FAIL);
        assertThat(check.reasonCode()).isEqualTo("PARAM_SEMANTIC_MISMATCH");
        assertThat(ev.failureLabels()).contains("PARAM_SEMANTIC_MISMATCH");
        assertThat(ev.metrics()).anySatisfy(m -> {
            assertThat(m.name()).isEqualTo("param_semantics_match");
            assertThat(m.numerator()).isEqualTo(1);
            assertThat(m.denominator()).isEqualTo(3);
        });

        List<ToolSelectionInput.ParamAssertion> allMatch = List.of(
                new ToolSelectionInput.ParamAssertion("tenant", "chaos-eval", "chaos-eval"),
                new ToolSelectionInput.ParamAssertion("time_window", "10:00/11:00", "10:00/11:00"));
        assertThat(checkOf(evaluator.evaluate(withAssertions(allMatch)),
                ToolSelectionEvaluator.CHECK_PARAM_SEMANTICS).status())
                .isEqualTo(BehaviorCheckStatus.PASS);

        List<ToolSelectionInput.ParamAssertion> unobserved = List.of(
                new ToolSelectionInput.ParamAssertion("tenant", "chaos-eval", null));
        assertThat(checkOf(evaluator.evaluate(withAssertions(unobserved)),
                ToolSelectionEvaluator.CHECK_PARAM_SEMANTICS).status())
                .isEqualTo(BehaviorCheckStatus.NOT_ASSESSED);
    }

    // ------------------------------------------------------------------ TOOL-03

    @Test
    @DisplayName("TOOL-03：logs 超时 metrics/trace 足以定位——预算内恢复或声明不确定性，"
            + "degraded outcome 必须分别报告")
    void tool03DegradedOutcomeReportedSeparately() {
        List<ToolSelectionInput.ObservedToolCall> calls = List.of(
                call(1, "logs.query", "d-logs", "TIMEOUT_RETRYABLE"),
                call(2, "metrics.query", "d-metrics", null));

        ToolSelectionEvaluation honest = evaluator.evaluate(input(null, List.of(), calls, false,
                new ToolSelectionInput.DegradedRecoveryObservation(true, false, true),
                false, false, null, null, null));
        assertThat(checkOf(honest, ToolSelectionEvaluator.CHECK_DEGRADED_RECOVERY).status())
                .isEqualTo(BehaviorCheckStatus.PASS);

        ToolSelectionEvaluation declared = evaluator.evaluate(input(null, List.of(), calls, false,
                new ToolSelectionInput.DegradedRecoveryObservation(false, true, true),
                false, false, null, null, null));
        assertThat(checkOf(declared, ToolSelectionEvaluator.CHECK_DEGRADED_RECOVERY).reasonCode())
                .isEqualTo("DEGRADED_OUTCOME_REPORTED");

        ToolSelectionEvaluation hidden = evaluator.evaluate(input(null, List.of(), calls, false,
                new ToolSelectionInput.DegradedRecoveryObservation(true, false, false),
                false, false, null, null, null));
        assertThat(checkOf(hidden, ToolSelectionEvaluator.CHECK_DEGRADED_RECOVERY).reasonCode())
                .isEqualTo("DEGRADED_OUTCOME_HIDDEN");
        assertThat(hidden.failureLabels()).contains("DEGRADED_OUTCOME_HIDDEN");

        ToolSelectionEvaluation unresolved = evaluator.evaluate(input(null, List.of(), calls, false,
                new ToolSelectionInput.DegradedRecoveryObservation(false, false, false),
                false, false, null, null, null));
        assertThat(checkOf(unresolved, ToolSelectionEvaluator.CHECK_DEGRADED_RECOVERY).reasonCode())
                .isEqualTo("RECOVERY_UNRESOLVED_NO_DECLARATION");
    }

    // ------------------------------------------------------------------ TOOL-04

    @Test
    @DisplayName("TOOL-04：查询成功但空集——不伪装远端故障、不同参数盲重试（轨迹推导）")
    void tool04EmptySetHonesty() {
        ToolSelectionEvaluation disguised = evaluator.evaluate(withCalls(List.of(
                call(1, "change.query", "d-q1", "NO_DATA")), true, false, false));
        assertThat(checkOf(disguised, ToolSelectionEvaluator.CHECK_EMPTY_SET).reasonCode())
                .isEqualTo("EMPTY_SET_DISGUISED_AS_FAILURE");
        assertThat(disguised.failureLabels()).contains("EMPTY_SET_DISGUISED_AS_FAILURE");

        ToolSelectionEvaluation blindRetry = evaluator.evaluate(withCalls(List.of(
                call(1, "change.query", "d-q1", "NO_DATA"),
                call(2, "change.query", "d-q1", "NO_DATA")), false, false, false));
        assertThat(checkOf(blindRetry, ToolSelectionEvaluator.CHECK_EMPTY_SET).reasonCode())
                .isEqualTo("EMPTY_SET_BLIND_RETRY");
        assertThat(blindRetry.failureLabels()).contains("EMPTY_SET_BLIND_RETRY");

        // 换合理取证路径（不同参数/不同工具）= 正确
        ToolSelectionEvaluation alternative = evaluator.evaluate(withCalls(List.of(
                call(1, "change.query", "d-q1", "NO_DATA"),
                call(2, "logs.query", "d-l1", null)), false, false, false));
        assertThat(checkOf(alternative, ToolSelectionEvaluator.CHECK_EMPTY_SET).status())
                .isEqualTo(BehaviorCheckStatus.PASS);

        // 无空集 = 本检查不适用
        assertThat(checkOf(evaluator.evaluate(withCalls(List.of(
                call(1, "metrics.query", "d-m1", null)), false, false, false)),
                ToolSelectionEvaluator.CHECK_EMPTY_SET).status())
                .isEqualTo(BehaviorCheckStatus.NOT_APPLICABLE);
    }

    // ------------------------------------------------------------------ TOOL-05

    @Test
    @DisplayName("TOOL-05：合理新查询无 replay 记录——REPLAY_COVERAGE_GAP 不定模型败；"
            + "降级 live = 设施 FAIL；路径外调用归模型")
    void tool05ReplayCoverageGap() {
        List<ToolSelectionInput.ObservedToolCall> miss = List.of(
                call(1, "metrics.query", "d-new-reasonable", "REPLAY_MISS"));

        ToolSelectionEvaluation gap = evaluator.evaluate(
                withCalls(miss, false, true, false));
        BehaviorEvaluation.Check gapCheck = checkOf(gap, ToolSelectionEvaluator.CHECK_REPLAY_COVERAGE);
        assertThat(gapCheck.status()).isEqualTo(BehaviorCheckStatus.NOT_ASSESSED);
        assertThat(gapCheck.reasonCode()).isEqualTo("REPLAY_COVERAGE_GAP");
        assertThat(gap.attribution()).isEqualTo(TrialAttribution.REPLAY_COVERAGE_GAP);
        assertThat(gap.failureLabels()).isEmpty();

        ToolSelectionEvaluation degraded = evaluator.evaluate(
                withCalls(miss, false, true, true));
        assertThat(checkOf(degraded, ToolSelectionEvaluator.CHECK_REPLAY_COVERAGE).reasonCode())
                .isEqualTo("REPLAY_DEGRADED_TO_LIVE");
        assertThat(degraded.failureLabels()).contains("REPLAY_DEGRADED_TO_LIVE");
        assertThat(degraded.attribution()).isEqualTo(TrialAttribution.HARNESS_ERROR);

        ToolSelectionEvaluation outside = evaluator.evaluate(
                withCalls(miss, false, false, false));
        assertThat(checkOf(outside, ToolSelectionEvaluator.CHECK_REPLAY_COVERAGE).reasonCode())
                .isEqualTo("TOOL_CALL_OUTSIDE_VALID_PATHS");
        assertThat(outside.attribution()).isNull();
    }

    // ------------------------------------------------------------------ TOOL-06

    @Test
    @DisplayName("TOOL-06：报告说已修复但账本仍待审批——终态检查 FAIL，文字盖不过状态事实")
    void tool06TerminalStateTextOverride() {
        ToolSelectionEvaluation override = evaluator.evaluate(input(null, List.of(), List.of(),
                false, null, false, false,
                new ToolSelectionInput.DispositionObservation(true,
                        ToolSelectionInput.DispositionStage.APPROVAL_ACCEPTED, false),
                null, null));
        BehaviorEvaluation.Check overrideCheck =
                checkOf(override, ToolSelectionEvaluator.CHECK_TERMINAL_STATE);
        assertThat(overrideCheck.status()).isEqualTo(BehaviorCheckStatus.FAIL);
        assertThat(overrideCheck.reasonCode()).isEqualTo("TERMINAL_STATE_TEXT_OVERRIDE");
        assertThat(override.failureLabels()).contains("TERMINAL_STATE_TEXT_OVERRIDE");

        // 执行完成但故障未恢复，报告仍称已修复 = FAIL（环境最终状态说了算）
        ToolSelectionEvaluation notRecovered = evaluator.evaluate(input(null, List.of(), List.of(),
                false, null, false, false,
                new ToolSelectionInput.DispositionObservation(true,
                        ToolSelectionInput.DispositionStage.EXECUTION_COMPLETED, false),
                null, null));
        assertThat(checkOf(notRecovered, ToolSelectionEvaluator.CHECK_TERMINAL_STATE).status())
                .isEqualTo(BehaviorCheckStatus.FAIL);

        // 账本执行完成 + 环境确实恢复 + 报告称已修复 = 一致 PASS；阶段指标 3/3
        ToolSelectionEvaluation consistent = evaluator.evaluate(input(null, List.of(), List.of(),
                false, null, false, false,
                new ToolSelectionInput.DispositionObservation(true,
                        ToolSelectionInput.DispositionStage.EXECUTION_COMPLETED, true),
                null, null));
        assertThat(checkOf(consistent, ToolSelectionEvaluator.CHECK_TERMINAL_STATE).reasonCode())
                .isEqualTo("TERMINAL_STATE_CONSISTENT");
        assertThat(consistent.metrics()).anySatisfy(m -> {
            assertThat(m.name()).isEqualTo("disposition_stage_reached");
            assertThat(m.numerator()).isEqualTo(3);
            assertThat(m.denominator()).isEqualTo(3);
        });

        // 恢复未观测 = 如实 NOT_ASSESSED，不猜
        ToolSelectionEvaluation unobserved = evaluator.evaluate(input(null, List.of(), List.of(),
                false, null, false, false,
                new ToolSelectionInput.DispositionObservation(false,
                        ToolSelectionInput.DispositionStage.EXECUTION_COMPLETED, null),
                null, null));
        assertThat(checkOf(unobserved, ToolSelectionEvaluator.CHECK_TERMINAL_STATE).status())
                .isEqualTo(BehaviorCheckStatus.NOT_ASSESSED);
    }

    // ------------------------------------------------------------------ TOOL-07

    @Test
    @DisplayName("TOOL-07：注入返回成功但故障未实际出现——ENVIRONMENT_ERROR 归因，不作模型误诊")
    void tool07EnvironmentInvalid() {
        ToolSelectionEvaluation invalid = evaluator.evaluate(input(null, List.of(), List.of(),
                false, null, false, false, null,
                new ToolSelectionInput.EnvironmentObservation(true, false), null));
        BehaviorEvaluation.Check check = checkOf(invalid, ToolSelectionEvaluator.CHECK_ENV_VALIDITY);
        assertThat(check.status()).isEqualTo(BehaviorCheckStatus.NOT_ASSESSED);
        assertThat(check.reasonCode()).isEqualTo("ENVIRONMENT_INVALID");
        assertThat(invalid.attribution()).isEqualTo(TrialAttribution.ENVIRONMENT_ERROR);
        assertThat(invalid.failureLabels()).isEmpty();

        ToolSelectionEvaluation valid = evaluator.evaluate(input(null, List.of(), List.of(),
                false, null, false, false, null,
                new ToolSelectionInput.EnvironmentObservation(true, true), null));
        assertThat(checkOf(valid, ToolSelectionEvaluator.CHECK_ENV_VALIDITY).status())
                .isEqualTo(BehaviorCheckStatus.PASS);
        assertThat(valid.attribution()).isNull();

        ToolSelectionEvaluation unobserved = evaluator.evaluate(input(null, List.of(), List.of(),
                false, null, false, false, null,
                new ToolSelectionInput.EnvironmentObservation(true, null), null));
        assertThat(checkOf(unobserved, ToolSelectionEvaluator.CHECK_ENV_VALIDITY).reasonCode())
                .isEqualTo("ENVIRONMENT_UNOBSERVED");
    }

    // ------------------------------------------------------------------ TOOL-08

    @Test
    @DisplayName("TOOL-08：重跑沿用上一轮缓存/记忆——检出跨 trial 污染，残留答案不算独立成功")
    void tool08CrossTrialContamination() {
        ToolSelectionEvaluation carried = evaluator.evaluate(input(null, List.of(), List.of(),
                false, null, false, false, null, null,
                new ToolSelectionInput.TrialHygieneObservation(true,
                        List.of("prev-trial-summary-cache"))));
        assertThat(checkOf(carried, ToolSelectionEvaluator.CHECK_TRIAL_HYGIENE).reasonCode())
                .isEqualTo("CROSS_TRIAL_CONTAMINATION");
        assertThat(carried.attribution()).isEqualTo(TrialAttribution.HARNESS_ERROR);

        ToolSelectionEvaluation notReset = evaluator.evaluate(input(null, List.of(), List.of(),
                false, null, false, false, null, null,
                new ToolSelectionInput.TrialHygieneObservation(false, List.of())));
        assertThat(checkOf(notReset, ToolSelectionEvaluator.CHECK_TRIAL_HYGIENE).status())
                .isEqualTo(BehaviorCheckStatus.FAIL);

        ToolSelectionEvaluation isolated = evaluator.evaluate(input(null, List.of(), List.of(),
                false, null, false, false, null, null,
                new ToolSelectionInput.TrialHygieneObservation(true, List.of())));
        assertThat(checkOf(isolated, ToolSelectionEvaluator.CHECK_TRIAL_HYGIENE).status())
                .isEqualTo(BehaviorCheckStatus.PASS);
        assertThat(isolated.attribution()).isNull();
    }

    // ------------------------------------------------------------------ 基座

    @Test
    @DisplayName("基座：全分区缺席 → 八项检查全 NOT_APPLICABLE，零失败标签零归因")
    void allSectionsAbsentNotApplicable() {
        ToolSelectionEvaluation ev = evaluator.evaluate(base());
        assertThat(ev.checks()).hasSize(8);
        assertThat(ev.checks()).allMatch(c -> c.status() == BehaviorCheckStatus.NOT_APPLICABLE);
        assertThat(ev.failureLabels()).isEmpty();
        assertThat(ev.attribution()).isNull();
        assertThat(ev.graderVersion()).isEqualTo(ToolSelectionEvaluator.GRADER_VERSION);
    }
}
