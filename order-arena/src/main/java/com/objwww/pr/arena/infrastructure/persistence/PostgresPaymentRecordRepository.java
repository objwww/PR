package com.objwww.pr.arena.infrastructure.persistence;

import com.objwww.pr.arena.domain.model.PaymentRecord;
import com.objwww.pr.arena.domain.model.PaymentRecord.PaymentKind;
import com.objwww.pr.arena.domain.model.PaymentResult;
import com.objwww.pr.arena.domain.repository.PaymentRecordRepository;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.sql.Timestamp;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** oa_payment_record 仓储（C-1 支付事实面）。 */
public class PostgresPaymentRecordRepository implements PaymentRecordRepository {

    private final JdbcClient jdbc;

    public PostgresPaymentRecordRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void insertInitiated(PaymentRecord record) {
        jdbc.sql("""
                INSERT INTO arena.oa_payment_record(id,order_id,attempt_no,kind,result,amount,
                    initiated_at,settled_at)
                VALUES (:id,:orderId,:attemptNo,:kind,'INITIATED',:amount,:initiated,null)
                """).param("id", record.id()).param("orderId", record.orderId())
                .param("attemptNo", record.attemptNo()).param("kind", record.kind().name())
                .param("amount", record.amount())
                .param("initiated", Timestamp.from(record.initiatedAt()))
                .update();
    }

    @Override
    public boolean casResult(UUID id, PaymentResult from, PaymentResult to) {
        return jdbc.sql("""
                UPDATE arena.oa_payment_record
                SET result=:to,
                    settled_at = CASE WHEN :to IN ('SUCCEEDED','DECLINED') THEN now() ELSE settled_at END,
                    reconcile_owner = CASE WHEN :to IN ('SUCCEEDED','DECLINED') THEN null ELSE reconcile_owner END,
                    reconcile_lease_until = CASE WHEN :to IN ('SUCCEEDED','DECLINED') THEN null ELSE reconcile_lease_until END
                WHERE id=:id AND result=:from
                """).param("to", to.name()).param("id", id).param("from", from.name())
                .update() == 1;
    }

    @Override
    public Optional<PaymentRecord> findById(UUID id) {
        return jdbc.sql("""
                        SELECT id,order_id,attempt_no,kind,result,amount,initiated_at,settled_at
                        FROM arena.oa_payment_record WHERE id=:id
                        """).param("id", id)
                .query((rs, n) -> map(rs)).optional();
    }

    @Override
    public List<PaymentRecord> findByOrder(UUID orderId) {
        return jdbc.sql("""
                        SELECT id,order_id,attempt_no,kind,result,amount,initiated_at,settled_at
                        FROM arena.oa_payment_record WHERE order_id=:id ORDER BY attempt_no
                        """).param("id", orderId)
                .query((rs, n) -> map(rs)).list();
    }

    @Override
    public int nextAttemptNo(UUID orderId, PaymentKind kind) {
        Integer max = jdbc.sql("""
                        SELECT COALESCE(max(attempt_no), 0) FROM arena.oa_payment_record
                        WHERE order_id=:id AND kind=:kind
                        """).param("id", orderId).param("kind", kind.name())
                .query(Integer.class).single();
        return max + 1;
    }

    @Override
    public List<PaymentRecord> findUnsettled(PaymentKind kind) {
        return jdbc.sql("""
                        SELECT id,order_id,attempt_no,kind,result,amount,initiated_at,settled_at
                        FROM arena.oa_payment_record
                        WHERE kind=:kind AND result IN ('INITIATED','UNKNOWN','RECONCILING')
                        ORDER BY initiated_at
                        """).param("kind", kind.name())
                .query((rs, n) -> map(rs)).list();
    }

    @Override
    public List<PaymentRecord> claimReconcileWork(String owner, java.time.Duration lease,
                                                  java.time.Duration unknownOlderThan, int limit) {
        // F3 会话 ACTIVE 期间其靶面订单不可领（超时未知持续）——off 后进入对账窗口
        // （恢复 = 延迟结算；告警面 ArenaOrderStuck 因此有真实观察窗）
        return jdbc.sql("""
                UPDATE arena.oa_payment_record p
                SET result='RECONCILING',
                    reconcile_owner=:owner,
                    reconcile_lease_until = now() + make_interval(secs => :leaseSeconds)
                WHERE p.id IN (
                    SELECT p2.id FROM arena.oa_payment_record p2
                    WHERE (
                            (p2.result = 'UNKNOWN'
                             AND p2.initiated_at < now() - make_interval(secs => :olderSeconds))
                         OR (p2.result = 'RECONCILING' AND p2.reconcile_lease_until < now())
                          )
                      AND NOT EXISTS (
                        SELECT 1 FROM arena.oa_trade_order t
                        JOIN arena.oa_chaos_session s ON s.fault_type = 'F3'
                         AND s.state = 'ACTIVE'
                         AND (s.target IS NULL OR t.correlation_id LIKE s.target || '%')
                        WHERE t.id = p2.order_id)
                    ORDER BY p2.initiated_at
                    LIMIT :limit
                    FOR UPDATE SKIP LOCKED
                )
                RETURNING id,order_id,attempt_no,kind,result,amount,initiated_at,settled_at
                """)
                .param("owner", owner)
                .param("leaseSeconds", lease.toSeconds())
                .param("olderSeconds", unknownOlderThan.toSeconds())
                .param("limit", limit)
                .query((rs, n) -> map(rs))
                .list();
    }

    @Override
    public void releaseReconcileLease(UUID id) {
        jdbc.sql("""
                UPDATE arena.oa_payment_record
                SET reconcile_owner = null, reconcile_lease_until = null
                WHERE id=:id
                """).param("id", id).update();
    }

    private PaymentRecord map(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new PaymentRecord(
                UUID.fromString(rs.getString("id")),
                UUID.fromString(rs.getString("order_id")),
                rs.getInt("attempt_no"),
                PaymentKind.valueOf(rs.getString("kind")),
                PaymentResult.valueOf(rs.getString("result")),
                rs.getBigDecimal("amount"),
                rs.getTimestamp("initiated_at").toInstant(),
                Optional.ofNullable(rs.getTimestamp("settled_at")).map(Timestamp::toInstant).orElse(null));
    }
}
