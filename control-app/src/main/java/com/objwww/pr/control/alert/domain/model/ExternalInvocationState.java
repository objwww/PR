package com.objwww.pr.control.alert.domain.model;

/**
 * 外部调用账本四态（V5 账本同形态）：STARTED→{SUCCEEDED,FAILED,UNKNOWN}。
 * UNKNOWN = 崩溃回收把悬挂 STARTED 补的终态（调用结果不明，可对账不可重放）。
 */
public enum ExternalInvocationState {
    STARTED, SUCCEEDED, FAILED, UNKNOWN
}
