package com.objwww.pr.control.application;

import com.objwww.pr.control.domain.model.RunStep;
import com.objwww.pr.control.domain.model.WorkItem;

import java.util.Objects;
import java.util.UUID;

/**
 * 一次 Step 执行的输入：已领租约的 WorkItem（lease_owner/lease_epoch 是 T2 栅栏凭据）+ 逻辑 Step。
 */
public record StepExecutionContext(WorkItem workItem, RunStep step, UUID attemptId) {

    public StepExecutionContext {
        Objects.requireNonNull(workItem, "workItem");
        Objects.requireNonNull(step, "step");
    }

    public StepExecutionContext(WorkItem workItem, RunStep step) {
        this(workItem, step, null);
    }
}
