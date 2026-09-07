package com.objwww.pr.control.alert.domain.tool;

/**
 * 控制面终止族原因（AM4 M4-17/18，评审错误两族裁定）：直接终止/拒绝，不触发模型
 * 重试循环。与 V15 账本原因码集（INVALID_INPUT/POLICY_DENIED/TIMEOUT/…）是两个面：
 * 本枚举面向调用编排终止语义，账本原因码面向调用落档。
 */
public enum ToolControlReason {
    POLICY_DENIED,
    UNKNOWN_TOOL,
    INVALID_ARGS,
    AUTH_FAILED,
    BUDGET_EXHAUSTED,
    STALE_GENERATION,
    RESULT_OVERSIZE
}
