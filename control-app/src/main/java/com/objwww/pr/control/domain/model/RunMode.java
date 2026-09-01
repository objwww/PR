package com.objwww.pr.control.domain.model;

/**
 * Run 模式（与 V4 ck_review_run_mode 一致）。
 * 回放/重建类模式必须 publisher_disabled=true；REPAIR 例外，允许发布 repair 命令
 * （V4 ck_replay_publisher_disabled，RM2-10）。
 */
public enum RunMode {
    NORMAL,
    PROJECTION_REBUILD,
    RECORDED_REPLAY,
    ISOLATED_REEXECUTION,
    REPAIR
}
