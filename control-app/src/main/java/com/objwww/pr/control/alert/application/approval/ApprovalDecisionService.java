package com.objwww.pr.control.alert.application.approval;

import com.objwww.pr.control.alert.domain.approval.DecisionQuorum;
import com.objwww.pr.control.alert.domain.event.RcaEventAppender;
import org.springframework.transaction.support.TransactionOperations;

import java.time.Clock;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * 审批决策面（PC-C1，§2.6/§2.5）：决策行入账（UNIQUE 拦同人重复）→ 域裁决
 * （two distinct principals + distinct roles / 任一 denied 即终态）→ APPROVED
 * 即签发 Grant（ONCE，可复用授权范围的最小形态；SESSION 随消费面节奏开放）。
 * 通知 fail-closed 的 5s/15s/45s 面随 AM8 管理面接入；本面只承认已落库决策。
 */
public class ApprovalDecisionService {

    private final ApprovalStore store;
    private final RcaEventAppender events;
    private final TransactionOperations tx;
    private final Duration grantTtl;
    private final Clock clock;

    public ApprovalDecisionService(ApprovalStore store, RcaEventAppender events,
            TransactionOperations tx, Duration grantTtl, Clock clock) {
        this.store = Objects.requireNonNull(store);
        this.events = Objects.requireNonNull(events);
        this.tx = Objects.requireNonNull(tx);
        if (grantTtl.isNegative() || grantTtl.isZero()) {
            throw new IllegalArgumentException("grant TTL 必须为正");
        }
        this.grantTtl = grantTtl;
        this.clock = Objects.requireNonNull(clock);
    }

    /** 记录一条决策并推进裁决；返回请求当前状态 */
    public String decide(UUID requestId, String approverId, String approverRole,
            boolean approved) {
        if (approverId == null || approverId.isBlank() || approverRole == null
                || approverRole.isBlank()) {
            throw new IllegalArgumentException("决策人身份/角色不得为空");
        }
        return tx.execute(status -> {
            ApprovalStore.RequestView request = store.findRequest(requestId)
                    .orElseThrow(() -> new IllegalArgumentException("审批不存在: " + requestId));
            if (!"PENDING".equals(request.state())) {
                throw new IllegalStateException("审批已终态（" + request.state()
                        + "），不接受新决策");
            }
            if (!store.insertDecision(requestId, approverId, approverRole, approved,
                    clock.instant())) {
                throw new IllegalStateException("同一人对同一审批只能一条决策（UNIQUE）");
            }
            var decisions = store.listDecisions(requestId);
            var outcome = DecisionQuorum.evaluate(request.requiredApprovers(), decisions);
            emit(requestId, "APPROVAL_DECISION_RECORDED", Map.of(
                    "approver_id", approverId,
                    "decision", approved ? "approved" : "denied"));
            switch (outcome) {
                case DENIED -> {
                    store.markRequestState(requestId, "PENDING", "DENIED",
                            clock.instant(), null);
                    emit(requestId, "APPROVAL_DENIED", Map.of("by", approverId));
                }
                case APPROVED -> {
                    store.markRequestState(requestId, "PENDING", "APPROVED",
                            clock.instant(), null);
                    UUID grantId = UUID.randomUUID();
                    store.insertGrant(new ApprovalStore.GrantView(grantId, requestId,
                            request.runId(), request.actionId(), request.actionDigest(),
                            request.scopeSnapshotHash(), request.policyVersion(), "ONCE",
                            1, 0, "ACTIVE", clock.instant().plus(grantTtl)));
                    emit(requestId, "APPROVAL_APPROVED", Map.of(
                            "grant_id", grantId.toString(),
                            "scope_kind", "ONCE"));
                    emit(requestId, "GRANT_ISSUED", Map.of(
                            "grant_id", grantId.toString(),
                            "action_digest", request.actionDigest(),
                            "snapshot_hash", request.scopeSnapshotHash(),
                            "policy_version", request.policyVersion()));
                }
                case PENDING -> {
                    // 等待更多 distinct principals——不加事件噪声
                }
            }
            return outcome.name();
        });
    }

    private void emit(UUID requestId, String type, Map<String, String> extra) {
        var request = store.findRequest(requestId);
        UUID runId = request.map(ApprovalStore.RequestView::runId).orElse(null);
        if (runId == null) {
            return;
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("kind", type);
        payload.put("request_id", requestId.toString());
        payload.putAll(extra);
        events.append(runId, new RcaEventAppender.EventDraft(UUID.randomUUID(), type,
                com.objwww.pr.control.alert.application.mutation.OperationPlanner
                        .CanonicalEventJson.canonicalize(payload)));
    }
}
