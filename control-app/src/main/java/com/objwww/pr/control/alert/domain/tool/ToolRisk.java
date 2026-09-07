package com.objwww.pr.control.alert.domain.tool;

/**
 * 工具风险等级（AM4 M4-13）——只来自本地注册表（ToolDefinition 显式声明），
 * 外部 MCP annotations（readOnlyHint 等）仅作参考、官方明示不可信（E-16 §1.2），
 * 伪造 annotation 不影响本枚举判定。可执行集判定归 ToolPolicy（M4-16）。
 */
public enum ToolRisk {
    /** 只读、无副作用、可安全重放 */
    R0,
    /** 读敏感面（如全量日志检索），仍无副作用 */
    R1,
    /** 有副作用/写意图——AM4 一律不执行，仅 VALIDATE_ONLY 记录 */
    R2,
    /** 危险/不可逆——同 R2，且升级人工口径（AM5 审批态） */
    R3;

    /** 当前策略下允许真实执行的风险集（R2/R3 只记录不执行） */
    public boolean executable() {
        return this == R0 || this == R1;
    }
}
