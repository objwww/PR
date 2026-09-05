package com.objwww.pr.arena.application.chaos;

import com.objwww.pr.arena.application.F3ReconcileService;
import com.objwww.pr.arena.application.OrderCreationSteps;
import com.objwww.pr.arena.application.RefundChainService;
import com.objwww.pr.arena.domain.model.BookingStatus;
import com.objwww.pr.arena.domain.model.PayStatus;
import com.objwww.pr.arena.domain.model.PaymentRecord;
import com.objwww.pr.arena.domain.model.RefundParty;
import com.objwww.pr.arena.domain.model.TradeOrder;
import com.objwww.pr.arena.domain.repository.PaymentRecordRepository;
import com.objwww.pr.arena.domain.repository.TradeOrderRepository;
import com.objwww.pr.arena.infrastructure.persistence.PostgresChaosInjectionStore;
import com.objwww.pr.arena.infrastructure.persistence.PostgresChaosInjectionStore.DuplicateRow;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * 恢复驱动（M2-18/19，arena 侧扫描循环每轮调用 {@link #scanOnce}）：
 * <ul>
 *   <li>ACTIVE F2 → 逐单回跳注入（审计幂等锚防重）；</li>
 *   <li>RECOVERING F1 → 重复单分析（canonical=最早保留），其余走正常业务路径
 *       废单/退款+补偿（live 零污染：只碰 chaos- 关联）；</li>
 *   <li>RECOVERING F2 → 事实驱动恢复（C-4：事实未变才回写）；</li>
 *   <li>RECOVERING F3 → 对账欠账清零后落会话级 RECOVERED 审计
 *       （对账本体在 F3ReconcileService，此处只收口）。</li>
 * </ul>
 * 会话状态推进（RECOVERING→CLOSED）不在本类：C-3 角色拆分，arena 无权写
 * oa_chaos_session，由 arena-chaos-admin 依注入审计只读判定后推进。
 */
public class ChaosRecoveryService {

    private static final Logger log = LoggerFactory.getLogger(ChaosRecoveryService.class);

    private final ChaosSwitchboard switchboard;
    private final PostgresChaosInjectionStore injectionStore;
    private final TradeOrderRepository tradeOrders;
    private final PaymentRecordRepository payments;
    private final OrderCreationSteps steps;
    private final RefundChainService refundChain;
    private final F3ReconcileService f3Reconcile;
    private final int f2Batch;

    public ChaosRecoveryService(ChaosSwitchboard switchboard,
                                PostgresChaosInjectionStore injectionStore,
                                TradeOrderRepository tradeOrders,
                                PaymentRecordRepository payments,
                                OrderCreationSteps steps,
                                RefundChainService refundChain,
                                F3ReconcileService f3Reconcile,
                                int f2Batch) {
        this.switchboard = switchboard;
        this.injectionStore = injectionStore;
        this.tradeOrders = tradeOrders;
        this.payments = payments;
        this.steps = steps;
        this.refundChain = refundChain;
        this.f3Reconcile = f3Reconcile;
        this.f2Batch = f2Batch;
    }

    /** @return 本轮注入/恢复动作数（取证/节奏观察用） */
    public int scanOnce() {
        int actions = 0;
        for (ChaosSwitchboard.SessionView session : switchboard.sessions()) {
            try {
                actions += switch (session.faultType()) {
                    case F1 -> handleF1(session);
                    case F2 -> handleF2(session);
                    case F3 -> handleF3(session);
                };
            } catch (RuntimeException e) {
                log.warn("chaos 会话处理失败（下轮重试）: scenario={} {}",
                        session.scenarioId(), e.getMessage());
            }
        }
        return actions;
    }

    private int handleF1(ChaosSwitchboard.SessionView session) {
        if (!"RECOVERING".equals(session.state())) {
            return 0; // ACTIVE F1 无注入动作（注入点在幂等跳过，create 时已生效）
        }
        if (injectionStore.hasSessionRecovered(sessionIdOf(session))) {
            return 0; // 收口幂等
        }
        List<DuplicateRow> rows = injectionStore.findF1DuplicateRows(session.target());
        int compensated = 0;
        for (DuplicateRow row : rows) {
            if (row.canonical()) {
                continue; // C-4 F1：最早保留，其余废单
            }
            compensateDuplicate(row);
            compensated++;
        }
        // 空靶面（激活后未产生重复即关闭）也属恢复完成——会话必须可闭（C-4 收口完备性）
        injectionStore.auditSessionRecovered(sessionIdOf(session), "F1",
                "canonical_kept=" + rows.stream().filter(DuplicateRow::canonical).count()
                        + ", duplicates_compensated=" + compensated);
        log.info("F1 恢复收口: scenario={} 重复行={} 补偿={}",
                session.scenarioId(), rows.size(), compensated);
        return compensated;
    }

    /** 重复单补偿：按当前事实走正常业务路径（CREATED 废单；ENABLED 按支付状态分支） */
    private void compensateDuplicate(DuplicateRow row) {
        TradeOrder order = tradeOrders.findById(row.orderId()).orElse(null);
        if (order == null || order.bookingStatus() == BookingStatus.DISCARDED) {
            return;
        }
        switch (order.bookingStatus()) {
            case CREATED -> steps.discardTx(order.id(), "F1_DUPLICATE");
            case ENABLED -> {
                if (order.payStatus() == PayStatus.PAID) {
                    refundChain.refundPaid(order.id(), order.amount(),
                            "F1_DUPLICATE", RefundParty.SUPPLIER);
                }
                steps.discardEnabledTx(order.id(), "F1_DUPLICATE");
            }
            default -> {
                // DISCARDED 已在上面短路
            }
        }
    }

    private int handleF2(ChaosSwitchboard.SessionView session) {
        if ("ACTIVE".equals(session.state())) {
            int injected = 0;
            for (var orderId : injectionStore.findF2Targets(
                    sessionIdOf(session), session.target(), f2Batch)) {
                if (injectionStore.injectF2Backjump(sessionIdOf(session), orderId)) {
                    injected++;
                }
            }
            if (injected > 0) {
                log.warn("F2 注入回跳: scenario={} 单数={}", session.scenarioId(), injected);
            }
            return injected;
        }
        // RECOVERING：事实驱动恢复（逐单配对收口：每条 INJECTED 必须落同单 RECOVERED，
        // chaos-admin 的 closeRecovered 才能闭会话——与闭会话判定同款谓词，崩溃续跑幂等）
        int restored = 0;
        for (var orderId : injectionStore.findInjectedWithoutRecovered(sessionIdOf(session))) {
            boolean did = injectionStore.restoreF2(orderId);
            if (did) {
                restored++;
            }
            // CAS 未中（已被业务路径终态/事实缺失）也如实入账：restored=false，不伪装恢复
            injectionStore.auditOrderRecovered(sessionIdOf(session), "F2", orderId,
                    "restored=" + did);
        }
        if (!injectionStore.hasSessionRecovered(sessionIdOf(session))) {
            injectionStore.auditSessionRecovered(sessionIdOf(session), "F2",
                    "restored=" + restored);
        }
        log.info("F2 恢复收口: scenario={} 恢复={}", session.scenarioId(), restored);
        return restored;
    }

    private int handleF3(ChaosSwitchboard.SessionView session) {
        if (!"RECOVERING".equals(session.state())) {
            return 0;
        }
        if (injectionStore.hasSessionRecovered(sessionIdOf(session))) {
            return 0;
        }
        int drained = f3Reconcile.reconcileOnce();
        boolean clean = payments.findUnsettled(PaymentRecord.PaymentKind.AUTH).isEmpty()
                && payments.findUnsettled(PaymentRecord.PaymentKind.CAPTURE).isEmpty();
        if (clean) {
            injectionStore.auditSessionRecovered(sessionIdOf(session), "F3",
                    "unsettled_drained=true");
            log.info("F3 恢复收口: scenario={}（对账欠账清零）", session.scenarioId());
            return 1;
        }
        log.info("F3 对账欠账未清: scenario={} 本轮={}", session.scenarioId(), drained);
        return 0;
    }

    private java.util.UUID sessionIdOf(ChaosSwitchboard.SessionView session) {
        // SessionView 不带 id（scenario_id 全域唯一即业务身份，DB 以 scenario_id 关联）
        return injectionStore.sessionIdOf(session.scenarioId());
    }
}
