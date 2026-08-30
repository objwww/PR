package com.objwww.pr.control.application;

import java.util.Objects;
import java.util.UUID;

/**
 * T2 完成 Step 的输入：work_item 租约凭据（lease_owner + lease_epoch，I11 晚到结果栅栏）
 * + attempt 标识 + 执行结果。
 */
public record StepCompletion(
        UUID workItemId,
        UUID stepId,
        UUID attemptId,
        String leaseOwner,
        long leaseEpoch,
        StepOutcome outcome) {

    public StepCompletion {
        Objects.requireNonNull(workItemId, "workItemId");
        Objects.requireNonNull(stepId, "stepId");
        Objects.requireNonNull(attemptId, "attemptId");
        Objects.requireNonNull(leaseOwner, "leaseOwner");
        Objects.requireNonNull(outcome, "outcome");
        if (leaseEpoch < 0) {
            throw new IllegalArgumentException("leaseEpoch 不能为负: " + leaseEpoch);
        }
    }
}
