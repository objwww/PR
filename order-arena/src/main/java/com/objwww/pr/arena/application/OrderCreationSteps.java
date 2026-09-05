package com.objwww.pr.arena.application;

import com.objwww.pr.arena.domain.model.BookingStatus;
import com.objwww.pr.arena.domain.model.CompensationEvent;
import com.objwww.pr.arena.domain.model.FulfillmentOrder;
import com.objwww.pr.arena.domain.model.FulfillmentState;
import com.objwww.pr.arena.domain.model.OrderSnapshot;
import com.objwww.pr.arena.domain.model.PayStatus;
import com.objwww.pr.arena.domain.model.PaymentRecord;
import com.objwww.pr.arena.domain.model.PaymentResult;
import com.objwww.pr.arena.domain.model.ResourceLedgerEntry;
import com.objwww.pr.arena.domain.model.ResourceType;
import com.objwww.pr.arena.domain.model.TradeOrder;
import com.objwww.pr.arena.domain.repository.CompensationOutboxRepository;
import com.objwww.pr.arena.domain.repository.FulfillmentOrderRepository;
import com.objwww.pr.arena.domain.repository.PaymentRecordRepository;
import com.objwww.pr.arena.domain.repository.ResourceLedgerRepository;
import com.objwww.pr.arena.domain.repository.TradeOrderRepository;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * 两步创单的事务步骤集（M2-09/10/12）：每步一个独立短事务（REQUIRES_NEW），
 * 编排层不包大事务——步骤间的崩溃窗口由此天然成立，由 IT 逐窗验证。
 *
 * <p>模拟世界规则（确定性，无随机）：
 * <ul>
 *   <li>sku 以 "-noroom" 结尾 → INVENTORY 扣减失败（NO_ROOM 路径）；</li>
 *   <li>扣减顺序 = INVENTORY(1)→DISCOUNT(2)→PURCHASE_LIMIT(3)→ASSET(4)。</li>
 * </ul>
 */
public class OrderCreationSteps {

    /** 库存不足（NO_ROOM 路径的确定性触发面） */
    public static class NoRoomException extends IllegalStateException {
        public NoRoomException(ResourceType type) {
            super("资源扣减失败: " + type);
        }
    }

    private final TransactionTemplate tx;
    private final TradeOrderRepository tradeOrders;
    private final FulfillmentOrderRepository fulfillments;
    private final ResourceLedgerRepository ledger;
    private final PaymentRecordRepository payments;
    private final CompensationOutboxRepository outbox;

    public OrderCreationSteps(TransactionTemplate tx,
                              TradeOrderRepository tradeOrders,
                              FulfillmentOrderRepository fulfillments,
                              ResourceLedgerRepository ledger,
                              PaymentRecordRepository payments,
                              CompensationOutboxRepository outbox) {
        this.tx = tx;
        this.tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.tradeOrders = tradeOrders;
        this.fulfillments = fulfillments;
        this.ledger = ledger;
        this.payments = payments;
        this.outbox = outbox;
    }

    /** 第一步：CREATE 不可见快照（交易单 CREATED + 履约单 CONFIRMING，单事务成对） */
    public OrderSnapshot createSnapshotTx(TradeOrder newOrder) {
        return tx.execute(status -> tradeOrders.insertCreatedSnapshot(newOrder));
    }

    /** 第二步之一：单资源独立短事务扣减（台账逐笔）；NO_ROOM 规则触发即抛，事务自动回滚 */
    public void deductResourceTx(OrderSnapshot snapshot, ResourceType type, int seq, int quantity) {
        if (type == ResourceType.INVENTORY && snapshot.tradeOrder().sku().endsWith("-noroom")) {
            throw new NoRoomException(type);
        }
        tx.executeWithoutResult(status ->
                ledger.insertDeduct(ResourceLedgerEntry.deduct(snapshot.orderId(), type, seq, quantity)));
    }

    /** 第二步之二：支付授权 INITIATED 出生（独立事务；网关调用发生在此后的进程内） */
    public PaymentRecord initiateAuthTx(UUID orderId, BigDecimal amount) {
        PaymentRecord record = PaymentRecord.initiate(UUID.randomUUID(), orderId,
                payments.nextAttemptNo(orderId, PaymentRecord.PaymentKind.AUTH),
                PaymentRecord.PaymentKind.AUTH, amount);
        tx.executeWithoutResult(status -> payments.insertInitiated(record));
        return record;
    }

    /** 第二步之三：网关结果落定（CAS + PayStateMachine 门；UNKNOWN 即 F3 的持久化未知） */
    public boolean resolveAuthTx(UUID paymentId, PaymentResult resolved) {
        return tx.execute(status -> payments.casResult(paymentId, PaymentResult.INITIATED, resolved));
    }

    /** 收口：ENABLE（booking CREATED→ENABLED + 履约 CONFIRMING→CONFIRMED，同事务） */
    public boolean enableTx(UUID orderId) {
        return tx.execute(status -> {
            boolean booking = tradeOrders.casBookingStatus(orderId, BookingStatus.CREATED,
                    BookingStatus.ENABLED, null);
            boolean fulfillment = fulfillments.casState(orderId,
                    FulfillmentState.CONFIRMING, FulfillmentState.CONFIRMED);
            return booking && fulfillment;
        });
    }

    /**
     * 废单收口（M2-12 的同生共死点）：DISCARDED + 履约 CANCELLED + 补偿 outbox 行
     * 同一事务——任一失败整单回滚（崩溃窗口两向由 IT 验证）。
     * 补偿计划 = 此刻台账里"已扣成功"的 DEDUCT 行（事务内读取，天然一致）。
     */
    public void discardTx(UUID orderId, String reason) {
        tx.executeWithoutResult(status -> {
            boolean booking = tradeOrders.casBookingStatus(orderId, BookingStatus.CREATED,
                    BookingStatus.DISCARDED, reason);
            if (!booking) {
                status.setRollbackOnly();
                return;
            }
            fulfillments.casState(orderId, FulfillmentState.CONFIRMING, FulfillmentState.CANCELLED);
            List<CompensationEvent.PlanEntry> plan = ledger.listDeductions(orderId).stream()
                    .map(e -> new CompensationEvent.PlanEntry(e.resourceType(), e.deductionSeq(),
                            e.quantity()))
                    .toList();
            outbox.insertPending(CompensationEvent.pending(orderId, plan));
        });
    }

    /** C-1：pay() 回调链——CAPTURE INITIATED 出生 */
    public PaymentRecord initiateCaptureTx(UUID orderId, BigDecimal amount) {
        PaymentRecord record = PaymentRecord.initiate(UUID.randomUUID(), orderId,
                payments.nextAttemptNo(orderId, PaymentRecord.PaymentKind.CAPTURE),
                PaymentRecord.PaymentKind.CAPTURE, amount);
        tx.executeWithoutResult(status -> payments.insertInitiated(record));
        return record;
    }

    /** CAPTURE 结果落定（CAS） */
    public boolean resolveCaptureTx(UUID paymentId, PaymentResult resolved) {
        return tx.execute(status -> payments.casResult(paymentId, PaymentResult.INITIATED, resolved));
    }

    /** CAPTURE 成功 → pay_status NOT_PAY→PAID（CAS，booking=ENABLED 兜底在 SQL） */
    public boolean markPaidTx(UUID orderId) {
        return tx.execute(status ->
                tradeOrders.casPayStatus(orderId, PayStatus.NOT_PAY, PayStatus.PAID));
    }

    /** 已生效订单的取消收口（cancel 路径：ENABLED→DISCARDED，补偿同事务，M2-11） */
    public void discardEnabledTx(UUID orderId, String reason) {
        tx.executeWithoutResult(status -> {
            boolean booking = tradeOrders.casBookingStatus(orderId, BookingStatus.ENABLED,
                    BookingStatus.DISCARDED, reason);
            if (!booking) {
                status.setRollbackOnly();
                return;
            }
            fulfillments.casState(orderId, fulfillments.findByTradeOrderId(orderId)
                    .map(FulfillmentOrder::state).orElse(FulfillmentState.CONFIRMING),
                    FulfillmentState.CANCELLED);
            List<CompensationEvent.PlanEntry> plan = ledger.listDeductions(orderId).stream()
                    .map(e -> new CompensationEvent.PlanEntry(e.resourceType(), e.deductionSeq(),
                            e.quantity()))
                    .toList();
            outbox.insertPending(CompensationEvent.pending(orderId, plan));
        });
    }

    /** 台账读取（编排层组装幂等重放摘要用） */
    public List<ResourceLedgerEntry> deductions(UUID orderId) {
        return ledger.listDeductions(orderId);
    }
}
