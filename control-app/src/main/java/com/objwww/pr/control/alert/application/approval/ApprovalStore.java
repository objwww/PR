package com.objwww.pr.control.alert.application.approval;

import com.objwww.pr.control.alert.domain.approval.DecisionQuorum;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 审批四账本端口（PC-C1，V119）：request/decisions/grant/authorization。
 * 全部方法 join 调用方事务；CAS 类方法返回 false = 并发赢家是别人（幂等让步）。
 */
public interface ApprovalStore {

    /** rca_run.generation 快照（observed_generation 锚源，§2.4） */
    int generationOfRun(UUID runId);

    // ---------------- request ----------------

    record RequestView(UUID requestId, UUID intentId, UUID runId, String actionId,
            String actionDigest, int observedGeneration, int requiredApprovers,
            String scopeSnapshotHash, String policyVersion, String state,
            Instant expiresAt) {
    }

    void insertRequest(RequestView request);

    Optional<RequestView> findRequest(UUID requestId);

    /** UNIQUE(request_id, approver_id) 冲突 = false（同人重复决策） */
    boolean insertDecision(UUID requestId, String approverId, String approverRole,
            boolean approved, Instant at);

    List<DecisionQuorum.Decision> listDecisions(UUID requestId);

    /** 请求状态 CAS（decidedAt 仅 APPROVED/DENIED 落）；voidReason 仅作废态落 */
    boolean markRequestState(UUID requestId, String expectedState, String nextState,
            Instant decidedAt, String voidReason);

    /** PENDING 且过期 → EXPIRED；返回被清扫的 (requestId, runId) */
    List<ExpiredRequest> expirePendingRequests(Instant now);

    record ExpiredRequest(UUID requestId, UUID runId) {
    }

    /**
     * 待审批列表行（前端产品化波次1 审批处置页）：审批请求 + 已投票计数聚合。
     * 波次2 补齐审批对象详情：目标资源/关键参数/工具版本/范围快照/关联告警/
     * 策略版本——全部来自 action_intent 与 rca_run→incident 精确键 join，
     * 环节缺席如实 null（不冒充）。
     */
    record PendingRequestView(UUID requestId, UUID intentId, UUID runId, String actionId,
            String risk, int requiredApprovers, Instant requestedAt, Instant expiresAt,
            int approvedCount, int deniedCount, String toolName, String toolVersion,
            String argsJson, String resolvedResourceUid, String scopeSnapshotJson,
            String incidentKey, String policyVersion) {
    }

    /**
     * PENDING 审批清单（requested_at 降序，上限 50）。default 抛出 = 假件未镜像
     * （actionAssessmentStats 同款先例）——真实 PG 实现覆盖。
     */
    default List<PendingRequestView> listPendingRequests(Instant now) {
        throw new UnsupportedOperationException(
                "listPendingRequests 仅 Postgres 实现（前端产品化波次1）");
    }

    // ---------------- grant ----------------

    record GrantView(UUID grantId, UUID requestId, UUID runId, String actionId,
            String actionDigest, String scopeSnapshotHash, String policyVersion,
            String scopeKind, int maxOperations, int issuedOperations, String state,
            Instant expiresAt) {
    }

    void insertGrant(GrantView grant);

    /** 消费模板查活 grant（digest+快照锚+policy+run 四元匹配） */
    Optional<GrantView> findActiveGrant(UUID runId, String actionDigest,
            String scopeSnapshotHash, String policyVersion);

    /** 签发配额 CAS：ACTIVE 且 issued<max 才 +1；返回 false = 无配额/非 ACTIVE */
    boolean tryReserveGrantQuota(UUID grantId, Instant at);

    Optional<GrantView> findGrant(UUID grantId);

    boolean markGrantState(UUID grantId, String expectedState, String nextState, Instant at);

    List<ExpiredRequest> expireActiveGrants(Instant now);

    // ---------------- operation_authorization ----------------

    UUID insertAuthorization(UUID grantId, Instant at);

    /** single-use CAS：ISSUED→CONSUMED + operation_id 回填；false = 已消费/不存在 */
    boolean consumeAuthorization(UUID authzId, UUID operationId, Instant at);

    Optional<AuthzView> findAuthorization(UUID authzId);

    record AuthzView(UUID authzId, UUID grantId, UUID operationId, String state) {
    }
}
