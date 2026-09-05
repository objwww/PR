package com.objwww.pr.arena.domain.model;

/**
 * 补偿 outbox 八态（M2-05/V2 冻结）：PENDING→CLAIMED→EXECUTING→{SUCCEEDED,SKIPPED}
 * /RETRY_WAIT（→CLAIMED 重领）；SUCCEEDED/SKIPPED/DEAD/CANCELLED 终态。
 */
public enum OutboxState {
    PENDING,
    CLAIMED,
    EXECUTING,
    SUCCEEDED,
    SKIPPED,
    RETRY_WAIT,
    DEAD,
    CANCELLED
}
