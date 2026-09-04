package com.objwww.pr.control.alert.domain.model;

/**
 * rca_task 六态（V1 work_item 同构 + V7 ck_rca_task_state）。
 * READY/RETRY_WAIT 可领取；LEASED 持租约；DONE/CANCELLED/DEAD 终态。
 */
public enum RcaTaskState {
    READY, LEASED, RETRY_WAIT, DONE, CANCELLED, DEAD
}
