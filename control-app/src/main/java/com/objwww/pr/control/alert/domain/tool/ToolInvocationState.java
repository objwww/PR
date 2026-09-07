package com.objwww.pr.control.alert.domain.tool;

/**
 * 工具调用账本四态（AM4 M4-18，V15）：沿用既有 PENDING/SUCCESS/FAILED/UNKNOWN。
 * PENDING 先行（同事务先行落档，进程死后仍可查悬挂）；终态 CAS 单向迁移。
 * 不引入审批挂起态（归 AM5），不照搬 CrewAI 六分类（评审裁定）。
 */
public enum ToolInvocationState {
    PENDING,
    SUCCESS,
    FAILED,
    UNKNOWN
}
