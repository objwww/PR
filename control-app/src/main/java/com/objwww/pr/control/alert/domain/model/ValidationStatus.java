package com.objwww.pr.control.alert.domain.model;

/**
 * 报告结构验证链结果（V7 ck_rca_report_status / V9 ck_rca_ir_validation；
 * 语义验证归 AM4，本枚举只管结构）。
 * NOT_VALIDATED 仅用于调查记录 STARTED 先行行（尚未验证，M3-04）。
 */
public enum ValidationStatus {
    NOT_VALIDATED,
    STRUCTURE_VALIDATED,
    REJECTED_MALFORMED,
    REJECTED_OVERSIZE,
    REJECTED_SCHEMA_VERSION,
    REJECTED_SCHEMA_MISMATCH
}
