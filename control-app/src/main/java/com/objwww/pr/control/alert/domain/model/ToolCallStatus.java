package com.objwww.pr.control.alert.domain.model;

/**
 * Holmes tool_call 状态（M3-06 枚举映射：线缆侧 success/error/no_data/approval_required
 * 大小写不敏感映射到本枚举；未知值落 null——账本诚实，不猜测）。
 */
public enum ToolCallStatus {
    SUCCESS,
    ERROR,
    NO_DATA,
    APPROVAL_REQUIRED
}
