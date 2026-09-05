package com.objwww.pr.arena.domain.model;

import com.objwww.pr.arena.domain.statemachine.FulfillmentStateMachine;

import java.time.Instant;
import java.util.UUID;

/** 履约单（与交易单同事务成对出生，C-1）；迁移走 FulfillmentStateMachine。 */
public record FulfillmentOrder(
        UUID id,
        UUID tradeOrderId,
        FulfillmentState state,
        Instant createdAt,
        Instant updatedAt) {

    public static FulfillmentOrder create(UUID id, UUID tradeOrderId) {
        return new FulfillmentOrder(id, tradeOrderId, FulfillmentState.CONFIRMING,
                Instant.now(), Instant.now());
    }

    public FulfillmentOrder withState(FulfillmentState next) {
        FulfillmentStateMachine.requireTransition(state, next);
        return new FulfillmentOrder(id, tradeOrderId, next, createdAt, Instant.now());
    }
}
