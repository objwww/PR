package com.objwww.pr.control.eval.domain.model;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * 死循环评测输入（ME-T05/D05；纯数据，L0 零框架依赖）：run 级事件投影 +
 * 环境真值标注。事件跨 taskId 整案观察——主/子任务换 ID 不重置（D05 第 5 条，
 * LOOP-07）。投影来自确定性脚本环境，事件时间戳为场景时钟值（第 8 条，禁真实
 * sleep）。
 *
 * <p>真值标注纪律（第 1 条）：{@code loopCase}/{@code loopOnsetEventIndex} 来自
 * 环境真值（脚本环境知道从哪个事件起本案再无新有效信息），不取被测守卫自判。
 * 循环案例必须给出 onset；正常对照 onset 必须为 null。
 *
 * <p>语义签名（第 3 条）：{@code actionDigest} 由投影侧用既有
 * ActionDigest/ArgsNormalizer 产物填充（时间范围/租户/对象 ID 是摘要成分，不可删；
 * 仅请求 ID/无意义排序等预先声明的噪声被规范化折叠）。本输入不重复实现规范化。
 */
public record LoopTraceInput(String caseId,
                             boolean loopCase,
                             Integer loopOnsetEventIndex,
                             DetectionPolicy policy,
                             List<LoopEvent> events) {

    public LoopTraceInput {
        Objects.requireNonNull(caseId, "caseId 不得为 null");
        Objects.requireNonNull(policy, "policy 不得为 null");
        Objects.requireNonNull(events, "events 不得为 null");
        events = List.copyOf(events);
        if (loopCase && loopOnsetEventIndex == null) {
            throw new IllegalArgumentException(
                    "循环案例必须标注 loop_onset_event（环境真值，不取被测守卫自判）: " + caseId);
        }
        if (!loopCase && loopOnsetEventIndex != null) {
            throw new IllegalArgumentException("正常对照不得标注 loop_onset_event: " + caseId);
        }
        if (loopOnsetEventIndex != null
                && (loopOnsetEventIndex < 0 || loopOnsetEventIndex >= events.size())) {
            throw new IllegalArgumentException("loop_onset_event 越界: " + loopOnsetEventIndex
                    + " / " + events.size());
        }
    }

    /**
     * 检测策略（离线测量窗）：runNoProgressWindow = run 级连续无进展事件数达窗即检出
     * （整案级，不按 taskId 重置）；hardBudgetEvents = 硬预算（已发起的工具/模型动作
     * 上限）——预算耗尽标 BUDGET_EXHAUSTED，不冒充循环检出（第 7 条）。生产配置应
     * 满足 窗 < 预算（检出先于兜底）；本记录不强制该序——窗口状态丢失（LOOP-11
     * 恢复边界）等兜底场景需要 窗 ≥ 预算 的形态如实测量。
     */
    public record DetectionPolicy(int runNoProgressWindow, int hardBudgetEvents) {
        public DetectionPolicy {
            if (runNoProgressWindow < 2) {
                throw new IllegalArgumentException(
                        "无进展窗至少 2（单次无进展不构成循环）: " + runNoProgressWindow);
            }
            if (hardBudgetEvents < 2) {
                throw new IllegalArgumentException("硬预算至少 2: " + hardBudgetEvents);
            }
        }
    }

    /** 事件类别：TOOL_CALL 工具调用 / MODEL_ROUND 模型轮（独白候选）/ WRAPUP 确定性收尾 / LATE_RESULT 停止后在途晚到结果 */
    public enum Kind { TOOL_CALL, MODEL_ROUND, WRAPUP, LATE_RESULT }

    /**
     * 单事件投影行（按发生序入场，列表序即事件序）。
     *
     * <p>进展判定相关字段（收紧口径，第 2 条）：contentDigest = 业务内容摘要（payload
     * 级，不含证据行 ID/落库时间；null = 无内容产出：失败/空集/独白）；重复
     * contentDigest、reusedEvidence（复用既有证据 UUID）、失败、空集、纯独白均
     * <b>不算</b>进展——新时间戳/新措辞若未改变业务内容则 digest 相同，天然不记
     * 进展。businessStateChange = 可观察业务状态变化（轮询状态迁移/分页游标推进等，
     * 由投影侧按环境真值标注），恒算进展。
     *
     * <p>physical = 是否真实触网（复用=false）；tokenCost 可空——任一事件缺失即
     * 该案例 token 成本不出数（如实标注，不拼凑）。
     */
    public record LoopEvent(String eventId, UUID taskId, Kind kind, String toolName,
                            String actionDigest, boolean physical, boolean reusedEvidence,
                            boolean success, String contentDigest,
                            boolean businessStateChange, String failureReason,
                            Long tokenCost, Instant at) {

        public LoopEvent {
            Objects.requireNonNull(eventId, "eventId 不得为 null");
            Objects.requireNonNull(kind, "kind 不得为 null");
            if (kind == Kind.TOOL_CALL) {
                Objects.requireNonNull(toolName, "TOOL_CALL 缺 toolName");
                Objects.requireNonNull(actionDigest, "TOOL_CALL 缺 actionDigest（语义签名）");
            }
        }
    }
}
