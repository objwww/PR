package com.objwww.pr.arena.application;

import com.objwww.pr.arena.infrastructure.persistence.PostgresProbeStore;
import com.objwww.pr.shared.Digest;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 领域探针（M2-21，C-7 冻结语义）：
 * <ul>
 *   <li>每次扫描 = 事实违规查询 + episode 台账同步：违规出现→开 episode（检测计数 +1）、
 *       连续在→续（只碰 last_seen，<b>同问题连续扫描只计一次</b>）、消失→关、
 *       复发→<b>新 episode_no</b>（重新计一次）；</li>
 *   <li>gauge = 打开 episode 数；counter = 累计 episode 数；</li>
 *   <li>DB 失败：<b>保留末值、probe_up=0、绝不归零业务 gauge</b>——
 *       探测失明 ≠ 世界恢复（C-7 硬约束）；失明时长由
 *       oa_domain_probe_last_success_timestamp 的陈旧度暴露。</li>
 * </ul>
 */
public class DomainProbe {

    private static final Logger log = LoggerFactory.getLogger(DomainProbe.class);

    public static final String STUCK = "STUCK_ORDER";
    public static final String DUPLICATE = "DUPLICATE_ORDER";
    public static final String STATE_VIOLATION = "STATE_VIOLATION";

    // —— M-a 业务对账探测类型（v2 设计 §3.1）——
    public static final String PAYMENT_ORDER_MISMATCH = "PAYMENT_ORDER_MISMATCH";
    public static final String PENDING_PAYMENT = "PENDING_PAYMENT";
    public static final String DUPLICATE_PAYMENT = "DUPLICATE_PAYMENT";
    public static final String RECON_SKEW = "RECON_SKEW";
    public static final String INVENTORY_OVERSELL = "INVENTORY_OVERSELL";
    public static final String FULFILLMENT_GAP = "FULFILLMENT_GAP";
    public static final String DUPLICATE_FULFILLMENT = "DUPLICATE_FULFILLMENT";
    public static final String FULFILLMENT_OVERDUE = "FULFILLMENT_OVERDUE";

    /** @param ok false = 本轮失败（保留末值语义生效） */
    public record ScanResult(boolean ok, int stuck, int duplicates, int stateViolations) {
    }

    private final PostgresProbeStore store;
    private final int stuckThresholdSeconds;
    private final MeterRegistry registry;
    private final Map<String, Counter> detectedCounters = new HashMap<>();
    /** C-7 末值面：type → 最近一次成功扫描的打开 episode 数 */
    private final Map<String, Long> lastOpenCounts = new ConcurrentHashMap<>();
    private volatile boolean probeUp = true;
    private volatile double lastSuccessEpoch = 0;

    /** M-a 业务量 counter（探测面增量产出，注入点禁自报——INV-AM2-5） */
    private final Counter ordersCreated;
    private final Counter ordersSuccess;
    private final Counter paymentsSuccess;
    private final Counter fulfillmentsStarted;
    private volatile Instant lastCounterScan = Instant.now();

    public DomainProbe(PostgresProbeStore store, int stuckThresholdSeconds,
                       MeterRegistry registry) {
        this.store = store;
        this.stuckThresholdSeconds = stuckThresholdSeconds;
        this.registry = registry;
        // 注册名 = Prometheus 渲染名（T7 真机校准）：Micrometer/OpenMetrics 对 counter 保留
        // 后缀 _created_total 会剥成 _total、gauge 尾部 _total 会被剥——注册面直接用渲染名，
        // 与 arena-business.yml 规则面严格一致（INV-AM2-5 探测面↔规则面冻结）
        this.ordersCreated = Counter.builder("oa_orders_total")
                .description("订单创建尝试数（探测增量累计，S24/S23 分母）").register(registry);
        this.ordersSuccess = Counter.builder("oa_orders_success_total")
                .description("订单成功数（ENABLED，探测增量累计，S23 分子）").register(registry);
        this.paymentsSuccess = Counter.builder("oa_payments_success_total")
                .description("支付成功数（CAPTURE SUCCEEDED，探测增量累计，S16 差值左项）").register(registry);
        this.fulfillmentsStarted = Counter.builder("oa_fulfillments_started_total")
                .description("履约发起数（探测增量累计，S21 差值右项）").register(registry);
        registerGauges();
    }

    private void registerGauges() {
        Gauge.builder("oa_stuck_orders_current", () -> openCount(STUCK))
                .description("当前打开的卡单 episode 数（F3 症状）").register(registry);
        Gauge.builder("oa_duplicate_orders_current", () -> openCount(DUPLICATE))
                .description("当前打开的重复单 episode 数（F1 症状）").register(registry);
        Gauge.builder("oa_state_violations_current", () -> openCount(STATE_VIOLATION))
                .description("当前打开的状态违规 episode 数（F2 症状）").register(registry);
        Gauge.builder("oa_domain_probe_up", () -> probeUp ? 1.0 : 0.0)
                .description("领域探针自证（1=上轮成功）").register(registry);
        Gauge.builder("oa_domain_probe_last_success_timestamp", () -> lastSuccessEpoch)
                .description("最近一次成功扫描的 epoch 秒（陈旧度 = 探测失明时长）")
                .register(registry);
        // —— M-a 业务对账 gauge（设计冻结名）——
        Gauge.builder("oa_payment_order_mismatch_current", () -> openCount(PAYMENT_ORDER_MISMATCH))
                .description("掉单 episode 数（F9 症状：支付成功订单未推进）").register(registry);
        Gauge.builder("oa_pending_payment_orders_current", () -> openCount(PENDING_PAYMENT))
                .description("待支付积压 episode 数（F10 症状：AUTH 沉默停 INITIATED）").register(registry);
        Gauge.builder("oa_duplicate_payments_current", () -> openCount(DUPLICATE_PAYMENT))
                .description("重复扣款 episode 数（F11 症状：同单多笔 CAPTURE 成功）").register(registry);
        Gauge.builder("oa_recon_diff_current", () -> openCount(RECON_SKEW))
                .description("三方对账差异数（F12 症状：DEDUCT 类型不全的 ENABLED 订单）").register(registry);
        Gauge.builder("oa_inventory_negative", () -> openCount(INVENTORY_OVERSELL))
                .description("库存超卖 episode 数（F13 症状：INVENTORY 超额扣减）").register(registry);
        Gauge.builder("oa_orders_vs_fulfillments_diff", () -> openCount(FULFILLMENT_GAP))
                .description("履约缺口 episode 数（F14 症状：ENABLED 无履约记录）").register(registry);
        Gauge.builder("oa_duplicate_fulfillments_current", () -> openCount(DUPLICATE_FULFILLMENT))
                .description("重复消费 episode 数（F15 症状：消费尝试 >1）").register(registry);
        Gauge.builder("oa_fulfillment_overdue_current", () -> openCount(FULFILLMENT_OVERDUE))
                .description("履约超时积压 episode 数（F17 症状：CONFIRMING 超龄）").register(registry);
    }

    private double openCount(String type) {
        return lastOpenCounts.getOrDefault(type, 0L);
    }

    private Counter detectedCounter(String type, String metricName) {
        return detectedCounters.computeIfAbsent(type,
                t -> registry.counter(metricName));
    }

    /** 一轮探测（异常不外抛：C-7 失败语义在内部收口） */
    public ScanResult scanOnce() {
        try {
            int stuck = sync(STUCK, store.stuckOrders(stuckThresholdSeconds),
                    "oa_stuck_orders_detected");
            int dups = sync(DUPLICATE, store.duplicateOrders(),
                    "oa_duplicate_orders_detected");
            int states = sync(STATE_VIOLATION, store.stateViolations(),
                    "oa_state_violations_detected");
            // —— M-a 业务对账探测（S16~S20）——
            sync(PAYMENT_ORDER_MISMATCH, store.paymentOrderMismatches(),
                    "oa_payment_order_mismatch_detected");
            sync(PENDING_PAYMENT, store.pendingPaymentOrders(stuckThresholdSeconds),
                    "oa_pending_payment_detected");
            sync(DUPLICATE_PAYMENT, store.duplicatePayments(),
                    "oa_duplicate_payments_detected");
            sync(RECON_SKEW, store.reconSkewOrders(),
                    "oa_recon_skew_detected");
            sync(INVENTORY_OVERSELL, store.inventoryOversell(),
                    "oa_inventory_oversell_detected");
            sync(FULFILLMENT_GAP, store.fulfillmentGapOrders(),
                    "oa_fulfillment_gap_detected");
            sync(DUPLICATE_FULFILLMENT, store.duplicateFulfillments(),
                    "oa_duplicate_fulfillment_detected");
            sync(FULFILLMENT_OVERDUE, store.fulfillmentOverdue(stuckThresholdSeconds),
                    "oa_fulfillment_overdue_detected");
            syncBusinessCounters();
            probeUp = true;
            lastSuccessEpoch = Instant.now().getEpochSecond();
            return new ScanResult(true, stuck, dups, states);
        } catch (DataAccessException e) {
            // C-7：保留末值 + probe_down；业务 gauge 一字不动
            probeUp = false;
            log.warn("领域探针失明（保留末值，不伪装恢复）: {}", e.getMostSpecificCause().getMessage());
            return new ScanResult(false,
                    (int) openCount(STUCK), (int) openCount(DUPLICATE),
                    (int) openCount(STATE_VIOLATION));
        }
    }

    /**
     * M-a 业务量 counter 增量（探测面从 DB 事实统计，INV-AM2-5）：
     * 窗口 = 上次成功增量扫描 → 本轮；本轮失败则窗口不推进（失败不丢数）。
     */
    private void syncBusinessCounters() {
        Instant now = Instant.now();
        Instant since = lastCounterScan;
        ordersCreated.increment(store.countOrdersCreatedSince(since));
        ordersSuccess.increment(store.countOrdersSuccessSince(since));
        paymentsSuccess.increment(store.countPaymentsSuccessSince(since));
        fulfillmentsStarted.increment(store.countFulfillmentsStartedSince(since));
        lastCounterScan = now;
    }

    /** 同步一类 episode；@return 同步后的打开数（= 当前违规事实数） */
    private int sync(String type, List<PostgresProbeStore.Violation> violations,
                     String counterName) {
        Map<String, String> wanted = new HashMap<>();
        for (var v : violations) {
            wanted.put(v.entityId(),
                    Digest.sha256Of(v.variant() + "|" + v.entityId()).value());
        }
        Map<String, PostgresProbeStore.OpenFinding> open = store.openFindings(type);
        long newEpisodes = 0;
        for (var entry : wanted.entrySet()) {
            if (open.containsKey(entry.getKey())) {
                store.touchEpisode(type, entry.getKey(), entry.getValue());
            } else {
                store.openEpisode(type, entry.getKey(), entry.getValue());
                newEpisodes++;
            }
        }
        List<String> toClose = new ArrayList<>(open.keySet());
        toClose.removeAll(wanted.keySet());
        store.closeEpisodes(type, toClose);
        if (newEpisodes > 0) {
            detectedCounter(type, counterName).increment(newEpisodes);
        }
        lastOpenCounts.put(type, (long) wanted.size());
        return wanted.size();
    }
}
