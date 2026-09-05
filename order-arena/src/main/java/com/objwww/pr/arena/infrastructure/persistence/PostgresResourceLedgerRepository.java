package com.objwww.pr.arena.infrastructure.persistence;

import com.objwww.pr.arena.domain.model.LedgerDirection;
import com.objwww.pr.arena.domain.model.ResourceLedgerEntry;
import com.objwww.pr.arena.domain.repository.ResourceLedgerRepository;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.sql.Timestamp;
import java.util.List;
import java.util.UUID;

/** oa_resource_ledger 仓储（逐笔流水；REFUND 唯一锚冲突 = 幂等跳过）。 */
public class PostgresResourceLedgerRepository implements ResourceLedgerRepository {

    private final JdbcClient jdbc;

    public PostgresResourceLedgerRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void insertDeduct(ResourceLedgerEntry entry) {
        jdbc.sql("""
                INSERT INTO arena.oa_resource_ledger(id,order_id,resource_type,direction,
                    deduction_seq,quantity,created_at)
                VALUES (:id,:orderId,:type,'DEDUCT',:seq,:qty,:created)
                """).param("id", entry.id()).param("orderId", entry.orderId())
                .param("type", entry.resourceType().name()).param("seq", entry.deductionSeq())
                .param("qty", entry.quantity()).param("created", Timestamp.from(entry.createdAt()))
                .update();
    }

    @Override
    public boolean insertRefundIfAbsent(ResourceLedgerEntry refundEntry) {
        int inserted = jdbc.sql("""
                INSERT INTO arena.oa_resource_ledger(id,order_id,resource_type,direction,
                    deduction_seq,quantity,created_at)
                VALUES (:id,:orderId,:type,'REFUND',:seq,:qty,:created)
                ON CONFLICT (order_id, resource_type, deduction_seq, direction) DO NOTHING
                """).param("id", refundEntry.id()).param("orderId", refundEntry.orderId())
                .param("type", refundEntry.resourceType().name())
                .param("seq", refundEntry.deductionSeq()).param("qty", refundEntry.quantity())
                .param("created", Timestamp.from(refundEntry.createdAt()))
                .update();
        return inserted == 1;
    }

    @Override
    public List<ResourceLedgerEntry> listDeductions(UUID orderId) {
        return jdbc.sql("""
                        SELECT id,order_id,resource_type,direction,deduction_seq,quantity,created_at
                        FROM arena.oa_resource_ledger
                        WHERE order_id=:id AND direction='DEDUCT' ORDER BY deduction_seq
                        """).param("id", orderId)
                .query((rs, n) -> map(rs)).list();
    }

    @Override
    public boolean hasRefund(UUID orderId, String resourceType, int deductionSeq) {
        Integer n = jdbc.sql("""
                        SELECT count(*) FROM arena.oa_resource_ledger
                        WHERE order_id=:id AND resource_type=:type::text
                          AND direction='REFUND' AND deduction_seq=:seq
                        """).param("id", orderId).param("type", resourceType)
                .param("seq", deductionSeq).query(Integer.class).single();
        return n > 0;
    }

    private ResourceLedgerEntry map(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new ResourceLedgerEntry(
                UUID.fromString(rs.getString("id")),
                UUID.fromString(rs.getString("order_id")),
                com.objwww.pr.arena.domain.model.ResourceType.valueOf(rs.getString("resource_type")),
                LedgerDirection.valueOf(rs.getString("direction")),
                rs.getInt("deduction_seq"),
                rs.getInt("quantity"),
                rs.getTimestamp("created_at").toInstant());
    }
}
