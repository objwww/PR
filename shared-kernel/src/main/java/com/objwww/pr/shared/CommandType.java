package com.objwww.pr.shared;

/**
 * M0 类型化命令白名单（B15；与 V1 ck_outbox_command_type 一致）。
 * CREATE_FIX_BRANCH / CREATE_CHILD_PR 属 M5。
 */
public enum CommandType {
    CREATE_CHECK,
    UPDATE_CHECK,
    PUBLISH_REVIEW
}
