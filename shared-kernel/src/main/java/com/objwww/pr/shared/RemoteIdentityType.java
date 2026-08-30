package com.objwww.pr.shared;

/**
 * 远端身份探测策略类型（v2.2 §6.3 RemoteIdentityStrategy；与 V1 outbox_command.remote_identity_type 对齐）。
 */
public enum RemoteIdentityType {

    /** CREATE_CHECK：external_id = operation_id */
    EXTERNAL_ID,
    /** UPDATE_CHECK：已存 GitHub check run id */
    CHECK_RUN_ID,
    /** PUBLISH_REVIEW：review body 隐藏 Marker {@code <!-- ai-review:{operation_id} -->} */
    REVIEW_MARKER
}
