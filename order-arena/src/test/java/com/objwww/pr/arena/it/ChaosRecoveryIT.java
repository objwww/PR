package com.objwww.pr.arena.it;

import com.objwww.pr.arena.application.F3ReconcileService;
import com.objwww.pr.arena.application.OrderCreationSteps;
import com.objwww.pr.arena.application.RefundChainService;
import com.objwww.pr.arena.application.chaos.ChaosRecoveryService;
import com.objwww.pr.arena.application.chaos.ChaosSwitchboard;
import com.objwww.pr.arena.infrastructure.persistence.PostgresChaosInjectionStore;
import com.objwww.pr.arena.infrastructure.persistence.PostgresCompensationOutboxRepository;
import com.objwww.pr.arena.infrastructure.persistence.PostgresFulfillmentOrderRepository;
import com.objwww.pr.arena.infrastructure.persistence.PostgresPaymentRecordRepository;
import com.objwww.pr.arena.infrastructure.persistence.PostgresResourceLedgerRepository;
import com.objwww.pr.arena.infrastructure.persistence.PostgresRefundOrderRepository;
import com.objwww.pr.arena.infrastructure.persistence.PostgresTradeOrderRepository;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * M2-18/19 恢复驱动 IT：
 * F1 = 同 intent 重复单补偿（canonical 保留，其余废单；live 零污染）；
 * F2 = ACTIVE 逐单回跳注入（审计幂等锚）→ RECOVERING 事实驱动恢复。
 * 会话推进（→CLOSED）归 arena-chaos-admin（C-3），此处只验 arena 侧职责。
 */
class ChaosRecoveryIT extends ArenaPostgresITBase {

    private ChaosRecoveryService service() {
        TransactionTemplate tx = new TransactionTemplate(
                new org.springframework.jdbc.datasource.DataSourceTransactionManager(
                        arenaDataSource()));
        var tradeOrders = new PostgresTradeOrderRepository(arenaJdbc,
                new PostgresFulfillmentOrderRepository(arenaJdbc));
        var fulfillments = new PostgresFulfillmentOrderRepository(arenaJdbc);
        var payments = new PostgresPaymentRecordRepository(arenaJdbc);
        var ledger = new PostgresResourceLedgerRepository(arenaJdbc);
        var outbox = new PostgresCompensationOutboxRepository(arenaJdbc);
        var injection = new PostgresChaosInjectionStore(arenaJdbc);
        var steps = new OrderCreationSteps(tx, tradeOrders, fulfillments, ledger,
                payments, outbox);
        var refundChain = new RefundChainService(
                action -> tx.executeWithoutResult(s -> action.run()),
                new PostgresRefundOrderRepository(arenaJdbc), tradeOrders);
        var f3 = new F3ReconcileService(payments, tradeOrders, steps, "it-f3",
                Duration.ofSeconds(60), Duration.ofSeconds(30), 8);
        return new ChaosRecoveryService(
                new ChaosSwitchboard(arenaJdbc, Duration.ZERO), injection, tradeOrders,
                payments, steps, refundChain, f3, 16);
    }

    private String bookingStatus(UUID orderId) {
        return adminJdbc.sql("SELECT booking_status FROM arena.oa_trade_order WHERE id = :id")
                .param("id", orderId).query(String.class).single();
    }

    private String discardReason(UUID orderId) {
        return adminJdbc.sql("SELECT discard_reason FROM arena.oa_trade_order WHERE id = :id")
                .param("id", orderId).query(String.class).single();
    }

    private List<Map<String, Object>> audits(UUID sessionId) {
        return adminJdbc.sql("""
                SELECT action, order_id FROM arena.oa_injection_audit
                WHERE session_id = :id ORDER BY occurred_at
                """).param("id", sessionId)
                .query((rs, i) -> {
                    // order_id 可空（会话级 RECOVERED 行）→ Map.of 拒 null，用 LinkedHashMap
                    Map<String, Object> row = new java.util.LinkedHashMap<>();
                    row.put("action", rs.getString("action"));
                    row.put("order", rs.getString("order_id"));
                    return row;
                })
                .list();
    }

    // ---------- F1 ----------

    @Test
    void F1恢复_canonical保留_其余废单_live零污染() {
        // 同 intent 两个 chaos- 重复单（都 ENABLED）；另有一个 live- 干净单同 intent 不同
        UUID dupA = seedTradeOrder("intent-dup-1", "chaos-f1x-a-1",
                "ENABLED", "NOT_PAY", null, "sku-std", 0);
        UUID dupB = seedTradeOrder("intent-dup-1", "chaos-f1x-b-2",
                "ENABLED", "NOT_PAY", null, "sku-std", 0);
        UUID liveOrder = seedTradeOrder("intent-live-clean", "live-clean-1",
                "ENABLED", "NOT_PAY", null, "sku-std", 0);

        seedChaosSession("f1-sc-rec", "F1", "chaos-f1x", "RECOVERING", 1, 600);

        int compensated = service().scanOnce();

        assertThat(compensated).isEqualTo(1); // 只补一个重复单
        String kept = bookingStatus(dupA);
        String other = bookingStatus(dupB);
        // canonical = created_at 最早（平局取 id 序）→ 恰好一留一废
        assertThat(Map.of(kept, other)).containsAnyOf(
                Map.entry("ENABLED", "DISCARDED"), Map.entry("DISCARDED", "ENABLED"));
        assertThat(discardReason("ENABLED".equals(kept) ? dupB : dupA))
                .isEqualTo("F1_DUPLICATE");
        assertThat(bookingStatus(liveOrder)).isEqualTo("ENABLED"); // live 零污染

        // 幂等：再扫一轮不重复补偿
        assertThat(service().scanOnce()).isZero();
    }

    @Test
    void F1恢复_PAID重复单_走退款链再废单() {
        UUID dupPaid = seedTradeOrder("intent-dup-2", "chaos-f1y-a-1",
                "ENABLED", "PAID", null, "sku-std", 0);
        UUID dupNotPaid = seedTradeOrder("intent-dup-2", "chaos-f1y-b-2",
                "ENABLED", "NOT_PAY", null, "sku-std", 0);
        seedChaosSession("f1-sc-paid", "F1", "chaos-f1y", "RECOVERING", 1, 600);

        service().scanOnce();

        boolean paidKept = "ENABLED".equals(bookingStatus(dupPaid));
        UUID discardedId = paidKept ? dupNotPaid : dupPaid;
        assertThat(bookingStatus(discardedId)).isEqualTo("DISCARDED");
        if (!paidKept) {
            // 被废的是 PAID 单：必须先退款（PAID→REFUNDED）再废单
            String payStatus = adminJdbc.sql("""
                    SELECT pay_status FROM arena.oa_trade_order WHERE id = :id
                    """).param("id", discardedId).query(String.class).single();
            assertThat(payStatus).isEqualTo("REFUNDED");
            assertThat(count("oa_refund_order")).isEqualTo(1);
        } else {
            assertThat(count("oa_refund_order")).isZero();
        }
    }

    // ---------- F2 ----------

    @Test
    void F2注入回跳_审计幂等_恢复事实驱动() {
        UUID target = seedTradeOrder("intent-f2-1", "chaos-f2z-1",
                "ENABLED", "NOT_PAY", null, "sku-std", 0);
        seedPayment(target, "AUTH", "SUCCEEDED", 0);
        UUID untouched = seedTradeOrder("intent-f2-2", "chaos-f2z-1",
                "ENABLED", "NOT_PAY", null, "sku-std", 0);
        seedPayment(untouched, "AUTH", "SUCCEEDED", 0);
        UUID sessionId = seedChaosSession("f2-sc-it", "F2", "chaos-f2z", "ACTIVE", 0, 600);
        // 注入有界批量=1：只有最早一单进入靶面
        TransactionTemplate tx = new TransactionTemplate(
                new org.springframework.jdbc.datasource.DataSourceTransactionManager(
                        arenaDataSource()));
        var tradeOrders = new PostgresTradeOrderRepository(arenaJdbc,
                new PostgresFulfillmentOrderRepository(arenaJdbc));
        var fulfillments = new PostgresFulfillmentOrderRepository(arenaJdbc);
        var payments = new PostgresPaymentRecordRepository(arenaJdbc);
        var injection = new PostgresChaosInjectionStore(arenaJdbc);
        ChaosRecoveryService bounded = new ChaosRecoveryService(
                new ChaosSwitchboard(arenaJdbc, Duration.ZERO), injection, tradeOrders,
                payments,
                new OrderCreationSteps(tx, tradeOrders, fulfillments,
                        new PostgresResourceLedgerRepository(arenaJdbc), payments,
                        new PostgresCompensationOutboxRepository(arenaJdbc)),
                new RefundChainService(
                        action -> tx.executeWithoutResult(s -> action.run()),
                        new PostgresRefundOrderRepository(arenaJdbc), tradeOrders),
                new F3ReconcileService(payments, tradeOrders,
                        new OrderCreationSteps(tx, tradeOrders, fulfillments,
                                new PostgresResourceLedgerRepository(arenaJdbc), payments,
                                new PostgresCompensationOutboxRepository(arenaJdbc)),
                        "it-f3", Duration.ofSeconds(60), Duration.ofSeconds(30), 8),
                1);

        // ACTIVE：注入回跳（批量=1：逐轮一单，注入在 ACTIVE 期间持续生效）
        assertThat(bounded.scanOnce()).isEqualTo(1);
        UUID firstInjected = "CREATED".equals(bookingStatus(target)) ? target : untouched;
        assertThat(bookingStatus(firstInjected)).isEqualTo("CREATED");
        // 第二轮：继续注入剩余靶面（幂等锚只防"同单重复"）
        assertThat(bounded.scanOnce()).isEqualTo(1);
        UUID secondInjected = firstInjected == target ? untouched : target;
        assertThat(bookingStatus(secondInjected)).isEqualTo("CREATED");
        // 靶面清空后再扫：零动作
        assertThat(bounded.scanOnce()).isZero();
        assertThat(adminJdbc.sql("""
                        SELECT count(*) FROM arena.oa_injection_audit
                        WHERE session_id = :id AND action = 'INJECTED'
                        """).param("id", sessionId).query(Long.class).single())
                .isEqualTo(2L);

        // RECOVERING：事实驱动恢复（enabled_at + AUTH SUCCEEDED 事实仍在）
        adminJdbc.sql("""
                UPDATE arena.oa_chaos_session SET state = 'RECOVERING',
                    generation = generation + 1 WHERE id = :id
                """).param("id", sessionId).update();
        assertThat(bounded.scanOnce()).isEqualTo(2);
        assertThat(bookingStatus(firstInjected)).isEqualTo("ENABLED");
        assertThat(bookingStatus(secondInjected)).isEqualTo("ENABLED");
        var sessionAudits = audits(sessionId);
        // 闭会话配对面：每条 INJECTED 必须有同单 RECOVERED，另有且仅有一条会话级 RECOVERED
        long injected = sessionAudits.stream()
                .filter(a -> "INJECTED".equals(a.get("action"))).count();
        List<Map<String, Object>> recoveredPerOrder = sessionAudits.stream()
                .filter(a -> "RECOVERED".equals(a.get("action")) && a.get("order") != null)
                .toList();
        long recoveredSessionLevel = sessionAudits.stream()
                .filter(a -> "RECOVERED".equals(a.get("action")) && a.get("order") == null)
                .count();
        assertThat(injected).isEqualTo(2L);
        assertThat(recoveredPerOrder).extracting(a -> UUID.fromString((String) a.get("order")))
                .containsExactlyInAnyOrder(firstInjected, secondInjected);
        assertThat(recoveredSessionLevel).isEqualTo(1L);
        // 幂等：再扫一轮零动作（逐单已配对 + 会话级已落）
        assertThat(bounded.scanOnce()).isZero();
    }
}
