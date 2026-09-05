package com.objwww.pr.arena.infrastructure.persistence;

import com.objwww.pr.arena.domain.model.BookingStatus;
import com.objwww.pr.arena.domain.model.FulfillmentOrder;
import com.objwww.pr.arena.domain.model.FulfillmentState;
import com.objwww.pr.arena.domain.model.OrderSnapshot;
import com.objwww.pr.arena.domain.model.PayStatus;
import com.objwww.pr.arena.domain.model.TradeOrder;
import com.objwww.pr.arena.domain.repository.FulfillmentOrderRepository;
import com.objwww.pr.arena.domain.repository.TradeOrderRepository;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** oa_trade_order / oa_fulfillment_order 仓储。快照插入与查询按角色授权直达 arena schema。 */
public class PostgresTradeOrderRepository implements TradeOrderRepository {

    private final JdbcClient jdbc;
    private final FulfillmentOrderRepository fulfillmentOrders;

    public PostgresTradeOrderRepository(JdbcClient jdbc, FulfillmentOrderRepository fulfillmentOrders) {
        this.jdbc = jdbc;
        this.fulfillmentOrders = fulfillmentOrders;
    }

    @Override
    public OrderSnapshot insertCreatedSnapshot(TradeOrder order) {
        jdbc.sql("""
                INSERT INTO arena.oa_trade_order(id,intent_id,correlation_id,buyer_id,sku,quantity,
                    amount,booking_status,pay_status,created_at,updated_at)
                VALUES (:id,:intent,:corr,:buyer,:sku,:qty,:amount,'CREATED','NOT_PAY',
                        :created,:created)
                """)
                .param("id", order.id()).param("intent", order.intentId())
                .param("corr", order.correlationId()).param("buyer", order.buyerId())
                .param("sku", order.sku()).param("qty", order.quantity())
                .param("amount", order.amount()).param("created", Timestamp.from(order.createdAt()))
                .update();

        FulfillmentOrder fulfillment = FulfillmentOrder.create(UUID.randomUUID(), order.id());
        fulfillmentOrders.insert(fulfillment);
        return new OrderSnapshot(order, fulfillment);
    }

    @Override
    public Optional<TradeOrder> findById(UUID id) {
        return jdbc.sql("""
                        SELECT id,intent_id,correlation_id,buyer_id,sku,quantity,amount,
                               booking_status,pay_status,discard_reason,created_at,enabled_at,updated_at
                        FROM arena.oa_trade_order WHERE id=:id
                        """).param("id", id)
                .query((rs, n) -> map(rs)).optional();
    }

    @Override
    public Optional<TradeOrder> findVisibleById(UUID id) {
        return jdbc.sql("""
                        SELECT id,intent_id,correlation_id,buyer_id,sku,quantity,amount,
                               booking_status,pay_status,discard_reason,created_at,enabled_at,updated_at
                        FROM arena.oa_trade_order
                        WHERE id=:id AND booking_status <> 'CREATED'
                        """).param("id", id)
                .query((rs, n) -> map(rs)).optional();
    }

    @Override
    public boolean casBookingStatus(UUID id, BookingStatus from, BookingStatus to, String reason) {
        return jdbc.sql("""
                UPDATE arena.oa_trade_order
                SET booking_status=:to,
                    discard_reason = CASE WHEN :to = 'DISCARDED' THEN :reason ELSE discard_reason END,
                    enabled_at = CASE WHEN :to = 'ENABLED' THEN now() ELSE enabled_at END,
                    updated_at=now()
                WHERE id=:id AND booking_status=:from
                """).param("to", to.name()).param("reason", reason)
                .param("id", id).param("from", from.name()).update() == 1;
    }

    @Override
    public boolean casPayStatus(UUID id, PayStatus from, PayStatus to) {
        return jdbc.sql("""
                UPDATE arena.oa_trade_order
                SET pay_status=:to, updated_at=now()
                WHERE id=:id AND pay_status=:from AND (:to <> 'PAID' OR booking_status='ENABLED')
                """).param("to", to.name()).param("id", id).param("from", from.name())
                .update() == 1;
    }

    @Override
    public List<TradeOrder> findByIntentId(String intentId) {
        return jdbc.sql("""
                        SELECT id,intent_id,correlation_id,buyer_id,sku,quantity,amount,
                               booking_status,pay_status,discard_reason,created_at,enabled_at,updated_at
                        FROM arena.oa_trade_order WHERE intent_id=:intent
                        """).param("intent", intentId)
                .query((rs, n) -> map(rs)).list();
    }

    private TradeOrder map(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new TradeOrder(
                UUID.fromString(rs.getString("id")),
                rs.getString("intent_id"),
                rs.getString("correlation_id"),
                rs.getString("buyer_id"),
                rs.getString("sku"),
                rs.getInt("quantity"),
                rs.getBigDecimal("amount"),
                BookingStatus.valueOf(rs.getString("booking_status")),
                PayStatus.valueOf(rs.getString("pay_status")),
                rs.getString("discard_reason"),
                rs.getTimestamp("created_at").toInstant(),
                Optional.ofNullable(rs.getTimestamp("enabled_at")).map(Timestamp::toInstant).orElse(null),
                rs.getTimestamp("updated_at").toInstant());
    }

    /** 测试与自检用的金额转义（numeric 精度统一 12,2） */
    static BigDecimal normalizeAmount(BigDecimal amount) {
        return amount.setScale(2, java.math.RoundingMode.HALF_UP);
    }
}
