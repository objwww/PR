package com.objwww.pr.control.alert.application.agent;

import java.util.UUID;

/**
 * 压缩消费观测 append 口（ME-T12/D08，V164 rca_compaction_consumption）。
 *
 * <p>BoundedLlmRoleRunner.maybeCompact 在压缩 COMMITTED 且带消费观测时逐次 append
 * 一行：mode/summary_committed/consumer_invoked/consumed/policy_digest 五件如实
 * （consumed 未观测 null 不猜），评测侧按 run 取最新行拼 ContextDrift 评分输入
 * （ConsumptionFace）。落库失败不打断主路径（压缩是优化，观测面同律 fail-soft）。
 *
 * <p>默认 NOOP：既有装配未接端口时零行为漂移（与 compaction=null 同姿态）。
 */
public interface CompactionConsumptionPort {

    /** 零写默认实现（既有装配未接观测面时零漂移） */
    CompactionConsumptionPort NOOP = (runId, taskId, summaryDigest, mode,
            summaryCommitted, consumerInvoked, consumed, policyDigest) -> { };

    void record(UUID runId, UUID taskId, String summaryDigest, String mode,
                boolean summaryCommitted, boolean consumerInvoked, Boolean consumed,
                String policyDigest);
}
