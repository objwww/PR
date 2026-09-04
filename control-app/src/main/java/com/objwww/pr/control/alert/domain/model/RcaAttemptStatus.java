package com.objwww.pr.control.alert.domain.model;

/**
 * rca_attempt 六态（V1 step_attempt 同构）：STARTED 起始，五终态。
 */
public enum RcaAttemptStatus {
    STARTED, SUCCEEDED, FAILED_RETRYABLE, FAILED_TERMINAL, ABANDONED, STALE
}
