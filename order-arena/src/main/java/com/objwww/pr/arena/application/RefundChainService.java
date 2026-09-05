package com.objwww.pr.arena.application;

import com.objwww.pr.arena.domain.model.PayStatus;
import com.objwww.pr.arena.domain.model.RefundOrder;
import com.objwww.pr.arena.domain.model.RefundParty;
import com.objwww.pr.arena.domain.model.RefundState;
import com.objwww.pr.arena.domain.repository.RefundOrderRepository;
import com.objwww.pr.arena.domain.repository.TradeOrderRepository;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * 退款链（M2-11 抽出，cancel 与 F1 恢复共用，M2-18）：
 * REQUESTED→APPROVED→REFUNDING→SUCCEEDED 四短事务 + pay_status PAID→REFUNDED。
 * 每步独立事务 = 链中间崩溃留下中间态（如 REFUNDING 挂起），由 DomainProbe 的
 * 事实面兜底观测（本靶场链路无外部副作用，中间态属可恢复事实）。
 */
public class RefundChainService {

    private final TransactionFacade txFacade;
    private final RefundOrderRepository refunds;
    private final TradeOrderRepository tradeOrders;

    public RefundChainService(TransactionFacade txFacade,
                              RefundOrderRepository refunds,
                              TradeOrderRepository tradeOrders) {
        this.txFacade = txFacade;
        this.refunds = refunds;
        this.tradeOrders = tradeOrders;
    }

    /** @return 退款单号（PAID 订单的废单前收口） */
    public UUID refundPaid(UUID orderId, BigDecimal amount, String reason, RefundParty party) {
        RefundOrder refund = RefundOrder.open(UUID.randomUUID(), orderId, reason, party, amount);
        txFacade.inTx(() -> refunds.insert(refund));
        txFacade.inTx(() -> refunds.casState(refund.id(), RefundState.REQUESTED,
                RefundState.APPROVED));
        txFacade.inTx(() -> refunds.casState(refund.id(), RefundState.APPROVED,
                RefundState.REFUNDING));
        txFacade.inTx(() -> refunds.casState(refund.id(), RefundState.REFUNDING,
                RefundState.SUCCEEDED));
        txFacade.inTx(() -> tradeOrders.casPayStatus(orderId, PayStatus.PAID, PayStatus.REFUNDED));
        return refund.id();
    }
}
