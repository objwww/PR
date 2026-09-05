package com.objwww.pr.arena.it;

import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * M2-04 迁移契约 IT（V1 订单基础表）：表/约束/唯一键/授权的数据库实证面。
 * 幂等表唯一键、台账回补幂等锚、支付流水唯一尝试号、非法状态写入必败。
 */
class ArenaV1MigrationContractIT extends ArenaPostgresITBase {

    @Test
    void tradeOrderRejectsIllegalBookingStatus() {
        UUID id = UUID.randomUUID();
        assertThatThrownBy(() -> adminJdbc.sql("""
                        INSERT INTO arena.oa_trade_order(id,intent_id,correlation_id,buyer_id,sku,
                            quantity,amount,booking_status,created_at,updated_at)
                        VALUES (:id,'i1','live-1','b','s',1,10.00,'PAUSED',now(),now())
                        """).param("id", id).update())
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("ck_trade_booking");
    }

    @Test
    void tradeOrderRequiresDiscardReasonWhenDiscarded() {
        UUID id = UUID.randomUUID();
        assertThatThrownBy(() -> adminJdbc.sql("""
                        INSERT INTO arena.oa_trade_order(id,intent_id,correlation_id,buyer_id,sku,
                            quantity,amount,booking_status,created_at,updated_at)
                        VALUES (:id,'i2','live-2','b','s',1,10.00,'DISCARDED',now(),now())
                        """).param("id", id).update())
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("ck_trade_discard_reason");
    }

    @Test
    void idempotencyTableKeyIsIntentId() {
        arenaJdbc.sql("""
                INSERT INTO arena.oa_idempotency_record(intent_id,request_digest,state,expires_at,
                    created_at,updated_at)
                VALUES ('intent-key-1',:d,'NEW',now()+interval '10 minutes',now(),now())
                """).param("d", "a".repeat(64)).update();
        assertThatThrownBy(() -> arenaJdbc.sql("""
                        INSERT INTO arena.oa_idempotency_record(intent_id,request_digest,state,
                            expires_at,created_at,updated_at)
                        VALUES ('intent-key-1',:d,'NEW',now()+interval '10 minutes',now(),now())
                        """).param("d", "b".repeat(64)).update())
                .as("幂等表唯一键：同 intent 只能一行（C-2）")
                .isInstanceOf(DuplicateKeyException.class);
    }

    @Test
    void idempotencyConsumedStateRequiresResultColumns() {
        assertThatThrownBy(() -> arenaJdbc.sql("""
                INSERT INTO arena.oa_idempotency_record(intent_id,request_digest,state,expires_at,
                    created_at,updated_at)
                VALUES ('intent-key-2',:d,'CONSUMED',now()+interval '10 minutes',now(),now())
                """).param("d", "c".repeat(64)).update())
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("ck_idem_consumed");
    }

    @Test
    void paymentRecordAttemptNumberIsUniquePerOrder() {
        UUID orderId = seedTradeOrder("pay-uq-1", "live-pay", "ENABLED", "NOT_PAY", null);
        insertPayment(orderId, 1);
        assertThatThrownBy(() -> insertPayment(orderId, 1))
                .as("支付流水唯一尝试号：uq(order_id, attempt_no)（C-1）")
                .isInstanceOf(DuplicateKeyException.class);
    }

    private void insertPayment(UUID orderId, int attemptNo) {
        arenaJdbc.sql("""
                INSERT INTO arena.oa_payment_record(id,order_id,attempt_no,kind,result,amount,
                    initiated_at,settled_at)
                VALUES (:id,:o,:no,'AUTH','SUCCEEDED',10.00,now(),now())
                """).param("id", UUID.randomUUID()).param("o", orderId)
                .param("no", attemptNo).update();
    }

    @Test
    void paymentTerminalResultRequiresSettledAt() {
        UUID orderId = seedTradeOrder("pay-st-1", "live-pay", "ENABLED", "NOT_PAY", null);
        assertThatThrownBy(() -> arenaJdbc.sql("""
                        INSERT INTO arena.oa_payment_record(id,order_id,attempt_no,kind,result,amount,
                            initiated_at,settled_at)
                        VALUES (:id,:o,1,'AUTH','DECLINED',10.00,now(),null)
                        """).param("id", UUID.randomUUID()).param("o", orderId).update())
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("ck_payment_settled");
    }

    @Test
    void ledgerRefundEntryIsIdempotencyAnchor() {
        UUID orderId = seedTradeOrder("led-1", "live-led", "ENABLED", "NOT_PAY", null);
        insertLedger(orderId, "INVENTORY", "DEDUCT", 1);
        insertLedger(orderId, "INVENTORY", "REFUND", 1);
        assertThatThrownBy(() -> insertLedger(orderId, "INVENTORY", "REFUND", 1))
                .as("回补幂等锚：同 (order,type,seq,direction) 只能一条 REFUND（§6.3）")
                .isInstanceOf(DuplicateKeyException.class);
    }

    private void insertLedger(UUID orderId, String type, String direction, int seq) {
        arenaJdbc.sql("""
                INSERT INTO arena.oa_resource_ledger(id,order_id,resource_type,direction,
                    deduction_seq,quantity,created_at)
                VALUES (:id,:o,:t,:dir,:seq,1,now())
                """).param("id", UUID.randomUUID()).param("o", orderId)
                .param("t", type).param("dir", direction).param("seq", seq).update();
    }

    @Test
    void fulfillmentOrderIsUniquePerTradeOrder() {
        // CREATED 种子不带履约单（种子助手只为 ENABLED/DISCARDED 配对），此处手工成对后撞唯一键
        UUID orderId = seedTradeOrder("ful-1", "live-ful", "CREATED", "NOT_PAY", null);
        insertFulfillment(orderId);
        assertThatThrownBy(() -> insertFulfillment(orderId))
                .as("履约单与交易单一一对应（C-1 成对出生）")
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    private void insertFulfillment(UUID orderId) {
        arenaJdbc.sql("""
                INSERT INTO arena.oa_fulfillment_order(id,trade_order_id,state,created_at,updated_at)
                VALUES (:id,:o,'CONFIRMING',now(),now())
                """).param("id", UUID.randomUUID()).param("o", orderId).update();
    }

    @Test
    void refundOrderRequiresKnownResponsibleParty() {
        UUID orderId = seedTradeOrder("ref-1", "live-ref", "ENABLED", "PAID", null);
        assertThatThrownBy(() -> arenaJdbc.sql("""
                        INSERT INTO arena.oa_refund_order(id,trade_order_id,reason,responsible_party,
                            amount,state,created_at,updated_at)
                        VALUES (:id,:o,'damage','COURIER',5.00,'REQUESTED',now(),now())
                        """).param("id", UUID.randomUUID()).param("o", orderId).update())
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("ck_refund_party");
    }

    @Test
    void migrationHistoryLandsInArenaSchema() {
        Long n = adminJdbc.sql(
                        "SELECT count(*) FROM information_schema.tables "
                                + "WHERE table_schema='arena' AND table_name='flyway_schema_history'")
                .query(Long.class).single();
        assertThat(n).as("arena 域历史表与 control 域（public）物理隔离").isEqualTo(1L);
    }
}
