package com.objwww.pr.arena.domain.model;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** 交易单 + 履约单的创单快照（M2-09 第一步：CREATE 不可见快照，单事务成对出生）。 */
public record OrderSnapshot(TradeOrder tradeOrder, FulfillmentOrder fulfillmentOrder) {

    public OrderSnapshot {
        if (!tradeOrder.id().equals(fulfillmentOrder.tradeOrderId())) {
            throw new IllegalArgumentException("履约单必须引用同一交易单");
        }
    }

    public UUID orderId() {
        return tradeOrder.id();
    }

    /** 兼容视图：金额（供支付网关调用取值） */
    public BigDecimal amount() {
        return tradeOrder.amount();
    }

    public Instant createdAt() {
        return tradeOrder.createdAt();
    }
}
