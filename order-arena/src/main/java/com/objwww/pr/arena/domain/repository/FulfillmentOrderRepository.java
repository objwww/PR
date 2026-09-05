package com.objwww.pr.arena.domain.repository;

import com.objwww.pr.arena.domain.model.FulfillmentState;
import com.objwww.pr.arena.domain.model.FulfillmentOrder;

import java.util.Optional;
import java.util.UUID;

/** 履约单仓储端口（CAS 迁移）。 */
public interface FulfillmentOrderRepository {

    void insert(FulfillmentOrder order);

    Optional<FulfillmentOrder> findByTradeOrderId(UUID tradeOrderId);

    boolean casState(UUID tradeOrderId, FulfillmentState from, FulfillmentState to);
}
