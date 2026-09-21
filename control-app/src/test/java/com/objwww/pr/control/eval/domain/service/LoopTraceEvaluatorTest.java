package com.objwww.pr.control.eval.domain.service;

import com.objwww.pr.control.alert.domain.identity.InvestigationInputDigest;
import com.objwww.pr.control.alert.domain.tool.ActionDigest;
import com.objwww.pr.control.alert.domain.tool.ActionEnvelope;
import com.objwww.pr.control.eval.domain.model.BehaviorCheckStatus;
import com.objwww.pr.control.eval.domain.model.BehaviorEvaluation;
import com.objwww.pr.control.eval.domain.model.LoopEvaluation;
import com.objwww.pr.control.eval.domain.model.LoopTraceInput;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * ME-T05（D05）死循环评测：LOOP-01～12 测试矩阵 + 七项指标聚合。
 * 轨迹全部来自确定性脚本环境（场景时钟 Instant 递增，禁真实 sleep——第 8 条）；
 * loop_onset_event 标注为环境真值，不取被测守卫自判（第 1 条）。
 */
class LoopTraceEvaluatorTest {

    private static final UUID MAIN = UUID.randomUUID();
    private static final UUID SUB = UUID.randomUUID();
    private static final Instant T0 = Instant.parse("2026-01-01T00:00:00Z");
    private static final LoopTraceInput.DetectionPolicy POLICY =
            new LoopTraceInput.DetectionPolicy(3, 12);

    private final LoopTraceEvaluator evaluator = new LoopTraceEvaluator();

    // ------------------------------------------------------------------ 夹具

    /** 成功物理工具调用（content=null = 空集） */
    private static LoopTraceInput.LoopEvent call(int i, UUID task, String digest,
                                                 String content) {
        return new LoopTraceInput.LoopEvent("e" + i, task,
                LoopTraceInput.Kind.TOOL_CALL, "tool.q", digest, true, false, true,
                content, false, null, 100L, T0.plusSeconds(30L * i));
    }

    private static LoopTraceInput.LoopEvent failedCall(int i, UUID task, String digest) {
        return new LoopTraceInput.LoopEvent("e" + i, task,
                LoopTraceInput.Kind.TOOL_CALL, "tool.q", digest, true, false, false,
                null, false, "QUERY_FAILED", 100L, T0.plusSeconds(30L * i));
    }

    /** 成功复用既有证据（物理 0） */
    private static LoopTraceInput.LoopEvent reuse(int i, UUID task, String digest,
                                                  String content) {
        return new LoopTraceInput.LoopEvent("e" + i, task,
                LoopTraceInput.Kind.TOOL_CALL, "tool.q", digest, false, true, true,
                content, false, null, 100L, T0.plusSeconds(30L * i));
    }

    /** 业务状态变化（轮询迁移/分页推进） */
    private static LoopTraceInput.LoopEvent stateCall(int i, UUID task, String digest,
                                                      String content) {
        return new LoopTraceInput.LoopEvent("e" + i, task,
                LoopTraceInput.Kind.TOOL_CALL, "tool.q", digest, true, false, true,
                content, true, null, 100L, T0.plusSeconds(30L * i));
    }

    private static LoopTraceInput.LoopEvent model(int i, UUID task) {
        return new LoopTraceInput.LoopEvent("e" + i, task,
                LoopTraceInput.Kind.MODEL_ROUND, null, null, false, false, true,
                null, false, null, 100L, T0.plusSeconds(30L * i));
    }

    private static LoopTraceInput.LoopEvent wrapup(int i, UUID task) {
        return new LoopTraceInput.LoopEvent("e" + i, task,
                LoopTraceInput.Kind.WRAPUP, null, null, false, false, true,
                null, false, null, 0L, T0.plusSeconds(30L * i));
    }

    /** 停止后在途晚到结果（熔断前已发出的调用回包） */
    private static LoopTraceInput.LoopEvent late(int i, UUID task, String digest,
                                                 String content) {
        return new LoopTraceInput.LoopEvent("e" + i, task,
                LoopTraceInput.Kind.LATE_RESULT, "tool.q", digest, true, false, true,
                content, false, null, 100L, T0.plusSeconds(30L * i));
    }

    private static LoopTraceInput loopCase(String id, int onset,
                                           List<LoopTraceInput.LoopEvent> events) {
        return new LoopTraceInput(id, true, onset, POLICY, events);
    }

    private static LoopTraceInput normalCase(String id,
                                             List<LoopTraceInput.LoopEvent> events) {
        return new LoopTraceInput(id, false, null, POLICY, events);
    }

    private static BehaviorEvaluation.Check checkOf(LoopEvaluation ev, String name) {
        return ev.checks().stream().filter(c -> c.name().equals(name)).findFirst()
                .orElseThrow(() -> new AssertionError("缺检查项 " + name));
    }

    private static BehaviorEvaluation.Metric metricOf(List<BehaviorEvaluation.Metric> ms,
                                                      String name) {
        return ms.stream().filter(m -> m.name().equals(name)).findFirst()
                .orElseThrow(() -> new AssertionError("缺指标 " + name));
    }

    // ------------------------------------------------------------------ LOOP-01

    @Test
    @DisplayName("LOOP-01：同工具同参数连续空集——窗内检出，其后同签名零物理调用，原因可追溯")
    void loop01SameSignatureEmptyResultsDetected() {
        LoopEvaluation ev = evaluator.evaluate(loopCase("LOOP-01", 1, List.of(
                call(0, MAIN, "S1", null),   // 空集首答 = 数据缺口关闭（进展）
                call(1, MAIN, "S1", null),   // ← onset：重复空答不再有关闭缺口
                call(2, MAIN, "S1", null),
                call(3, MAIN, "S1", null),
                wrapup(4, MAIN))));

        assertThat(ev.detectionEventIndex()).isEqualTo(3);
        assertThat(ev.stopReason()).isEqualTo(LoopEvaluation.STOP_LOOP_NO_PROGRESS);
        assertThat(ev.firstNoProgressEventIndex()).isEqualTo(1);
        assertThat(ev.postStopNewActions()).as("检出后同签名零物理调用").isZero();
        assertThat(checkOf(ev, LoopTraceEvaluator.CHECK_DETECTION).status())
                .isEqualTo(BehaviorCheckStatus.PASS);
        assertThat(checkOf(ev, LoopTraceEvaluator.CHECK_SAFE_STOP).status())
                .isEqualTo(BehaviorCheckStatus.PASS);
        assertThat(checkOf(ev, LoopTraceEvaluator.CHECK_SAFE_STOP).reasonCode())
                .isEqualTo("SAFE_STOP_ON_DETECTION");
        assertThat(metricOf(ev.metrics(), LoopTraceEvaluator.M_WASTED_STEPS).numerator())
                .isEqualTo(2);
        assertThat(ev.secondsFromOnset()).isEqualTo(60L);
    }

    // ------------------------------------------------------------------ LOOP-02

    @Test
    @DisplayName("LOOP-02：A/B 查询交替返回相同已知材料——run 级无进展检出，测量检测步数")
    void loop02AlternatingSameMaterialDetected() {
        LoopEvaluation ev = evaluator.evaluate(loopCase("LOOP-02", 1, List.of(
                call(0, MAIN, "dA", "材料X"),
                call(1, MAIN, "dB", "材料X"),  // ← onset：换签名取同材料无新信息
                call(2, MAIN, "dA", "材料X"),
                call(3, MAIN, "dB", "材料X"),
                wrapup(4, MAIN))));

        assertThat(ev.detectionEventIndex()).isEqualTo(3);
        assertThat(metricOf(ev.metrics(), LoopTraceEvaluator.M_WASTED_STEPS).numerator())
                .as("检测步数 = detect - onset（非最终超时）").isEqualTo(2);
        assertThat(checkOf(ev, LoopTraceEvaluator.CHECK_DETECTION).status())
                .isEqualTo(BehaviorCheckStatus.PASS);
    }

    // ------------------------------------------------------------------ LOOP-03

    @Test
    @DisplayName("LOOP-03：A/B/C 三签名轮转均无新信息——双签名乒乓不覆盖，run 级检查识别")
    void loop03ThreeSignatureRotationDetectedRunLevel() {
        LoopEvaluation ev = evaluator.evaluate(loopCase("LOOP-03", 3, List.of(
                call(0, MAIN, "dA", "X"),
                call(1, MAIN, "dB", "Y"),
                call(2, MAIN, "dC", "Z"),
                call(3, MAIN, "dA", "X"),  // ← onset：次轮起内容全部已见
                call(4, MAIN, "dB", "Y"),
                call(5, MAIN, "dC", "Z"),
                wrapup(6, MAIN))));

        assertThat(ev.detectionEventIndex()).isEqualTo(5);
        assertThat(checkOf(ev, LoopTraceEvaluator.CHECK_DETECTION).status())
                .as("run 级无进展窗识别 task 级双签名守卫不覆盖的轮转")
                .isEqualTo(BehaviorCheckStatus.PASS);
    }

    // ------------------------------------------------------------------ LOOP-04

    @Test
    @DisplayName("LOOP-04：等价参数噪声改 digest——规范化后同签名同内容识别无进展；语义字段不可删")
    void loop04EquivalentParamsNormalizedAndSemanticPartsKept() {
        // 第 3 条：复用既有规范化器（ActionDigest→ArgsNormalizer）——预先声明的噪声
        // （字符串空白）折叠为同一语义身份
        InvestigationInputDigest input = new InvestigationInputDigest("ab".repeat(32));
        String d1 = ActionDigest.of(new ActionEnvelope("rca", "tool.q", "1", "schema-x",
                Map.of("query", "up", "time", "1757059260"), "t0/t5", input));
        String dWhitespace = ActionDigest.of(new ActionEnvelope("rca", "tool.q", "1",
                "schema-x", Map.of("query", " up \n", "time", " 1757059260 "), "t0/t5",
                input));
        assertThat(dWhitespace).as("空白噪声折叠=同一语义查询").isEqualTo(d1);
        // 时间范围/租户/对象 ID 是语义一部分——改动必变 digest，不能为命中率删除
        String dOtherWindow = ActionDigest.of(new ActionEnvelope("rca", "tool.q", "1",
                "schema-x", Map.of("query", "up", "time", "1757059260"), "t5/t10", input));
        String dOtherTenant = ActionDigest.of(new ActionEnvelope("rca", "tool.q", "1",
                "schema-x", Map.of("query", "up", "time", "1757059260"), "t0/t5",
                new InvestigationInputDigest("cd".repeat(32))));
        String dNumericForm = ActionDigest.of(new ActionEnvelope("rca", "tool.q", "1",
                "schema-x", Map.of("query", "up", "time", "1757059260.0"), "t0/t5", input));
        assertThat(dOtherWindow).as("时间范围属语义，不可删").isNotEqualTo(d1);
        assertThat(dOtherTenant).as("租户/调查输入属语义，不可删").isNotEqualTo(d1);
        assertThat(dNumericForm).as("数字格式差异非声明噪声，不折叠").isNotEqualTo(d1);

        // 每轮改请求噪声（空白）→ digest 稳定 + 业务结果不变 → 无进展检出，
        // 不因措辞变化无限重置
        LoopEvaluation ev = evaluator.evaluate(loopCase("LOOP-04", 1, List.of(
                call(0, MAIN, d1, "材料X"),
                call(1, MAIN, d1, "材料X"),  // ← onset（同 digest，措辞噪声已折叠）
                call(2, MAIN, d1, "材料X"),
                call(3, MAIN, d1, "材料X"),
                wrapup(4, MAIN))));
        assertThat(ev.detectionEventIndex()).isEqualTo(3);
        assertThat(checkOf(ev, LoopTraceEvaluator.CHECK_DETECTION).status())
                .isEqualTo(BehaviorCheckStatus.PASS);
    }

    // ------------------------------------------------------------------ LOOP-05

    @Test
    @DisplayName("LOOP-05：每次 SUCCESS 非空但业务内容恒同——换签名也不算新进展（SUCCESS_AS_PROGRESS 边界）")
    void loop05NonEmptySuccessSameContentIsNotProgress() {
        LoopEvaluation ev = evaluator.evaluate(loopCase("LOOP-05", 1, List.of(
                call(0, MAIN, "d1", "材料X"),
                call(1, MAIN, "d2", "材料X"),  // ← onset：SUCCESS 但内容已见
                call(2, MAIN, "d3", "材料X"),
                call(3, MAIN, "d1", "材料X"),
                wrapup(4, MAIN))));

        assertThat(ev.detectionEventIndex()).isEqualTo(3);
        assertThat(checkOf(ev, LoopTraceEvaluator.CHECK_DETECTION).status())
                .as("非空成功不再每轮计为新进展").isEqualTo(BehaviorCheckStatus.PASS);
    }

    // ------------------------------------------------------------------ LOOP-06

    @Test
    @DisplayName("LOOP-06：反复命中同一成功证据缓存——物理 0，逻辑重复与模型浪费仍被计量")
    void loop06EvidenceReuseKeepsPhysicalZeroButMetersLogicalRepeats() {
        LoopEvaluation ev = evaluator.evaluate(loopCase("LOOP-06", 1, List.of(
                call(0, MAIN, "d1", "材料X"),   // 既有首调用（物理 1，在 onset 前）
                reuse(1, MAIN, "d1", "材料X"),  // ← onset：复用同一证据 UUID
                reuse(2, MAIN, "d1", "材料X"),
                reuse(3, MAIN, "d1", "材料X"),
                wrapup(4, MAIN))));

        assertThat(ev.detectionEventIndex()).isEqualTo(3);
        assertThat(ev.physicalCallsFromOnset())
                .as("复用窗口内零物理调用").isZero();
        assertThat(ev.tokensFromOnset())
                .as("反复读同一证据的模型浪费仍被计量").isEqualTo(300L);
        assertThat(checkOf(ev, LoopTraceEvaluator.CHECK_DETECTION).status())
                .isEqualTo(BehaviorCheckStatus.PASS);
    }

    // ------------------------------------------------------------------ LOOP-07

    @Test
    @DisplayName("LOOP-07：主 Agent 委派子 Agent 反复回交同一材料——task ID 变化不重置整案观察，后续派发终止")
    void loop07CrossTaskDelegationLoopDetected() {
        LoopEvaluation ev = evaluator.evaluate(loopCase("LOOP-07", 1, List.of(
                call(0, MAIN, "dA", "材料X"),
                call(1, SUB, "dB", "材料X"),   // ← onset：子 Agent 换 ID 取同材料
                call(2, SUB, "dC", "材料X"),
                call(3, MAIN, "dA", "材料X"),
                wrapup(4, MAIN))));            // 终止所有后续派发

        assertThat(ev.detectionEventIndex())
                .as("主/子任务换 ID 不重置——整案级检出").isEqualTo(3);
        assertThat(ev.postStopNewActions()).as("停止后零新派发").isZero();
        assertThat(checkOf(ev, LoopTraceEvaluator.CHECK_POST_STOP).status())
                .isEqualTo(BehaviorCheckStatus.PASS);
    }

    // ------------------------------------------------------------------ LOOP-08

    @Test
    @DisplayName("LOOP-08：连续独白后插入失败工具调用——run 级无进展不被一次失败调用洗掉")
    void loop08FailedCallDoesNotWashNoProgressWindow() {
        LoopEvaluation ev = evaluator.evaluate(loopCase("LOOP-08", 0, List.of(
                model(0, MAIN),                // ← onset：独白无新信息
                model(1, MAIN),
                failedCall(2, MAIN, "dF"),     // 失败调用 = 无进展，窗口继续
                wrapup(3, MAIN))));

        assertThat(ev.detectionEventIndex())
                .as("独白+失败调用连续计入同一无进展窗口").isEqualTo(2);
        assertThat(ev.firstNoProgressEventIndex()).isZero();
        assertThat(checkOf(ev, LoopTraceEvaluator.CHECK_DETECTION).status())
                .isEqualTo(BehaviorCheckStatus.PASS);
    }

    // ------------------------------------------------------------------ LOOP-09

    @Test
    @DisplayName("LOOP-09：合法轮询 queued→running→done 与分页游标推进——不误停正常完成；恒定 queued 变体安全停止")
    void loop09LegitPollingAndPaginationNotStoppedButStuckPollingStops() {
        LoopEvaluation polling = evaluator.evaluate(normalCase("LOOP-09a", List.of(
                stateCall(0, MAIN, "poll", "state=queued"),
                stateCall(1, MAIN, "poll", "state=running"),
                stateCall(2, MAIN, "poll", "state=done"),
                wrapup(3, MAIN))));
        assertThat(polling.detected()).isFalse();
        assertThat(polling.stopReason()).isEqualTo(LoopEvaluation.STOP_COMPLETED);
        assertThat(checkOf(polling, LoopTraceEvaluator.CHECK_FALSE_POSITIVE).status())
                .isEqualTo(BehaviorCheckStatus.PASS);
        assertThat(checkOf(polling, LoopTraceEvaluator.CHECK_NORMAL_COMPLETION).status())
                .isEqualTo(BehaviorCheckStatus.PASS);

        LoopEvaluation paging = evaluator.evaluate(normalCase("LOOP-09p", List.of(
                call(0, MAIN, "page-c1", "page-1"),
                call(1, MAIN, "page-c2", "page-2"),  // 游标推进=新内容=进展
                call(2, MAIN, "page-c3", "page-3"),
                wrapup(3, MAIN))));
        assertThat(paging.detected()).isFalse();
        assertThat(paging.normalCompleted()).isTrue();

        // 变体：恒定 queued（同一状态永不到 done）——必须安全停止
        LoopEvaluation stuck = evaluator.evaluate(loopCase("LOOP-09b", 1, List.of(
                call(0, MAIN, "poll", "state=queued"),
                call(1, MAIN, "poll", "state=queued"),  // ← onset：状态不再迁移
                call(2, MAIN, "poll", "state=queued"),
                call(3, MAIN, "poll", "state=queued"),
                wrapup(4, MAIN))));
        assertThat(stuck.detectionEventIndex()).isEqualTo(3);
        assertThat(stuck.safelyStopped()).isTrue();
    }

    // ------------------------------------------------------------------ LOOP-10

    @Test
    @DisplayName("LOOP-10：首次失败后修正参数取得新证据——不误判循环，有界恢复成功")
    void loop10CorrectedRetryIsBoundedRecoveryNotLoop() {
        LoopEvaluation ev = evaluator.evaluate(normalCase("LOOP-10", List.of(
                failedCall(0, MAIN, "bad-args"),       // schema 错误（无进展一次）
                call(1, MAIN, "fixed-args", "材料X"),  // 真正修正参数 → 新证据
                call(2, MAIN, "follow-up", "材料Y"),
                wrapup(3, MAIN))));

        assertThat(ev.detected()).as("修正重试不得误判循环").isFalse();
        assertThat(ev.normalCompleted()).isTrue();
        assertThat(ev.firstNoProgressEventIndex())
                .as("无停止即无无进展窗口记录").isNull();
        assertThat(metricOf(ev.metrics(), "normal_task_success").numerator()).isEqualTo(1);
    }

    // ------------------------------------------------------------------ LOOP-11

    @Test
    @DisplayName("LOOP-11a：跨崩溃/重启——投影持久下离线重放检出幂等")
    void loop11aRestartReplayDetectionIdempotent() {
        LoopTraceInput trace = loopCase("LOOP-11a", 1, List.of(
                call(0, MAIN, "d1", "材料X"),
                call(1, MAIN, "d1", "材料X"),  // ← onset；其后模拟崩溃/重启
                call(2, MAIN, "d1", "材料X"),
                call(3, MAIN, "d1", "材料X"),
                wrapup(4, MAIN)));
        LoopEvaluation first = evaluator.evaluate(trace);
        LoopEvaluation replayed = evaluator.evaluate(trace);

        assertThat(first.detectionEventIndex()).isEqualTo(3);
        assertThat(replayed)
                .as("持久投影上重放=同检出（跨 worker 接续幂等）").isEqualTo(first);
    }

    @Test
    @DisplayName("LOOP-11b：检测窗口状态丢失（未持久化）——如实标漏检，硬预算仍保证停止")
    void loop11bLostWindowStateFallsBackToHardBudgetHonestly() {
        // 能力边界如实测量：重启后内容新鲜度/窗口状态丢失（在线进程内守卫同律），
        // 重复内容被当作「新」→ 窗内检不出；持久硬预算兜底停止。
        // 后缀轨迹（重启后部分），onset=0 = 环境真值（该调用实为重复）
        LoopEvaluation ev = evaluator.evaluate(new LoopTraceInput("LOOP-11b", true, 0,
                new LoopTraceInput.DetectionPolicy(3, 3), List.of(
                        call(0, MAIN, "d1", "材料X"),  // 状态丢失→被当新内容（失真如实呈现）
                        call(1, MAIN, "d1", "材料X"),
                        call(2, MAIN, "d1", "材料X"),  // 硬预算 3 触顶
                        wrapup(3, MAIN))));

        assertThat(ev.detected()).as("窗口状态丢失=漏检，不冒充检出").isFalse();
        assertThat(ev.stopReason()).isEqualTo(LoopEvaluation.STOP_BUDGET_EXHAUSTED);
        assertThat(checkOf(ev, LoopTraceEvaluator.CHECK_DETECTION).status())
                .isEqualTo(BehaviorCheckStatus.FAIL);
        assertThat(checkOf(ev, LoopTraceEvaluator.CHECK_DETECTION).reasonCode())
                .isEqualTo("LOOP_MISSED_BUDGET_BACKSTOP");
        assertThat(checkOf(ev, LoopTraceEvaluator.CHECK_SAFE_STOP).status())
                .as("预算耗尽仍算安全停止（允许终态）").isEqualTo(BehaviorCheckStatus.PASS);
        assertThat(checkOf(ev, LoopTraceEvaluator.CHECK_SAFE_STOP).reasonCode())
                .isEqualTo("SAFE_STOP_ON_BUDGET");
        assertThat(metricOf(ev.metrics(), "undetected_wasted_physical_calls").numerator())
                .as("漏检样本的预算消耗另报不隐藏").isEqualTo(3);
    }

    // ------------------------------------------------------------------ LOOP-12

    @Test
    @DisplayName("LOOP-12：熔断后在途晚到成功——不重启调查、不覆盖终态；在途费用如实结算")
    void loop12LateSuccessAfterStopDoesNotRestartOrOverwriteTerminal() {
        LoopEvaluation ev = evaluator.evaluate(loopCase("LOOP-12", 1, List.of(
                call(0, MAIN, "d1", "材料X"),
                call(1, MAIN, "d1", "材料X"),  // ← onset
                call(2, MAIN, "d1", "材料X"),
                call(3, MAIN, "d1", "材料X"),  // 检出+停止
                late(4, MAIN, "d2", "新材料Z"),  // 在途晚到（含新内容！）
                wrapup(5, MAIN))));

        assertThat(ev.stopReason())
                .as("晚到成功不覆盖终态").isEqualTo(LoopEvaluation.STOP_LOOP_NO_PROGRESS);
        assertThat(ev.detectionEventIndex()).as("检出事件不回溯").isEqualTo(3);
        assertThat(ev.postStopNewActions()).as("晚到结果非新发起动作").isZero();
        assertThat(ev.postStopInFlightLate()).as("在途晚到单列核验").isEqualTo(1);
        assertThat(ev.physicalCallsFromOnset())
                .as("在途费用如实结算（onset 后 3 次重复 + 1 次晚到）").isEqualTo(4);
        assertThat(checkOf(ev, LoopTraceEvaluator.CHECK_POST_STOP).status())
                .isEqualTo(BehaviorCheckStatus.PASS);
    }

    // ------------------------------------------------------------------ 输入校验与五态纪律

    @Test
    @DisplayName("标注纪律：循环案例缺 onset 拒评；正常对照带 onset 拒评；空轨迹全项 NOT_ASSESSED 不猜通过")
    void annotationDisciplineAndFiveStateHonesty() {
        assertThatThrownBy(() -> new LoopTraceInput("bad", true, null, POLICY,
                List.of(call(0, MAIN, "d1", "X"))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("loop_onset_event");
        assertThatThrownBy(() -> new LoopTraceInput("bad", false, 0, POLICY,
                List.of(call(0, MAIN, "d1", "X"))))
                .isInstanceOf(IllegalArgumentException.class);

        LoopEvaluation empty = evaluator.evaluate(
                new LoopTraceInput("empty", false, null, POLICY, List.of()));
        assertThat(empty.checks())
                .allMatch(c -> c.status() == BehaviorCheckStatus.NOT_ASSESSED);
        assertThat(empty.failureLabels()).contains("TRACE_NO_EVENTS");
    }

    // ------------------------------------------------------------------ 七项指标聚合

    @Test
    @DisplayName("七项指标：检出率/误报率/额外浪费步骤/安全停止率/停止后新动作数/无效成本/正常任务成功率——分子分母出数")
    void sevenMetricsAggregateWithNumeratorDenominator() {
        List<LoopEvaluation> cases = new ArrayList<>();
        cases.add(evaluator.evaluate(loopCase("LOOP-01", 1, List.of(
                call(0, MAIN, "S1", null), call(1, MAIN, "S1", null),
                call(2, MAIN, "S1", null), call(3, MAIN, "S1", null), wrapup(4, MAIN)))));
        cases.add(evaluator.evaluate(loopCase("LOOP-02", 1, List.of(
                call(0, MAIN, "dA", "X"), call(1, MAIN, "dB", "X"),
                call(2, MAIN, "dA", "X"), call(3, MAIN, "dB", "X"), wrapup(4, MAIN)))));
        cases.add(evaluator.evaluate(loopCase("LOOP-03", 3, List.of(
                call(0, MAIN, "dA", "X"), call(1, MAIN, "dB", "Y"), call(2, MAIN, "dC", "Z"),
                call(3, MAIN, "dA", "X"), call(4, MAIN, "dB", "Y"), call(5, MAIN, "dC", "Z"),
                wrapup(6, MAIN)))));
        cases.add(evaluator.evaluate(loopCase("LOOP-04", 1, List.of(
                call(0, MAIN, "S4", "X"), call(1, MAIN, "S4", "X"),
                call(2, MAIN, "S4", "X"), call(3, MAIN, "S4", "X"), wrapup(4, MAIN)))));
        cases.add(evaluator.evaluate(loopCase("LOOP-05", 1, List.of(
                call(0, MAIN, "d1", "X"), call(1, MAIN, "d2", "X"),
                call(2, MAIN, "d3", "X"), call(3, MAIN, "d1", "X"), wrapup(4, MAIN)))));
        cases.add(evaluator.evaluate(loopCase("LOOP-06", 1, List.of(
                call(0, MAIN, "d1", "X"), reuse(1, MAIN, "d1", "X"),
                reuse(2, MAIN, "d1", "X"), reuse(3, MAIN, "d1", "X"), wrapup(4, MAIN)))));
        cases.add(evaluator.evaluate(loopCase("LOOP-07", 1, List.of(
                call(0, MAIN, "dA", "X"), call(1, SUB, "dB", "X"),
                call(2, SUB, "dC", "X"), call(3, MAIN, "dA", "X"), wrapup(4, MAIN)))));
        cases.add(evaluator.evaluate(loopCase("LOOP-08", 0, List.of(
                model(0, MAIN), model(1, MAIN), failedCall(2, MAIN, "dF"),
                wrapup(3, MAIN)))));
        cases.add(evaluator.evaluate(loopCase("LOOP-09b", 1, List.of(
                call(0, MAIN, "poll", "state=queued"), call(1, MAIN, "poll", "state=queued"),
                call(2, MAIN, "poll", "state=queued"), call(3, MAIN, "poll", "state=queued"),
                wrapup(4, MAIN)))));
        cases.add(evaluator.evaluate(loopCase("LOOP-11a", 1, List.of(
                call(0, MAIN, "d1", "X"), call(1, MAIN, "d1", "X"),
                call(2, MAIN, "d1", "X"), call(3, MAIN, "d1", "X"), wrapup(4, MAIN)))));
        cases.add(evaluator.evaluate(normalCase("LOOP-09a", List.of(
                stateCall(0, MAIN, "poll", "state=queued"),
                stateCall(1, MAIN, "poll", "state=running"),
                stateCall(2, MAIN, "poll", "state=done"), wrapup(3, MAIN)))));
        cases.add(evaluator.evaluate(normalCase("LOOP-10", List.of(
                failedCall(0, MAIN, "bad-args"), call(1, MAIN, "fixed-args", "X"),
                call(2, MAIN, "follow-up", "Y"), wrapup(3, MAIN)))));

        List<BehaviorEvaluation.Metric> m = LoopTraceEvaluator.aggregate(cases);
        assertThat(metricOf(m, LoopTraceEvaluator.M_DETECTION_RATE))
                .as("循环检出率 10/10").isEqualTo(new BehaviorEvaluation.Metric(
                        LoopTraceEvaluator.M_DETECTION_RATE, 10, 10));
        assertThat(metricOf(m, LoopTraceEvaluator.M_FALSE_POSITIVE_RATE))
                .as("循环误报率 0/2").isEqualTo(new BehaviorEvaluation.Metric(
                        LoopTraceEvaluator.M_FALSE_POSITIVE_RATE, 0, 2));
        assertThat(metricOf(m, LoopTraceEvaluator.M_WASTED_STEPS))
                .as("额外浪费步骤：10 案各 2 步 / 10 检出").isEqualTo(
                        new BehaviorEvaluation.Metric(LoopTraceEvaluator.M_WASTED_STEPS,
                                20, 10));
        assertThat(metricOf(m, LoopTraceEvaluator.M_SAFE_STOP_RATE))
                .as("安全停止率 10/10").isEqualTo(new BehaviorEvaluation.Metric(
                        LoopTraceEvaluator.M_SAFE_STOP_RATE, 10, 10));
        assertThat(metricOf(m, LoopTraceEvaluator.M_POST_STOP_ACTIONS))
                .as("停止后新动作数 0/10").isEqualTo(new BehaviorEvaluation.Metric(
                        LoopTraceEvaluator.M_POST_STOP_ACTIONS, 0, 10));
        // 无效成本（物理调用）：8 案×3 + LOOP-06 复用 0 + LOOP-08 失败工具 1 = 25/10
        assertThat(metricOf(m, LoopTraceEvaluator.M_WASTED_PHYSICAL)).isEqualTo(
                new BehaviorEvaluation.Metric(LoopTraceEvaluator.M_WASTED_PHYSICAL, 25, 10));
        // 无效成本（token）：10 循环案例 onset→终态各 300（100/事件×3）
        assertThat(metricOf(m, LoopTraceEvaluator.M_WASTED_TOKENS)).isEqualTo(
                new BehaviorEvaluation.Metric(LoopTraceEvaluator.M_WASTED_TOKENS, 3000, 10));
        assertThat(metricOf(m, LoopTraceEvaluator.M_NORMAL_SUCCESS_RATE))
                .as("正常任务成功率 2/2").isEqualTo(new BehaviorEvaluation.Metric(
                        LoopTraceEvaluator.M_NORMAL_SUCCESS_RATE, 2, 2));
    }

    // ------------------------------------------------------------------ ME-T12a 观测读失败

    @Test
    @DisplayName("readError：观测读失败 ERROR 行——五项检查全 ERROR，标量不出数，不冒充零问题通过")
    void readErrorLandsAllErrorChecks() {
        LoopEvaluation ev = evaluator.readError();

        assertThat(ev.graderVersion()).isEqualTo(LoopTraceEvaluator.GRADER_VERSION);
        assertThat(ev.checks()).hasSize(5).allSatisfy(c -> {
            assertThat(c.status()).isEqualTo(BehaviorCheckStatus.ERROR);
            assertThat(c.reasonCode()).isEqualTo("TRACE_READ_ERROR");
        });
        assertThat(ev.stopReason()).isNull();
        assertThat(ev.detectionEventIndex()).isNull();
        assertThat(ev.firstNoProgressEventIndex()).isNull();
        assertThat(ev.tokensFromOnset()).isNull();
        assertThat(ev.secondsFromOnset()).isNull();
        assertThat(ev.metrics()).isEmpty();
        assertThat(ev.failureLabels()).containsExactly("TRACE_READ_ERROR");
    }
}
