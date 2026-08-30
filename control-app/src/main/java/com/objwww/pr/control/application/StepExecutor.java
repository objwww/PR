package com.objwww.pr.control.application;

/**
 * Step 执行器（T10，application）：按 work_type 派发，M0 只有 REVIEW 一种实现。
 * Worker 不管业务步骤逻辑（§3.1：Worker 不自己实现业务步骤），执行器不管租约与状态推进。
 *
 * <p>约定：已知失败尽量返回 {@link StepOutcome.Failed}；未预期异常上抛，由 Worker 统一归类。
 * 心跳失效（{@link LeaseHeartbeat#isAlive()} == false）时执行器应抛
 * {@link LeaseLostException} 尽快停手。
 */
public interface StepExecutor {

    /** 对齐 work_item.work_type / run_step.step_type */
    String workType();

    StepOutcome execute(StepExecutionContext context, LeaseHeartbeat heartbeat);
}
