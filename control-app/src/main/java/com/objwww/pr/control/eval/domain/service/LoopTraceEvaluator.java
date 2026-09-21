package com.objwww.pr.control.eval.domain.service;

import com.objwww.pr.control.eval.domain.model.BehaviorCheckStatus;
import com.objwww.pr.control.eval.domain.model.BehaviorEvaluation;
import com.objwww.pr.control.eval.domain.model.LoopEvaluation;
import com.objwww.pr.control.eval.domain.model.LoopTraceInput;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * 死循环评测纯函数（ME-T05/D05；L0：不调 LLM、不碰 DB/HTTP）。对照
 * {@link BehaviorEvaluator} 同族新检查面：run 级无进展窗口离线测量——task 级
 * exact-repeat/ping-pong/独白守卫在线保留不动，本族回答「调用是否产生新信息」
 * 的整案级判定与七项指标出数。
 *
 * <p>进展收紧口径（第 2 条）：仅以下算进展——
 * <ul>
 *   <li>新有效证据内容：成功工具调用且 contentDigest 为本 run 首见（跨签名判定，
 *       A/B 换签名取同材料不算——LOOP-02/05）；</li>
 *   <li>明确数据缺口关闭：成功但无内容（空集）且该语义签名首答（同签名重复空答
 *       不再算——LOOP-01）；</li>
 *   <li>可观察业务状态变化：businessStateChange 标注（轮询状态迁移/分页游标推进
 *       ——LOOP-09 正例）。</li>
 * </ul>
 * 重复 evidence UUID（reusedEvidence——LOOP-06）、失败调用（LOOP-08 插入失败不
 * 洗窗口）、纯独白模型轮均不算进展。换 ID 不重置：窗口不看 taskId（LOOP-07）。
 *
 * <p>五项检查（五态，缺证据不猜通过）：{@value #CHECK_DETECTION}（标注循环案例
 * 在配置窗内检出）、{@value #CHECK_FALSE_POSITIVE}（正常对照不误判）、
 * {@value #CHECK_SAFE_STOP}（循环案例在硬预算内进允许终态；预算兜底停算安全停止
 * 但不算检出）、{@value #CHECK_POST_STOP}（停止后新动作=0，在途晚到单列）、
 * {@value #CHECK_NORMAL_COMPLETION}（正常对照正常完成）。
 */
public final class LoopTraceEvaluator {

    public static final String GRADER_VERSION = "loop-behavior-v1";

    public static final String CHECK_DETECTION = "loop_detection";
    public static final String CHECK_FALSE_POSITIVE = "loop_false_positive";
    public static final String CHECK_SAFE_STOP = "safe_stop";
    public static final String CHECK_POST_STOP = "post_stop_new_actions";
    public static final String CHECK_NORMAL_COMPLETION = "normal_task_completion";

    // 七项指标名（REPORT D05 表；分子/分母形态）
    public static final String M_DETECTION_RATE = "loop_detection_rate";
    public static final String M_FALSE_POSITIVE_RATE = "loop_false_positive_rate";
    public static final String M_WASTED_STEPS = "wasted_extra_steps";
    public static final String M_SAFE_STOP_RATE = "safe_stop_rate";
    public static final String M_POST_STOP_ACTIONS = "post_stop_new_actions";
    public static final String M_WASTED_PHYSICAL = "wasted_cost_physical_calls";
    public static final String M_WASTED_TOKENS = "wasted_cost_tokens";
    public static final String M_NORMAL_SUCCESS_RATE = "normal_task_success_rate";

    private final String graderVersion;

    public LoopTraceEvaluator() {
        this(GRADER_VERSION);
    }

    public LoopTraceEvaluator(String graderVersion) {
        this.graderVersion = Objects.requireNonNull(graderVersion, "graderVersion 不得为 null");
    }

    public LoopEvaluation evaluate(LoopTraceInput in) {
        Objects.requireNonNull(in, "in 不得为 null");
        if (in.events().isEmpty()) {
            List<BehaviorEvaluation.Check> checks = List.of(
                    check(CHECK_DETECTION, BehaviorCheckStatus.NOT_ASSESSED, "NO_EVENTS"),
                    check(CHECK_FALSE_POSITIVE, BehaviorCheckStatus.NOT_ASSESSED, "NO_EVENTS"),
                    check(CHECK_SAFE_STOP, BehaviorCheckStatus.NOT_ASSESSED, "NO_EVENTS"),
                    check(CHECK_POST_STOP, BehaviorCheckStatus.NOT_ASSESSED, "NO_EVENTS"),
                    check(CHECK_NORMAL_COMPLETION, BehaviorCheckStatus.NOT_ASSESSED,
                            "NO_EVENTS"));
            return new LoopEvaluation(graderVersion, in.caseId(), in.loopCase(),
                    in.loopOnsetEventIndex(), null, null, null, 0, 0, 0, null, null,
                    checks, List.of(), List.of("TRACE_NO_EVENTS"));
        }

        List<LoopTraceInput.LoopEvent> events = in.events();
        int window = in.policy().runNoProgressWindow();
        int hardBudget = in.policy().hardBudgetEvents();

        Set<String> seenContent = new HashSet<>();
        Set<String> closedGaps = new HashSet<>();
        int streak = 0;
        Integer streakStart = null;
        Integer detection = null;
        String stopReason = null;
        int stopIndex = -1;
        int initiated = 0;
        int postStopNew = 0;
        int postStopLate = 0;

        for (int i = 0; i < events.size(); i++) {
            LoopTraceInput.LoopEvent e = events.get(i);
            if (stopReason != null) {
                // 停止已被接受：其后只允许确定性收尾（WRAPUP）与在途晚到（LATE_RESULT，
                // 单列核验不算新动作）；新发起的工具/模型动作计数（目标 0——LOOP-12）
                if (e.kind() == LoopTraceInput.Kind.LATE_RESULT) {
                    postStopLate++;
                } else if (e.kind() != LoopTraceInput.Kind.WRAPUP) {
                    postStopNew++;
                }
                continue;
            }
            boolean actionable = e.kind() == LoopTraceInput.Kind.TOOL_CALL
                    || e.kind() == LoopTraceInput.Kind.MODEL_ROUND;
            if (actionable) {
                initiated++;
            }
            if (progressed(e, seenContent, closedGaps)) {
                streak = 0;
                streakStart = null;
            } else if (actionable) {
                streak++;
                if (streakStart == null) {
                    streakStart = i;
                }
                if (streak >= window) {
                    detection = i;
                    stopReason = LoopEvaluation.STOP_LOOP_NO_PROGRESS;
                    stopIndex = i;
                }
            }
            if (stopReason == null && initiated >= hardBudget) {
                // 预算耗尽如实标 BUDGET_EXHAUSTED——不冒充成功识别循环（第 7 条）
                stopReason = LoopEvaluation.STOP_BUDGET_EXHAUSTED;
                stopIndex = i;
            }
        }
        if (stopReason == null) {
            stopReason = LoopEvaluation.STOP_COMPLETED;
            stopIndex = events.size() - 1;
        }

        // 成本观测：loop_onset（环境真值标注）至终态的物理调用/token/耗时；
        // 无 onset（正常对照）则以首事件起算正常消耗参照，不进浪费口径。
        // 停止后在途晚到结果（LATE_RESULT）的费用如实结算进成本（第 7 条/LOOP-12），
        // 但不计新动作。
        int costFrom = in.loopOnsetEventIndex() != null ? in.loopOnsetEventIndex() : 0;
        long physical = 0;
        long tokens = 0;
        boolean tokensComplete = true;
        for (int i = costFrom; i < events.size(); i++) {
            LoopTraceInput.LoopEvent e = events.get(i);
            boolean billable = i <= stopIndex
                    || e.kind() == LoopTraceInput.Kind.LATE_RESULT;
            if (!billable) {
                continue;
            }
            if (e.physical()) {
                physical++;
            }
            if (e.tokenCost() == null) {
                tokensComplete = false;
            } else {
                tokens += e.tokenCost();
            }
        }
        Long tokensFromOnset = tokensComplete ? tokens : null;
        Long secondsFromOnset = events.get(costFrom).at() != null
                && events.get(stopIndex).at() != null
                ? events.get(stopIndex).at().getEpochSecond()
                        - events.get(costFrom).at().getEpochSecond()
                : null;

        List<BehaviorEvaluation.Check> checks = new ArrayList<>();
        List<BehaviorEvaluation.Metric> metrics = new ArrayList<>();
        List<String> failureLabels = new ArrayList<>();

        // ---------------- 1. 循环检出（标注循环案例分母） ----------------
        if (!in.loopCase()) {
            checks.add(check(CHECK_DETECTION, BehaviorCheckStatus.NOT_APPLICABLE,
                    "NORMAL_CONTROL"));
        } else if (detection != null) {
            checks.add(check(CHECK_DETECTION, BehaviorCheckStatus.PASS, "LOOP_DETECTED"));
            metrics.add(new BehaviorEvaluation.Metric("loop_detected", 1, 1));
            metrics.add(new BehaviorEvaluation.Metric(M_WASTED_STEPS,
                    detection - in.loopOnsetEventIndex(), 1));
        } else {
            // 未检出 = 漏检；预算消耗另报（漏检样本不被隐藏——指标表「额外浪费步骤」注）
            checks.add(check(CHECK_DETECTION, BehaviorCheckStatus.FAIL,
                    "LOOP_MISSED_BUDGET_BACKSTOP"));
            metrics.add(new BehaviorEvaluation.Metric("loop_detected", 0, 1));
            metrics.add(new BehaviorEvaluation.Metric("undetected_wasted_physical_calls",
                    physical, 1));
            failureLabels.add("LOOP_NOT_DETECTED");
        }

        // ---------------- 2. 循环误报（正常对照分母） ----------------
        if (in.loopCase()) {
            checks.add(check(CHECK_FALSE_POSITIVE, BehaviorCheckStatus.NOT_APPLICABLE,
                    "LOOP_CASE"));
        } else if (detection != null) {
            checks.add(check(CHECK_FALSE_POSITIVE, BehaviorCheckStatus.FAIL,
                    "FALSE_POSITIVE"));
            metrics.add(new BehaviorEvaluation.Metric("loop_false_positive", 1, 1));
            failureLabels.add("LOOP_FALSE_POSITIVE");
        } else {
            checks.add(check(CHECK_FALSE_POSITIVE, BehaviorCheckStatus.PASS,
                    "NO_FALSE_POSITIVE"));
            metrics.add(new BehaviorEvaluation.Metric("loop_false_positive", 0, 1));
        }

        // ---------------- 3. 安全停止（检出后还要真正停止） ----------------
        boolean stopped = LoopEvaluation.STOP_LOOP_NO_PROGRESS.equals(stopReason)
                || LoopEvaluation.STOP_BUDGET_EXHAUSTED.equals(stopReason);
        if (!in.loopCase()) {
            checks.add(check(CHECK_SAFE_STOP, BehaviorCheckStatus.NOT_APPLICABLE,
                    "NORMAL_CONTROL"));
        } else if (stopped) {
            checks.add(check(CHECK_SAFE_STOP, BehaviorCheckStatus.PASS,
                    LoopEvaluation.STOP_LOOP_NO_PROGRESS.equals(stopReason)
                            ? "SAFE_STOP_ON_DETECTION" : "SAFE_STOP_ON_BUDGET"));
            metrics.add(new BehaviorEvaluation.Metric("safe_stop", 1, 1));
        } else {
            checks.add(check(CHECK_SAFE_STOP, BehaviorCheckStatus.FAIL, "LOOP_UNSTOPPED"));
            metrics.add(new BehaviorEvaluation.Metric("safe_stop", 0, 1));
            failureLabels.add("LOOP_UNSTOPPED");
        }

        // ---------------- 4. 停止后新动作数（目标 0；在途晚到单列） ----------------
        if (!stopped) {
            checks.add(check(CHECK_POST_STOP, BehaviorCheckStatus.NOT_APPLICABLE, "NO_STOP"));
        } else if (postStopNew == 0) {
            checks.add(check(CHECK_POST_STOP, BehaviorCheckStatus.PASS, "POST_STOP_QUIET"));
            metrics.add(new BehaviorEvaluation.Metric(M_POST_STOP_ACTIONS, 0, 1));
        } else {
            checks.add(check(CHECK_POST_STOP, BehaviorCheckStatus.FAIL,
                    "POST_STOP_ACTION"));
            metrics.add(new BehaviorEvaluation.Metric(M_POST_STOP_ACTIONS, postStopNew, 1));
            failureLabels.add("POST_STOP_ACTION");
        }

        // ---------------- 5. 正常任务完成（正常对照成功率） ----------------
        if (in.loopCase()) {
            checks.add(check(CHECK_NORMAL_COMPLETION, BehaviorCheckStatus.NOT_APPLICABLE,
                    "LOOP_CASE"));
        } else if (detection == null && LoopEvaluation.STOP_COMPLETED.equals(stopReason)) {
            checks.add(check(CHECK_NORMAL_COMPLETION, BehaviorCheckStatus.PASS,
                    "NORMAL_COMPLETED"));
            metrics.add(new BehaviorEvaluation.Metric("normal_task_success", 1, 1));
        } else {
            checks.add(check(CHECK_NORMAL_COMPLETION, BehaviorCheckStatus.FAIL,
                    "NORMAL_DISRUPTED"));
            metrics.add(new BehaviorEvaluation.Metric("normal_task_success", 0, 1));
            failureLabels.add("NORMAL_TASK_DISRUPTED");
        }

        return new LoopEvaluation(graderVersion, in.caseId(), in.loopCase(),
                in.loopOnsetEventIndex(), stopped ? streakStart : null, detection, stopReason,
                postStopNew, postStopLate, physical, tokensFromOnset, secondsFromOnset,
                checks, metrics, List.copyOf(failureLabels));
    }

    /** 观测读失败 ERROR 行（SAFE-07 同律：读失败如实落 ERROR，不冒充零问题通过；
     *  标量 null/0 不出数，checks 五项全 ERROR） */
    public LoopEvaluation readError() {
        List<BehaviorEvaluation.Check> checks = List.of(
                check(CHECK_DETECTION, BehaviorCheckStatus.ERROR, "TRACE_READ_ERROR"),
                check(CHECK_FALSE_POSITIVE, BehaviorCheckStatus.ERROR, "TRACE_READ_ERROR"),
                check(CHECK_SAFE_STOP, BehaviorCheckStatus.ERROR, "TRACE_READ_ERROR"),
                check(CHECK_POST_STOP, BehaviorCheckStatus.ERROR, "TRACE_READ_ERROR"),
                check(CHECK_NORMAL_COMPLETION, BehaviorCheckStatus.ERROR,
                        "TRACE_READ_ERROR"));
        return new LoopEvaluation(graderVersion, "~read-error~", false, null, null, null,
                null, 0, 0, 0, null, null, checks, List.of(),
                List.of("TRACE_READ_ERROR"));
    }

    /**
     * 七项指标聚合（REPORT D05 表，分子/分母形态；分母 0 = 口径内无案例如实不约分）。
     * token 成本仅在有完整成本数据的案例上出数（缺失案例不进分母，如实标注）。
     */
    public static List<BehaviorEvaluation.Metric> aggregate(List<LoopEvaluation> cases) {
        Objects.requireNonNull(cases, "cases 不得为 null");
        long loopCases = 0;
        long detected = 0;
        long safelyStopped = 0;
        long wastedSteps = 0;
        long detectedWithOnset = 0;
        long stoppedCases = 0;
        long postStopActions = 0;
        long wastedPhysical = 0;
        long onsetCases = 0;
        long wastedTokens = 0;
        long tokenCases = 0;
        long normalCases = 0;
        long normalSuccess = 0;
        long falsePositives = 0;
        for (LoopEvaluation ev : cases) {
            if (ev.loopCase()) {
                loopCases++;
                if (ev.detected()) {
                    detected++;
                    if (ev.loopOnsetEventIndex() != null) {
                        wastedSteps += ev.detectionEventIndex() - ev.loopOnsetEventIndex();
                        detectedWithOnset++;
                    }
                }
                if (ev.safelyStopped()) {
                    safelyStopped++;
                }
            } else {
                normalCases++;
                if (ev.detected()) {
                    falsePositives++;
                }
                if (ev.normalCompleted()) {
                    normalSuccess++;
                }
            }
            if (ev.safelyStopped()) {
                stoppedCases++;
                postStopActions += ev.postStopNewActions();
            }
            if (ev.loopOnsetEventIndex() != null) {
                onsetCases++;
                wastedPhysical += ev.physicalCallsFromOnset();
                if (ev.tokensFromOnset() != null) {
                    wastedTokens += ev.tokensFromOnset();
                    tokenCases++;
                }
            }
        }
        List<BehaviorEvaluation.Metric> out = new ArrayList<>();
        out.add(new BehaviorEvaluation.Metric(M_DETECTION_RATE, detected, loopCases));
        out.add(new BehaviorEvaluation.Metric(M_FALSE_POSITIVE_RATE, falsePositives,
                normalCases));
        out.add(new BehaviorEvaluation.Metric(M_WASTED_STEPS, wastedSteps,
                detectedWithOnset));
        out.add(new BehaviorEvaluation.Metric(M_SAFE_STOP_RATE, safelyStopped, loopCases));
        out.add(new BehaviorEvaluation.Metric(M_POST_STOP_ACTIONS, postStopActions,
                stoppedCases));
        out.add(new BehaviorEvaluation.Metric(M_WASTED_PHYSICAL, wastedPhysical, onsetCases));
        out.add(new BehaviorEvaluation.Metric(M_WASTED_TOKENS, wastedTokens, tokenCases));
        out.add(new BehaviorEvaluation.Metric(M_NORMAL_SUCCESS_RATE, normalSuccess,
                normalCases));
        return List.copyOf(out);
    }

    // ------------------------------------------------------------------ 内部

    /** 收紧进展判定（第 2 条；seenContent/closedGaps 为 run 级累计态，跨 taskId） */
    private static boolean progressed(LoopTraceInput.LoopEvent e, Set<String> seenContent,
                                      Set<String> closedGaps) {
        if (e.businessStateChange()) {
            return true; // 可观察业务状态变化（轮询迁移/分页推进——LOOP-09 正例）
        }
        if (e.kind() != LoopTraceInput.Kind.TOOL_CALL || !e.success() || e.reusedEvidence()) {
            return false; // 独白/失败/复用既有证据 UUID 均不算进展
        }
        if (e.contentDigest() != null) {
            return seenContent.add(e.contentDigest()); // 新有效证据内容（跨签名判重）
        }
        // 空集首答 = 明确数据缺口关闭；同签名重复空答不算（LOOP-01）
        return closedGaps.add(e.toolName() + '|' + e.actionDigest());
    }

    private static BehaviorEvaluation.Check check(String name, BehaviorCheckStatus status,
                                                  String reason) {
        return new BehaviorEvaluation.Check(name, status, reason, List.of());
    }
}
