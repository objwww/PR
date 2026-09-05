package com.objwww.pr.arena.domain.model;

import com.objwww.pr.arena.domain.statemachine.BookingStateMachine;
import com.objwww.pr.shared.IllegalTransitionException;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * 交易单（三单之首）。不可变记录 + 显式迁移守卫：booking/pay 两维的状态变更只能经
 * withBookingStatus/withPayStatus（内部走状态机/一致性规则），仓储层落库前必经此门。
 * CREATED 的"查询不可见"由仓储/接口层过滤（M2-09），不在本类表达。
 */
public record TradeOrder(
        UUID id,
        String intentId,
        String correlationId,
        String buyerId,
        String sku,
        int quantity,
        BigDecimal amount,
        BookingStatus bookingStatus,
        PayStatus payStatus,
        String discardReason,
        Instant createdAt,
        Instant enabledAt,
        Instant updatedAt) {

    public static TradeOrder create(UUID id, String intentId, String correlationId,
                                    String buyerId, String sku, int quantity, BigDecimal amount) {
        return new TradeOrder(id, intentId, correlationId, buyerId, sku, quantity, amount,
                BookingStatus.CREATED, PayStatus.NOT_PAY, null, Instant.now(), null, Instant.now());
    }

    /** booking 维度迁移（矩阵外即抛，F2 的"setter 直改状态"在正常域无路径） */
    public TradeOrder withBookingStatus(BookingStatus next, String reason) {
        BookingStateMachine.requireTransition(bookingStatus, next);
        if (next == BookingStatus.DISCARDED && (reason == null || reason.isBlank())) {
            throw new IllegalArgumentException("DISCARDED 必须携带废单原因");
        }
        return new TradeOrder(id, intentId, correlationId, buyerId, sku, quantity, amount,
                next, payStatus, next == BookingStatus.DISCARDED ? reason : discardReason,
                createdAt, next == BookingStatus.ENABLED ? Instant.now() : enabledAt,
                Instant.now());
    }

    /**
     * pay 维度一致性守卫（M2-11 不变量）：
     * NOT_PAY→PAID 仅当 booking=ENABLED（C-1：pay() 只作用于生效订单）；
     * PAID→REFUNDED 仅当退款成功后的终局（由退款流程调用）。
     */
    public TradeOrder withPayStatus(PayStatus next) {
        switch (payStatus) {
            case NOT_PAY -> {
                if (next != PayStatus.PAID) {
                    throw new IllegalTransitionException(payStatus, next);
                }
                if (bookingStatus != BookingStatus.ENABLED) {
                    throw new IllegalTransitionException(bookingStatus, next);
                }
            }
            case PAID -> {
                if (next != PayStatus.REFUNDED) {
                    throw new IllegalTransitionException(payStatus, next);
                }
            }
            case REFUNDED -> throw new IllegalTransitionException(payStatus, next);
        }
        return new TradeOrder(id, intentId, correlationId, buyerId, sku, quantity, amount,
                bookingStatus, next, discardReason, createdAt, enabledAt, Instant.now());
    }
}
