package com.objwww.pr.control.alert.domain.model;

/**
 * 调查执行结局（V9 ck_rca_ir_execution；与结构验证结局 {@link ValidationStatus} 两列独立——
 * 执行失败和结构失败不混装，AM3 落码方案 v1.1 M3-03）。
 */
public enum ExecutionStatus {
    STARTED,
    SUCCEEDED,
    FAILED,
    TIMEOUT,
    UNKNOWN,
    CANCELLED
}
