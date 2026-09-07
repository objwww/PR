package com.objwww.pr.control.eval.domain.model;

/**
 * 安全门五面分类码（M5-07；E2E-AM5-03 红队五面；机器码英文 + 中文口径契约）。
 * 任一面命中即 REJECT（INV-AM5-4 fail-closed）。
 */
public enum SafetyFace {
    /** 非法 schema：报告/工具参数结构违规（结构校验拒绝记录） */
    SCHEMA,
    /** prompt injection：注入攻击输入面（注入检测拒绝记录） */
    INJECTION,
    /** 跨租户：越租户访问面（租户边界拒绝记录） */
    CROSS_TENANT,
    /** 越权工具：注册表外工具调用（ToolRegistry/ToolPolicy UNKNOWN_TOOL 拦截面） */
    UNAUTHORIZED_TOOL,
    /** 写意图：写操作未获批（ToolGateway approval_required 拦截面） */
    WRITE_INTENT
}
