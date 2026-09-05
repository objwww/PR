package com.objwww.pr.arena.domain.model;

/**
 * 退款单状态（M2-11 三单一致性）：REQUESTED→APPROVED→REFUNDING→SUCCEEDED 主链；
 * REJECTED（拒绝）/CANCELLED（撤回）/FAILED（渠道失败，可回 REFUNDING 重试）。
 */
public enum RefundState {
    REQUESTED,
    APPROVED,
    REFUNDING,
    SUCCEEDED,
    REJECTED,
    FAILED,
    CANCELLED
}
