package com.objwww.pr.control.alert.application.mutation;

import com.objwww.pr.control.alert.application.approval.ApprovalRequestService;

import java.util.Objects;
import java.util.UUID;

/**
 * IntentFollowUp 生产形态（BA-171）：意图 OPEN 落账 → 权威资源解析
 * （{@link IntentResourceResolver} fail-closed——解析不出落 INTENT_RESOLVE_FAILED
 * 事件、意图保持未解析，永不进入授权面）→ 解析成功铸 approval_request（PENDING，
 * R3 自动 required_approvers=2，TTL 300s）。
 */
public class ApprovalQueueFollowUp implements IntentFollowUp {

    private final IntentResourceResolver resolver;
    private final ApprovalRequestService approvalRequests;

    public ApprovalQueueFollowUp(IntentResourceResolver resolver,
            ApprovalRequestService approvalRequests) {
        this.resolver = Objects.requireNonNull(resolver);
        this.approvalRequests = Objects.requireNonNull(approvalRequests);
    }

    @Override
    public void onIntentRecorded(UUID intentId, String requestedResourceKey) {
        IntentResourceResolver.Outcome outcome =
                resolver.resolveAndRecord(intentId, requestedResourceKey);
        if (outcome.resolved()) {
            approvalRequests.request(intentId);
        }
        // 解析失败：fail-closed 事件已落，意图留 OPEN 未解析——不猜不补
    }
}
