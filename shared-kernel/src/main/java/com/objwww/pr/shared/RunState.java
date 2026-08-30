package com.objwww.pr.shared;

/**
 * ReviewRun 十四态（与 V1 ck_review_run_state 一致）。
 * M0 实际使用子集：CREATED→SNAPSHOTTING→REVIEWING→REVIEW_COMPLETE→READY_TO_PUBLISH→PUBLISHING→COMPLETED；
 * PATCH_PROPOSED/WAITING_APPROVAL/VERIFYING 为 M5 预留。
 */
public enum RunState {

    CREATED,
    SNAPSHOTTING,
    REVIEWING,
    REVIEW_COMPLETE,
    PATCH_PROPOSED,
    WAITING_APPROVAL,
    VERIFYING,
    READY_TO_PUBLISH,
    PUBLISHING,
    /** 终态 */
    COMPLETED,
    /** 终态 */
    COMPLETED_WITH_WARNINGS,
    /** 终态（可恢复语义由编排层决定，状态本身不再出迁） */
    FAILED,
    /** 终态 */
    CANCELLED,
    /** 终态：被新 revision/policy 世代取代（换届，v2.2 §3） */
    SUPERSEDED
}
