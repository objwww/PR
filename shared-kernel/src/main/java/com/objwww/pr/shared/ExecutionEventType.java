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
    BUDGET_EXCEEDED,
    /**
     * 对账器连续失败达到阈值的降级告警（M1-T07/T08，M1 方案措辞修正 #3：
     * 探测失败不冒充事实，但必须告警——否则对账覆盖率的盲区不可观测，EX-12/EX-14）。
     * 挂载规则：优先挂该 PR 的 active Run，无则挂最近 Run（execution_event 的
     * review_run_id/pr_revision_id 为 NOT NULL + FK）；该 PR 从未有过 Run 时无法
     * 合法落库，由对账器以结构化 WARN 日志代账。
     */
    RECONCILER_DEGRADED,
    /**
     * 已发布资源确认失踪（M1-T08，方案 §4.6）：探针 404 且 sanity 读通过才允许落，
     * 每个资源恰好一次（ST-22：状态已 MISSING 的重复扫描不再发）。
     */
    PUBLICATION_DRIFT_DETECTED,
    /**
     * Drift 探针 404 但 sanity 读失败（E2E-18/F-3：GitHub 以 404 替代 403 隐藏私有资源，
     * 无法区分"不存在"与"无权限"）——权限异常绝不冒充"不存在"：资源标 UNKNOWN 并告警。
     */
    PUBLICATION_DRIFT_PERMISSION_ALERT
}
