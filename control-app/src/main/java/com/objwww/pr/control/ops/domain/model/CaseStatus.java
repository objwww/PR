package com.objwww.pr.control.ops.domain.model;

/**
 * OperatorCase 状态全集（M5-11；与前端 alert-web/src/mocks/cases.js status 值核对：
 * 前端只用 OPEN/ACKED/RESOLVED 三态，中文显示名由 view 层词典映射）。
 */
public enum CaseStatus {
    OPEN,
    ACKED,
    RESOLVED
}
