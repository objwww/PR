package com.objwww.pr.control.interfaces.webhook;

import java.time.Instant;
import java.util.Objects;

/**
 * 解析后的 pull_request 事件（M1 六 action，方案 §4.4）。
 * deliveryId 是 X-GitHub-Delivery 头，作为 run_key 的 trigger 组分（B-3 重投幂等的前提）。
 *
 * <p>updatedAt 来自 {@code pull_request.updated_at}（ISO-8601），**可缺失/可非法 → null**
 * （M1-T05）：它是 LWW 快筛（StaleEventGuard）的唯一输入，缺失时不猜不补，
 * 直接放行给权威读（EX-18）。注意它只是"事件生成那一刻"的快照，Run 生死决策
 * 永远以权威读的远端值为准（方案 §4.3 修正 #6）。
 */
public record PullRequestEvent(
        String deliveryId,
        String action,
        long installationId,
        long repositoryId,
        String repositoryFullName,
        int prNumber,
        String prState,
        boolean draft,
        boolean merged,
        String headSha,
        String baseRef,
        String baseSha,
        Instant updatedAt) {

    public PullRequestEvent {
        Objects.requireNonNull(deliveryId, "deliveryId");
        Objects.requireNonNull(action, "action");
        Objects.requireNonNull(repositoryFullName, "repositoryFullName");
        Objects.requireNonNull(prState, "prState");
        Objects.requireNonNull(headSha, "headSha");
        Objects.requireNonNull(baseRef, "baseRef");
        Objects.requireNonNull(baseSha, "baseSha");
        // updatedAt 刻意允许 null（EX-18：缺字段不判、不猜，转权威读）
    }

    /** T05 权威读后以远端值重建事件（FULL_REVIEW 分支）：身份/触发字段不变，状态字段以远端为准 */
    public PullRequestEvent withRemoteState(String remoteState, boolean remoteDraft, boolean remoteMerged,
                                            String remoteHeadSha, String remoteBaseRef, String remoteBaseSha,
                                            Instant remoteUpdatedAt) {
        return new PullRequestEvent(deliveryId, action, installationId, repositoryId,
                repositoryFullName, prNumber, remoteState, remoteDraft, remoteMerged,
                remoteHeadSha, remoteBaseRef, remoteBaseSha, remoteUpdatedAt);
    }
}
