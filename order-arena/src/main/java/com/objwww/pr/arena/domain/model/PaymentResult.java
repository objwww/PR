package com.objwww.pr.arena.domain.model;

/**
 * 支付事实结果（C-1：支付不是业务单但必须有事实流水；oa_payment_record.result）。
 * UNKNOWN→RECONCILING→{SUCCEEDED, DECLINED} 为 F3 对账路径（M2-20），
 * UNKNOWN→SUCCEEDED 即"迟到成功"。
 */
public enum PaymentResult {
    INITIATED,
    SUCCEEDED,
    DECLINED,
    UNKNOWN,
    RECONCILING
}
