package com.objwww.pr.shared;

/**
 * publication_resource.resource_type（与 V1 ck_pub_resource_type 对齐）。
 * 漂移视角的资源现状见该表；命令历史仍在 outbox_command 八态（v2.2 §1 命令与资源分离）。
 */
public enum PublicationResourceType {
    CHECK_RUN,
    PR_COMMENT,
    REVIEW,
    REVIEW_COMMENT,
    /** M5 预留 */
    FIX_BRANCH,
    /** M5 预留 */
    CHILD_PR
}
