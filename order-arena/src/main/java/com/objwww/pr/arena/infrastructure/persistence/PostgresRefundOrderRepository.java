package com.objwww.pr.arena.infrastructure.persistence;

import com.objwww.pr.arena.domain.model.RefundOrder;
import com.objwww.pr.arena.domain.model.RefundParty;
import com.objwww.pr.arena.domain.model.RefundState;
import com.objwww.pr.arena.domain.repository.RefundOrderRepository;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.sql.Timestamp;
import java.util.Optional;
import java.util.UUID;

/** oa_refund_order 仓储（M2-11）。 */
public class PostgresRefundOrderRepository implements RefundOrderRepository {

    private final JdbcClient jdbc;

    public PostgresRefundOrderRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void insert(RefundOrder refund) {
        jdbc.sql("""
                INSERT INTO arena.oa_refund_order(id,trade_order_id,reason,responsible_party,
                    amount,state,created_at,updated_at,settled_at)
                VALUES (:id,:orderId,:reason,:party,:amount,:state,:created,:created,:settled)
                """).param("id", refund.id()).param("orderId", refund.tradeOrderId())
                .param("reason", refund.reason()).param("party", refund.responsibleParty().name())
                .param("amount", refund.amount()).param("state", refund.state().name())
                .param("created", Timestamp.from(refund.createdAt()))
                .param("settled", refund.settledAt() == null ? null : Timestamp.from(refund.settledAt()))
                .update();
    }

    @Override
    public boolean casState(UUID id, RefundState from, RefundState to) {
        return jdbc.sql("""
                UPDATE arena.oa_refund_order
                SET state=:to,
                    settled_at = CASE WHEN :to IN ('SUCCEEDED','REJECTED','CANCELLED')
                                      THEN now() ELSE settled_at END,
                    updated_at=now()
                WHERE id=:id AND state=:from
                """).param("to", to.name()).param("id", id).param("from", from.name())
                .update() == 1;
    }

    @Override
    public Optional<RefundOrder> findById(UUID id) {
        return jdbc.sql("""
                        SELECT id,trade_order_id,reason,responsible_party,amount,state,
                               created_at,updated_at,settled_at
                        FROM arena.oa_refund_order WHERE id=:id
                        """).param("id", id)
                .query((rs, n) -> new RefundOrder(
                        UUID.fromString(rs.getString("id")),
                        UUID.fromString(rs.getString("trade_order_id")),
                        rs.getString("reason"),
                        RefundParty.valueOf(rs.getString("responsible_party")),
                        rs.getBigDecimal("amount"),
                        RefundState.valueOf(rs.getString("state")),
                        rs.getTimestamp("created_at").toInstant(),
                        rs.getTimestamp("updated_at").toInstant(),
                        Optional.ofNullable(rs.getTimestamp("settled_at"))
                                .map(Timestamp::toInstant).orElse(null)))
                .optional();
    }
}
