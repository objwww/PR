package com.objwww.pr.control.ops.domain.repository;

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
 *   <li>llmCalls24h/tokens24h = external_invocation_ledger 近 24h 行数与
 *       total_tokens 求和（无数据 → tokens 如实 null，不回填 0）；</li>
 *   <li>topTools24h = 账本无工具名列（V7 列面只有 model）——按 model 分组 Top5，
 *       契约键名仍叫 tool（偏差已报备）。</li>
 * </ul>
 */
public interface AgentOpsReader {

    /** 分组计数行（tool 实为 model 列值——账本无工具名列的诚实替代） */
    record ToolCallCount(String tool, long calls) {
    }

    /** 大盘聚合（不含 generatedAt——时刻由服务层钉） */
    record AgentOpsAggregate(long activeRuns, long awaitingReviewRuns, long readyTasks,
                             Long oldestReadyWaitSeconds, long openCases,
                             long notifyOutboxPending, long notifyOutboxFailed24h,
                             long llmCalls24h, Long tokens24h,
                             List<ToolCallCount> topTools24h) {
    }

    /** 聚合（now 参与 oldestReadyWait 计算与 24h 窗） */
    AgentOpsAggregate summary(Instant now);
}
