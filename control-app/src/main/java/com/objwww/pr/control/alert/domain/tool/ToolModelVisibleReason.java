package com.objwww.pr.control.alert.domain.tool;

/**
 * 模型可见族错误原因（AM4 M4-17，评审错误两族裁定）：结构化、脱敏（不含堆栈/凭据/
 * 内部地址），对模型可见、可进入重试循环（TIMEOUT_RETRYABLE 语义即可重试超时）。
 */
public enum ToolModelVisibleReason {
    /** 查询成功但无数据（正常空结果，非故障） */
    NO_DATA,
    /** 限流（可退避重试） */
    RATE_LIMITED,
    /** 可重试超时（硬 deadline 触发） */
    TIMEOUT_RETRYABLE,
    /** 临时远端故障（网络/5xx 等，固定文案脱敏） */
    REMOTE_UNAVAILABLE,
    /** 回放账本无此精确动作记录（M4-32/33 REPLAY_MOCK 专用；不降级活执行） */
    REPLAY_MISS
}
