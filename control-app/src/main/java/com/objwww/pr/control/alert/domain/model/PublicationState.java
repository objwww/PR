package com.objwww.pr.control.alert.domain.model;

/**
 * 报告发布状态机（V9 ck_publication_state；M3-09 冻结）：
 * PENDING 出生 → READY（outbox 同事务就绪）→ SENT（全渠道送达）；
 * RETRY_WAIT 退避；DEAD 终态（4xx/耗尽）；SUPPRESSED（不发布的报告）。
 * rca_report 本体不可变——发布/重试状态只活在 publish 侧（BA-10②）。
 */
public enum PublicationState {
    PENDING,
    READY,
    SENT,
    RETRY_WAIT,
    DEAD,
    SUPPRESSED
}
