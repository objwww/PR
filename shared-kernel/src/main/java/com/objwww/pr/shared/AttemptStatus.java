package com.objwww.pr.shared;

/**
 * StepAttempt 物理尝试状态（与 V1 ck_attempt_status 一致）。
 * 除 STARTED 外均为终态；attempt start 不进账本（v2.2 E10）。
 */
public enum AttemptStatus {

    STARTED,
    /** 终态 */
    SUCCEEDED,
    /** 终态：可重试失败 */
    FAILED_RETRYABLE,
    /** 终态：确定性失败 */
    FAILED_TERMINAL,
    /** 终态：worker 崩溃/租约过期被放弃 */
    ABANDONED,
    /** 终态：晚到结果（lease_epoch 过期），只记录不推进（I11） */
    STALE
}
