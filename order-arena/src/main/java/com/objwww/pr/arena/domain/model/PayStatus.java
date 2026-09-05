package com.objwww.pr.arena.domain.model;

/**
 * 交易单 pay 维度（C-1）：NOT_PAY → PAID（CAPTURE/SUCCEEDED 事实落定）→ REFUNDED（退款 SUCCEEDED）。
 * 迁移门在 TradeOrder 一致性守卫：PAID 仅当 booking=ENABLED（pay() 回调只作用于 ENABLED 订单）。
 */
public enum PayStatus {
    NOT_PAY,
    PAID,
    REFUNDED
}
