package com.objwww.pr.shared;

/**
 * RunStep 七态（与 V1 ck_step_state 一致）。逻辑步骤态，物理重试走 StepAttempt。
 */
public enum StepState {

    /** 待执行，可被 WorkItem Worker 领取 */
    READY,
    RUNNING,
    /** 等待外部条件（如审批、异步回调），可回流 READY */
    WAITING,
    /** 终态 */
    SUCCEEDED,
    /** 终态 */
    FAILED,
    /** 终态 */
    CANCELLED,
    /** 终态：换届作废 */
    SUPERSEDED
}
