package com.objwww.pr.shared;

/**
 * WorkItem 状态（与 V1 ck_work_item_state 一致）。
 */
public enum WorkItemState {

    READY,
    LEASED,
    RETRY_WAIT,
    /** 终态 */
    DONE,
    /** 终态 */
    CANCELLED,
    /** 终态：attempt 预算耗尽 */
    DEAD
}
