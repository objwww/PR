package com.objwww.pr.arena.domain.model;

import com.objwww.pr.arena.domain.statemachine.PayStateMachine;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * 支付事实记录（C-1）。kind=AUTH 创单第二步授权；CAPTURE=pay() 回调。
 * result 迁移走 PayStateMachine（UNKNOWN→RECONCILING→... 为 F3 对账路径）。
 */
public record PaymentRecord(
        UUID id,
        UUID orderId,
        int attemptNo,
        PaymentKind kind,
        PaymentResult result,
        BigDecimal amount,
        Instant initiatedAt,
        Instant settledAt) {

    public enum PaymentKind {
        AUTH,
        CAPTURE
    }

    public static PaymentRecord initiate(UUID id, UUID orderId, int attemptNo, PaymentKind kind,
                                         BigDecimal amount) {
        return new PaymentRecord(id, orderId, attemptNo, kind, PaymentResult.INITIATED, amount,
                Instant.now(), null);
    }

    public PaymentRecord withResult(PaymentResult next) {
        PayStateMachine.requireTransition(result, next);
        return new PaymentRecord(id, orderId, attemptNo, kind, next, amount, initiatedAt,
                next == PaymentResult.SUCCEEDED || next == PaymentResult.DECLINED
                        ? Instant.now() : null);
    }

    public boolean terminal() {
        return result == PaymentResult.SUCCEEDED || result == PaymentResult.DECLINED;
    }
}
