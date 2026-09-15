package com.objwww.pr.control.alert.application.approval;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * 消费模板审批门（PC-C2，§2.8 步骤 1/2 的窄端口）：Plan 面只看见这四个动作——
 * 查活 grant（四元匹配：run+digest+快照锚+policy，过期/撤销/换代结构性作废）→
 * 配额预留 CAS → 签发 single-use 授权 → 同事务消费（ISSUED→CONSUMED+operation 回填）。
 * PostgresApprovalStore 同体实现（ApprovalStore 的窄视图）。
 */
public interface ApprovalPlannerGate {

    Optional<ApprovalStore.GrantView> findActiveGrant(UUID runId, String actionDigest,
            String scopeSnapshotHash, String policyVersion);

    boolean tryReserveGrantQuota(UUID grantId, Instant at);

    UUID insertAuthorization(UUID grantId, Instant at);

    boolean consumeAuthorization(UUID authzId, UUID operationId, Instant at);
}
