package com.objwww.pr.arena.domain.model;

/** 台账资源维度（AM2 v3.0 §6.3：库存/优惠/限购/资产四类逐一扣减）。 */
public enum ResourceType {
    INVENTORY,
    DISCOUNT,
    PURCHASE_LIMIT,
    ASSET
}
