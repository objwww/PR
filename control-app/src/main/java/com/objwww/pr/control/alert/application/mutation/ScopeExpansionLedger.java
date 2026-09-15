package com.objwww.pr.control.alert.application.mutation;

import java.time.Instant;
import java.util.UUID;

/**
 * 授权扩张台账端口（PB-B2，V115 scope_expansion）：扩张逐条独立审批锚定——
 * {@code recordPending} 只落 PENDING_APPROVAL（锚位 approval_id 留空，Phase C
 * 审批面回填后 DDL 才允许 APPROVED）；B4 消费模板只认
 * {@link #hasApprovedExpansion}，无锚扩张永不进入计划/执行面（B 组不变量）。
 */
public interface ScopeExpansionLedger {

    UUID recordPending(UUID runId, String requestedKey, String resolvedResourceUid,
            String reason, String snapshotJson, String snapshotHash, Instant at);

    /** B4 消费闸：该 run 是否存在对此资源的已锚定（APPROVED）扩张 */
    boolean hasApprovedExpansion(UUID runId, String resourceUid);
}
