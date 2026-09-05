package com.objwww.pr.arena.it;

import com.objwww.pr.arena.application.F3ReconcileService;
import com.objwww.pr.arena.application.OrderCreationSteps;
import com.objwww.pr.arena.infrastructure.persistence.PostgresCompensationOutboxRepository;
import com.objwww.pr.arena.infrastructure.persistence.PostgresFulfillmentOrderRepository;
import com.objwww.pr.arena.infrastructure.persistence.PostgresPaymentRecordRepository;
import com.objwww.pr.arena.infrastructure.persistence.PostgresResourceLedgerRepository;
import com.objwww.pr.arena.infrastructure.persistence.PostgresTradeOrderRepository;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * M2-20 F3 对账 IT：确定性裁定（拒绝→废单补偿、迟到成功→补 ENABLE/补 PAID）、
 * UNKNOWN 静默期、RECONCILING 租约互斥与过期重领（crash 续跑，全程无 sleep）。
 */
class F3ReconcileIT extends ArenaPostgresITBase {

    private F3ReconcileService service(int unknownOlderThanSeconds) {
        TransactionTemplate tx = new TransactionTemplate(
                new org.springframework.jdbc.datasource.DataSourceTransactionManager(
                        arenaDataSource()));
        var fulfillments = new PostgresFulfillmentOrderRepository(arenaJdbc);
        return new F3ReconcileService(
                new PostgresPaymentRecordRepository(arenaJdbc),
                new PostgresTradeOrderRepository(arenaJdbc, fulfillments),
                new OrderCreationSteps(tx,
                        new PostgresTradeOrderRepository(arenaJdbc, fulfillments),
                        fulfillments,
                        new PostgresResourceLedgerRepository(arenaJdbc),
                        new PostgresPaymentRecordRepository(arenaJdbc),
                        new PostgresCompensationOutboxRepository(arenaJdbc)),
                "it-f3", Duration.ofSeconds(60),
                Duration.ofSeconds(unknownOlderThanSeconds), 8);
    }

    private String paymentResult(UUID paymentId) {
        return adminJdbc.sql("SELECT result FROM arena.oa_payment_record WHERE id = :id")
                .param("id", paymentId).query(String.class).single();
    }

    private String bookingStatus(UUID orderId) {
        return adminJdbc.sql("SELECT booking_status FROM arena.oa_trade_order WHERE id = :id")
                .param("id", orderId).query(String.class).single();
    }

    @Test
    void 拒绝路径_AUTH拒绝且卡CREATED_废单补偿() {
        UUID order = seedTradeOrder("intent-f3-d", "chaos-f3-d-1",
                "CREATED", "NOT_PAY", null, "sku-std", 0);
        UUID payment = seedPayment(order, "AUTH", "UNKNOWN", 600);

        int processed = service(30).reconcileOnce();

        assertThat(processed).isEqualTo(1);
        assertThat(paymentResult(payment)).isEqualTo("DECLINED");
        assertThat(bookingStatus(order)).isEqualTo("DISCARDED");
        String reason = adminJdbc.sql(
                        "SELECT discard_reason FROM arena.oa_trade_order WHERE id = :id")
                .param("id", order).query(String.class).single();
        assertThat(reason).isEqualTo("PAYMENT_DECLINED_F3");
        // 废单同事务出补偿计划（outbox 一行待领取）
        assertThat(count("oa_compensation_outbox")).isEqualTo(1);
    }

    @Test
    void 迟到成功路径_AUTH补ENABLE() {
        UUID order = seedTradeOrder("intent-f3-l", "chaos-f3-l-1",
                "CREATED", "NOT_PAY", null, "sku-x-latesuccess", 0);
        UUID payment = seedPayment(order, "AUTH", "UNKNOWN", 600);

        service(30).reconcileOnce();

        assertThat(paymentResult(payment)).isEqualTo("SUCCEEDED");
        assertThat(bookingStatus(order)).isEqualTo("ENABLED");
        String payStatus = adminJdbc.sql(
                        "SELECT pay_status FROM arena.oa_trade_order WHERE id = :id")
                .param("id", order).query(String.class).single();
        assertThat(payStatus).isEqualTo("NOT_PAY"); // AUTH 成功 ≠ PAID（C-1：支付是回调面）
    }

    @Test
    void 迟到成功路径_CAPTURE补PAID() {
        UUID order = seedTradeOrder("intent-f3-c", "chaos-f3-c-1",
                "ENABLED", "NOT_PAY", null, "sku-x-latesuccess", 0);
        UUID payment = seedPayment(order, "CAPTURE", "UNKNOWN", 600);

        service(30).reconcileOnce();

        assertThat(paymentResult(payment)).isEqualTo("SUCCEEDED");
        String payStatus = adminJdbc.sql(
                        "SELECT pay_status FROM arena.oa_trade_order WHERE id = :id")
                .param("id", order).query(String.class).single();
        assertThat(payStatus).isEqualTo("PAID");
        assertThat(bookingStatus(order)).isEqualTo("ENABLED");
    }

    @Test
    void UNKNOWN静默期未到_不领取() {
        UUID order = seedTradeOrder("intent-f3-f", "chaos-f3-f-1",
                "CREATED", "NOT_PAY", null, "sku-std", 0);
        UUID payment = seedPayment(order, "AUTH", "UNKNOWN", 1);

        int processed = service(30).reconcileOnce();

        assertThat(processed).isZero();
        assertThat(paymentResult(payment)).isEqualTo("UNKNOWN");
        assertThat(bookingStatus(order)).isEqualTo("CREATED");
    }

    @Test
    void 租约互斥_被他人持有不动_租约过期重领() {
        UUID order = seedTradeOrder("intent-f3-m", "chaos-f3-m-1",
                "CREATED", "NOT_PAY", null, "sku-std", 0);
        UUID payment = seedPayment(order, "AUTH", "UNKNOWN", 600);

        // 模拟另一 reconciler 持有未过期租约（CAS UNKNOWN→RECONCILING）
        adminJdbc.sql("""
                UPDATE arena.oa_payment_record
                SET result = 'RECONCILING', reconcile_owner = 'other',
                    reconcile_lease_until = now() + interval '1 hour'
                WHERE id = :id
                """).param("id", payment).update();

        assertThat(service(30).reconcileOnce()).isZero();
        assertThat(paymentResult(payment)).isEqualTo("RECONCILING");

        // 租约过期 → 重领并裁定（崩溃续跑面）
        adminJdbc.sql("""
                UPDATE arena.oa_payment_record
                SET reconcile_lease_until = now() - interval '1 second'
                WHERE id = :id
                """).param("id", payment).update();

        assertThat(service(30).reconcileOnce()).isEqualTo(1);
        assertThat(paymentResult(payment)).isEqualTo("DECLINED");
        assertThat(bookingStatus(order)).isEqualTo("DISCARDED");
    }
}
