package com.objwww.pr.arena.application;

import com.objwww.pr.arena.domain.model.BookingStatus;
import com.objwww.pr.arena.domain.model.PaymentRecord;
import com.objwww.pr.arena.domain.model.PaymentRecord.PaymentKind;
import com.objwww.pr.arena.domain.model.PaymentResult;
import com.objwww.pr.arena.domain.model.TradeOrder;
import com.objwww.pr.arena.domain.repository.PaymentRecordRepository;
import com.objwww.pr.arena.domain.repository.TradeOrderRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.List;

/**
 * F3 对账服务（M2-20）：UNKNOWN → RECONCILING（领取+租约）→ SUCCEEDED/DECLINED。
 *
 * <p>状态即持久化：全程无 sleep——领取的是 UNKNOWN 超龄行或 RECONCILING 租约过期行
 * （crash 后由继任者续跑）；双 reconciler 互斥靠 FOR UPDATE SKIP LOCKED + 租约 CAS。
 *
 * <p>确定性裁定（模拟世界规则，无随机）：
 * <ul>
 *   <li>sku 以 "-latesuccess" 结尾 → SUCCEEDED（迟到成功路径）：
 *       AUTH 成功补 ENABLE（订单收敛到合法 ENABLED）；CAPTURE 成功补 PAID；</li>
 *   <li>否则 → DECLINED：AUTH DECLINED 且订单仍 CREATED → 废单+补偿（discardTx）；
 *       CAPTURE DECLINED → 订单保持 ENABLED/NOT_PAY（合法可取消）。</li>
 * </ul>
 */
public class F3ReconcileService {

    private static final Logger log = LoggerFactory.getLogger(F3ReconcileService.class);

    public static final String LATE_SUCCESS_SKU_SUFFIX = "-latesuccess";

    private final PaymentRecordRepository payments;
    private final TradeOrderRepository tradeOrders;
    private final OrderCreationSteps steps;
    private final String owner;
    private final Duration lease;
    private final Duration unknownOlderThan;
    private final int batch;

    public F3ReconcileService(PaymentRecordRepository payments,
                              TradeOrderRepository tradeOrders,
                              OrderCreationSteps steps,
                              String owner, Duration lease,
                              Duration unknownOlderThan, int batch) {
        this.payments = payments;
        this.tradeOrders = tradeOrders;
        this.steps = steps;
        this.owner = owner;
        this.lease = lease;
        this.unknownOlderThan = unknownOlderThan;
        this.batch = batch;
    }

    /** 一轮对账；@return 处理的记录数（0 = 无欠账，状态面干净） */
    public int reconcileOnce() {
        List<PaymentRecord> claimed = payments.claimReconcileWork(
                owner, lease, unknownOlderThan, batch);
        for (PaymentRecord record : claimed) {
            try {
                settle(record);
            } catch (RuntimeException e) {
                log.warn("对账裁定失败，留待租约过期重领: payment={} {}",
                        record.id(), e.getMessage());
            }
        }
        return claimed.size();
    }

    private void settle(PaymentRecord record) {
        TradeOrder order = tradeOrders.findById(record.orderId()).orElse(null);
        if (order == null) {
            // 事实孤儿：无订单的支付行不可裁定——释放租约留档，告警面由 probe 看异常
            payments.releaseReconcileLease(record.id());
            log.warn("支付记录缺订单事实，跳过裁定: payment={}", record.id());
            return;
        }
        boolean lateSuccess = order.sku() != null
                && order.sku().endsWith(LATE_SUCCESS_SKU_SUFFIX);
        PaymentResult verdict = lateSuccess ? PaymentResult.SUCCEEDED : PaymentResult.DECLINED;

        if (!payments.casResult(record.id(), PaymentResult.RECONCILING, verdict)) {
            return; // 竞态败者（理论上租约持有者唯一）；下轮再说
        }
        applyVerdict(record, order, verdict);
    }

    private void applyVerdict(PaymentRecord record, TradeOrder order, PaymentResult verdict) {
        if (record.kind() == PaymentKind.AUTH) {
            if (verdict == PaymentResult.SUCCEEDED) {
                // 迟到成功：补收口 CREATE（CREATED→ENABLED；订单若已被别路收口则 CAS 自然失败）
                steps.enableTx(order.id());
                log.info("F3 AUTH 迟到成功，补 ENABLE: order={}", order.id());
            } else if (order.bookingStatus() == BookingStatus.CREATED) {
                // 拒绝 + 仍卡 CREATED：废单 + 补偿（discardTx 内读台账出计划，同事务）
                steps.discardTx(order.id(), "PAYMENT_DECLINED_F3");
                log.info("F3 AUTH 裁定拒绝，废单补偿: order={}", order.id());
            }
        } else {
            if (verdict == PaymentResult.SUCCEEDED) {
                steps.markPaidTx(order.id()); // CAPTURE 迟到成功 → NOT_PAY→PAID
                log.info("F3 CAPTURE 迟到成功，补 PAID: order={}", order.id());
            }
            // CAPTURE DECLINED：ENABLED/NOT_PAY 保持（合法，可被取消）
        }
    }
}
