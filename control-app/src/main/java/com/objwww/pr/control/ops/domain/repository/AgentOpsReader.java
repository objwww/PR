package com.objwww.pr.control.ops.domain.repository;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * UI-6 监控大盘聚合只读端口（rca_run/rca_task/operator_case/notify_outbox/
 * external_invocation_ledger 五表 SQL 聚合；零写面——全部表 control_app 既有 SELECT）。
 *
 * <p>口径（全部 SQL 侧聚合，服务层只钉 generatedAt）：
 * <ul>
 *   <li>activeRuns = rca_run QUEUED+RUNNING+REPORTING（RcaRunState.isActive 同口径）；</li>
 *   <li>awaitingReviewRuns = SUCCEEDED+PARTIAL（RunQueryService C-18④ review 桶口径：
 *       报告待人工复核，暂无复核状态机——诚实近似）；</li>
 *   <li>readyTasks/oldestReadyWaitSeconds = rca_task READY 计数与最老 ready_since 等待
 *       （无 READY 任务 → 如实 null，IncidentQueryReader.RunsStats 同律）；</li>
 *   <li>openCases = operator_case OPEN+ACKED（OperatorQueryService tabs.all 同口径）；</li>
 *   <li>notifyOutboxPending = notify_outbox 未终态在途 = PENDING+CLAIMED+RETRY_WAIT；
 *       notifyOutboxFailed24h = 近 24h 落 DEAD（投递终败，updated_at 窗口）；</li>
 *   <li>llmCalls24h/tokens24h = rca_model_call 近 24h 行数与 usage->>'total_tokens'
 *       求和（引擎收敛后唯一在写的模型调用账本；external_invocation_ledger 已停写——
 *       2026-09-16 前端产品化波次1 切源；无数据 → tokens 如实 null，不回填 0）；</li>
 *   <li>topTools24h = rca_tool_invocation 按 tool_name 分组 Top5（真实工具名）。</li>
 * </ul>
 */
public interface AgentOpsReader {

    /** 分组计数行（真实工具名，源自 rca_tool_invocation） */
    record ToolCallCount(String tool, long calls) {
    }

    /** 大盘聚合（不含 generatedAt——时刻由服务层钉） */
    record AgentOpsAggregate(long activeRuns, long awaitingReviewRuns, long readyTasks,
                             Long oldestReadyWaitSeconds, long openCases,
                             long notifyOutboxPending, long notifyOutboxFailed24h,
                             long llmCalls24h, Long tokens24h,
                             List<ToolCallCount> topTools24h) {
    }

    /**
     * 执行器活性投影行（监控页「执行器」区，方案 §三.12）：
     * 按租约活动推导，非心跳注册表——无 worker 注册表，仅有数据源是 rca_attempt/
     * rca_task 租约、eval_run_command 与 drill_job 的领取列。source ∈ rca/eval/drill；
     * lastActivityAt = 窗口内最近一次租约相关时刻；inFlightTasks = 当前在飞任务数。
     */
    record WorkerActivity(String source, String workerId, Instant lastActivityAt,
                          long inFlightTasks) {
    }

    /**
     * OP-03 动作分析聚合（近窗 rca_action_assessment 行的 SQL 侧计数）：
     * duplicateRate 分母=logicalActions（分母为 0 时服务层如实回 0 并显式分母）；
     * 未被分析的 Run 是 NOT_ASSESSED，不在这组计数里当 0 价值。
     */
    record ActionAssessmentStats(long logicalActions, long duplicateActions,
                                 long newObservations, long confirmsOrRefutes,
                                 long noData, long sourceFailed, long undetermined,
                                 long assessedRuns) {
    }

    /** 聚合（now 参与 oldestReadyWait 计算与 24h 窗） */
    AgentOpsAggregate summary(Instant now);

    /**
     * 近 window 内有租约活动的 worker 清单（lastActivityAt 降序）；
     * 空清单 = 窗口内无租约活动，如实返回，不编造在线 worker。
     */
    List<WorkerActivity> workerActivity(Instant now, Duration window);

    /**
     * OP-03：动作分析行聚合（since 窗口按 computed_at）。default 抛出 = 假件
     * 环境未镜像（reclaimPendingOlderThan 同款先例）——真实 PG 实现覆盖。
     */
    default ActionAssessmentStats actionAssessmentStats(Instant since) {
        throw new UnsupportedOperationException(
                "actionAssessmentStats 仅 Postgres 读面实现（OP-03）");
    }

    /**
     * 分层延迟（前端产品化波次1，腾讯云 Agent 可观测口径；本批 §3.10 补任务层成四层）：
     * run = 创建→完成，task = rca_task 终态就绪→落定（coalesce(ready_since, created_at)→updated_at），
     * llm = rca_model_call.latency_ms，tool = rca_tool_invocation 起点→结算；
     * 近 24h 窗、只统计有完结值的行，p50/p95 = percentile_cont。
     */
    record LatencyLayers(long runs, Long runP50Ms, Long runP95Ms,
                         long taskCalls, Long taskP50Ms, Long taskP95Ms,
                         long llmCalls, Long llmP50Ms, Long llmP95Ms,
                         long toolCalls, Long toolP50Ms, Long toolP95Ms) {
    }

    /** 分层延迟聚合（since 窗口起点）。default 抛出 = 假件未镜像，PG 实现覆盖。 */
    default LatencyLayers latencyLayers(Instant since) {
        throw new UnsupportedOperationException(
                "latencyLayers 仅 Postgres 读面实现（前端产品化波次1）");
    }

    /** 成本归因单模型行（§3.10 Wave4：rca_model_call 按 requested_model 聚合，定价回算真值） */
    record ModelCost(String model, long calls, Long totalTokens, Long costMicros) {
    }

    /**
     * 成本归因聚合（近 24h）：models 按成本降序；totalCostMicros 只计有价行，
     * unpricedCalls = 无定价行数（如实透出，不把无价当 0 元混入总额）。
     */
    record CostBreakdown(List<ModelCost> models, Long totalCostMicros, String currency,
                         long unpricedCalls) {
    }

    /** 成本归因（since 窗口起点）。default 抛出 = 假件未镜像，PG 实现覆盖。 */
    default CostBreakdown costs(Instant since) {
        throw new UnsupportedOperationException("costs 仅 Postgres 读面实现（§3.10 Wave4）");
    }

    /** 风险审计事件行（kind ∈ GUARDIAN/APPROVAL_REJECTED/QUARANTINE/DEAD_LETTER，runId 可空） */
    record RiskEvent(String kind, Instant at, String runId, String title) {
    }

    /** 风险审计流（since 窗口，时间降序）：Guardian 复核/审批拒绝/隔离与死信命中，锚 run 轨迹。 */
    default List<RiskEvent> riskEvents(Instant since) {
        throw new UnsupportedOperationException("riskEvents 仅 Postgres 读面实现（§3.10 Wave4）");
    }

    /** 单窗口性能概览（§3.10：AI 请求数/平均耗时/错误/模型调用/Token/成本） */
    record PerfWindow(long calls, Long tokens, Long costMicros, Long avgLatencyMs, long errors) {
    }

    /** 环比双窗（当前 24h vs 上一 24h；窗口滚动不重叠）。default 抛出 = 假件未镜像。 */
    default PerfTrend perfTrend(Instant now) {
        throw new UnsupportedOperationException("perfTrend 仅 Postgres 读面实现（§3.10 环比）");
    }

    record PerfTrend(PerfWindow current, PerfWindow previous) {
    }
}
