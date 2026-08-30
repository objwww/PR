package com.objwww.pr.control.domain.model;

/**
 * Run 模式（与 V1 ck_review_run_mode 一致）。
 * 非 NORMAL 模式必须 publisher_disabled=true（V1 ck_replay_publisher_disabled）。
 */
public enum RunMode {
    NORMAL,
    PROJECTION_REBUILD,
    RECORDED_REPLAY,
    ISOLATED_REEXECUTION
}
