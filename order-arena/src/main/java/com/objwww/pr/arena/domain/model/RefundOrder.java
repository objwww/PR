package com.objwww.pr.arena.domain.model;

import com.objwww.pr.arena.domain.statemachine.RefundStateMachine;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** 退款单（M2-11）：责任方 + 金额约束（>0 且 ≤ 已付金额，服务层校验）+ 状态机。 */
public record RefundOrder(
        UUID id,
        UUID tradeOrderId,
        String reason,
        RefundParty responsibleParty,
        BigDecimal amount,
        RefundState state,
        Instant createdAt,
        Instant updatedAt,
        Instant settledAt) {

    public static RefundOrder open(UUID id, UUID tradeOrderId, String reason,
                                   RefundParty party, BigDecimal amount) {
        if (amount == null || amount.signum() <= 0) {
            throw new IllegalArgumentException("退款金额必须为正");
        }
        return new RefundOrder(id, tradeOrderId, reason, party, amount, RefundState.REQUESTED,
                Instant.now(), Instant.now(), null);
    }

    public RefundOrder withState(RefundState next) {
        RefundStateMachine.requireTransition(state, next);
        boolean terminal = next == RefundState.SUCCEEDED || next == RefundState.REJECTED
                || next == RefundState.CANCELLED;
        return new RefundOrder(id, tradeOrderId, reason, responsibleParty, amount, next,
                createdAt, Instant.now(), terminal ? Instant.now() : settledAt);
    }
}
