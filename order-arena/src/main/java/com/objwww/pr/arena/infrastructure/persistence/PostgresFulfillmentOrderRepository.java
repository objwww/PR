package com.objwww.pr.arena.infrastructure.persistence;

import com.objwww.pr.arena.domain.model.FulfillmentOrder;
import com.objwww.pr.arena.domain.model.FulfillmentState;
import com.objwww.pr.arena.domain.repository.FulfillmentOrderRepository;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.sql.Timestamp;
import java.util.Optional;
import java.util.UUID;

/** oa_fulfillment_order 仓储。 */
public class PostgresFulfillmentOrderRepository implements FulfillmentOrderRepository {

    private final JdbcClient jdbc;

    public PostgresFulfillmentOrderRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void insert(FulfillmentOrder order) {
        jdbc.sql("""
                INSERT INTO arena.oa_fulfillment_order(id,trade_order_id,state,created_at,updated_at)
                VALUES (:id,:tradeId,:state,:created,:created)
                """).param("id", order.id()).param("tradeId", order.tradeOrderId())
                .param("state", order.state().name())
                .param("created", Timestamp.from(order.createdAt()))
                .update();
    }

    @Override
    public Optional<FulfillmentOrder> findByTradeOrderId(UUID tradeOrderId) {
        return jdbc.sql("""
                        SELECT id,trade_order_id,state,created_at,updated_at
                        FROM arena.oa_fulfillment_order WHERE trade_order_id=:id
                        """).param("id", tradeOrderId)
                .query((rs, n) -> new FulfillmentOrder(
                        UUID.fromString(rs.getString("id")),
                        UUID.fromString(rs.getString("trade_order_id")),
                        FulfillmentState.valueOf(rs.getString("state")),
                        rs.getTimestamp("created_at").toInstant(),
                        rs.getTimestamp("updated_at").toInstant()))
                .optional();
    }

    @Override
    public boolean casState(UUID tradeOrderId, FulfillmentState from, FulfillmentState to) {
        return jdbc.sql("""
                UPDATE arena.oa_fulfillment_order
                SET state=:to, updated_at=now()
                WHERE trade_order_id=:id AND state=:from
                """).param("to", to.name()).param("id", tradeOrderId).param("from", from.name())
                .update() == 1;
    }
}
