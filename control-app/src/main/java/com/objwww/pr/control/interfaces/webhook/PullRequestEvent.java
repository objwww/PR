package com.objwww.pr.control.interfaces.webhook;

import java.util.Objects;

/**
 * 解析后的 pull_request 事件（M0 只关心 opened/synchronize/reopened）。
 * deliveryId 是 X-GitHub-Delivery 头，作为 run_key 的 trigger 组分（B-3 重投幂等的前提）。
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
        String baseSha) {

    public PullRequestEvent {
        Objects.requireNonNull(deliveryId, "deliveryId");
        Objects.requireNonNull(action, "action");
        Objects.requireNonNull(repositoryFullName, "repositoryFullName");
        Objects.requireNonNull(prState, "prState");
        Objects.requireNonNull(headSha, "headSha");
        Objects.requireNonNull(baseRef, "baseRef");
        Objects.requireNonNull(baseSha, "baseSha");
    }
}
