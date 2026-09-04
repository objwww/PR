package com.objwww.pr.control.alert.domain.model;

/**
 * 报告结构验证链结果（V7 ck_rca_report_status；语义验证归 AM4，本枚举只管结构）。
 */
public enum ValidationStatus {
    STRUCTURE_VALIDATED,
    REJECTED_MALFORMED,
    REJECTED_OVERSIZE,
    REJECTED_SCHEMA_VERSION,
    REJECTED_SCHEMA_MISMATCH
}
