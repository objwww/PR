package com.objwww.pr.shared;

/**
 * 执行账本事件类型（M0 最小必要集，M0-T04）。
 * 账本只记 intent/result 级事实；attempt start 不落账本（v2.2 E10）。
 */
public enum ExecutionEventType {

    /** Run 建立（T1） */
    RUN_CREATED,
    /** Run 状态推进事实，payload 含 run_state（COMPLETED/FAILED/CANCELLED 等终态及中间态） */
    RUN_STATE_CHANGED,
    /** revision/policy 换届，旧 Run 作废（T1） */
    REVISION_INVALIDATED,
    /** Step 终态结果，payload 含 step_state；attempt start 不入账（E10） */
    STEP_RESULT,
    /** T2 同事务插入 Outbox 命令的发布意图 */
    PUBLICATION_REQUESTED,
    /** Publisher 确认远端副作用存在（T3-B） */
    PUBLICATION_CONFIRMED,
    /** 写调用结果未知（崩溃窗口），命令转 RECONCILING（§4.3） */
    PUBLICATION_OUTCOME_UNKNOWN,
    /** sequence 跳号对账事件（v2.2 E2：致命，不静默跳过） */
    SEQUENCE_GAP_DETECTED,
    /** 安全门禁拒绝（fail-closed，E5） */
    SAFETY_REJECTED,
    /** token/成本预算超限（EX-06） */
    BUDGET_EXCEEDED
}
