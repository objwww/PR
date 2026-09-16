package com.objwww.pr.arena.infrastructure.persistence;

import org.springframework.jdbc.core.simple.JdbcClient;

import java.util.List;
import java.util.UUID;

/**
 * 履约消费尝试面（M-a F15/S22）：CONFIRMED 履约的下游消费账本。
 * 正常恰一次尝试（消费器幂等扫描）；F15（ack 失败）产生重复尝试行——
 * DomainProbe 以 attempt>1/履约单 检出重复消费（oa_duplicate_fulfillments_current）。
 */
public class PostgresFulfillmentAttemptStore {

    /** 待消费候选：CONFIRMED 且尚无尝试行的履约单（携带 correlation 供 chaos 判定） */
    public record Candidate(UUID fulfillmentId, String correlationId) {
    }

    private final JdbcClient jdbc;

    public PostgresFulfillmentAttemptStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public List<Candidate> confirmedWithoutAttempt() {
        return jdbc.sql("""
                SELECT f.id::text AS fid, t.correlation_id AS corr
                FROM arena.oa_fulfillment_order f
                  JOIN arena.oa_trade_order t ON t.id = f.trade_order_id
                WHERE f.state = 'CONFIRMED'
                  AND NOT EXISTS (SELECT 1 FROM arena.oa_fulfillment_attempt a
                                  WHERE a.fulfillment_id = f.id)
                ORDER BY f.created_at LIMIT 100
                """)
                .query((rs, i) -> new Candidate(
                        UUID.fromString(rs.getString("fid")), rs.getString("corr")))
                .list();
    }

    public void insertAttempt(UUID attemptId, UUID fulfillmentId, String consumer) {
        jdbc.sql("""
                INSERT INTO arena.oa_fulfillment_attempt(id, fulfillment_id, consumer)
                VALUES (:id, :fid, :consumer)
                """)
                .param("id", attemptId).param("fid", fulfillmentId)
                .param("consumer", consumer)
                .update();
    }
}
