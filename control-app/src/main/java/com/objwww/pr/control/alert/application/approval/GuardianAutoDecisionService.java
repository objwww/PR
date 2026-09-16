package com.objwww.pr.control.alert.application.approval;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Guardian 自动决策流（Phase E，§2.0 低危 R2 自动执行）：意图已过解锁注册表 →
 * Guardian 裁决 → SAFE 自动批准（approver=guardian:&lt;policy&gt;，role=GUARDIAN，
 * R2 单批即达 quorum → Grant 签发）→ 消费模板照常走真锚；UNSAFE 自动拒绝；
 * UNCERTAIN 保持 PENDING 转人工（现有 decide 面不动）。R3 永远不经此面
 * （Guardian 权限单调）。
 */
public class GuardianAutoDecisionService {

    private final MutationGuardian guardian;
    private final ApprovalRequestService requests;
    private final ApprovalDecisionService decisions;

    public GuardianAutoDecisionService(MutationGuardian guardian,
            ApprovalRequestService requests, ApprovalDecisionService decisions) {
        this.guardian = Objects.requireNonNull(guardian);
        this.requests = Objects.requireNonNull(requests);
        this.decisions = Objects.requireNonNull(decisions);
    }

    public record AutoOutcome(String verdict, UUID requestId, String approvalState,
            String reason) {
    }

    public AutoOutcome autoDecide(UUID intentId) {
        MutationGuardian.Review review = guardian.review(intentId);
        switch (review.verdict()) {
            case SAFE -> {
                UUID requestId = requests.request(intentId);
                String state = decisions.decide(requestId, guardian.guardianApproverId(),
                        "GUARDIAN", true);
                return new AutoOutcome("SAFE", requestId, state, review.reason());
            }
            case UNSAFE -> {
                UUID requestId = requests.request(intentId);
                String state = decisions.decide(requestId, guardian.guardianApproverId(),
                        "GUARDIAN", false);
                return new AutoOutcome("UNSAFE", requestId, state, review.reason());
            }
            default -> {
                // UNCERTAIN 转人工：不铸请求也不代决策——人工 decide 面接管
                return new AutoOutcome("UNCERTAIN", null, "PENDING_HUMAN", review.reason());
            }
        }
    }
}
