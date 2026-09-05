package com.objwww.pr.arena.domain.model;

/**
 * 履约单状态（AM2 v3.0 §3.1）：CONFIRMING 确认中 / CONFIRMED 已确认（随订单 ENABLE）
 * / NO_ROOM 无房（库存扣减失败面）/ CANCELLED 已取消。
 */
public enum FulfillmentState {
    CONFIRMING,
    CONFIRMED,
    NO_ROOM,
    CANCELLED
}
