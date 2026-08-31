package com.objwww.pr.publisher.domain.model;

import com.objwww.pr.shared.PublicationResourceState;
import com.objwww.pr.shared.PublicationResourceType;

import java.util.Objects;
import java.util.UUID;

/**
 * DriftReconciler 的巡检目标（M1-T08，方案 §4.6）：publication_resource 行 +
 * 其创建命令（JOIN outbox_command 同行取出——探针复用需要完整命令上下文，
 * 事件的 review_run_id/pr_revision_id 挂载也从命令取）。
 */
public record DriftCheckTarget(
        UUID resourceId,
        PublicationResourceType resourceType,
        String remoteId,
        String remoteUrl,
        String marker,
        PublicationResourceState state,
        int checkErrorCount,
        ClaimedCommand command) {

    public DriftCheckTarget {
        Objects.requireNonNull(resourceId, "resourceId");
        Objects.requireNonNull(resourceType, "resourceType");
        Objects.requireNonNull(remoteId, "remoteId");
        Objects.requireNonNull(state, "state");
        Objects.requireNonNull(command, "command");
    }
}
