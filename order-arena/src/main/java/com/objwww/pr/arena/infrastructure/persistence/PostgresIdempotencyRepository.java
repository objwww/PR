package com.objwww.pr.arena.infrastructure.persistence;

import com.objwww.pr.arena.domain.model.IdempotencyClaim;
import com.objwww.pr.arena.domain.repository.IdempotencyRepository;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.time.Duration;
import java.util.Map;
import java.util.UUID;

/**
 * oa_idempotency_record 仓储（C-2）。
 * claim 的关键路径：INSERT ... ON CONFLICT DO NOTHING 的原子性承担"check-and-mark"，
 * 冲突后对既有行的判定与过期回收全部走 CAS UPDATE（影响行数 = 判定依据），
 * 不依赖应用锁。
 */
public class PostgresIdempotencyRepository implements IdempotencyRepository {

    private final JdbcClient jdbc;

    public PostgresIdempotencyRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public IdempotencyClaim claim(String intentId, String requestDigest, String owner,
                                  Duration leaseDuration, Duration ttl) {
        int inserted = jdbc.sql("""
                INSERT INTO arena.oa_idempotency_record
                    (intent_id, request_digest, state, owner, lease_until, lease_epoch,
                     expires_at, created_at, updated_at)
                VALUES (:intent, :digest, 'PROCESSING', :owner,
                        now() + make_interval(secs => :lease), 0,
                        now() + make_interval(secs => :ttl), now(), now())
                ON CONFLICT (intent_id) DO NOTHING
                """)
                .param("intent", intentId).param("digest", requestDigest).param("owner", owner)
                .param("lease", leaseDuration.toSeconds())
                .param("ttl", ttl.toSeconds())
                .update();
        if (inserted == 1) {
            return new IdempotencyClaim.Claimed(0);
        }
        return decideOnExisting(intentId, requestDigest, owner, leaseDuration, ttl);
    }

    /** 现存幂等行的显式投影（JdbcClient 不支持接口类型映射，走行映射器） */
    private record IdemRow(String requestDigest, String state, UUID resultOrderId,
                           String responseDigest, java.sql.Timestamp leaseUntil, long epoch) {
    }

    private IdempotencyClaim decideOnExisting(String intentId, String requestDigest, String owner,
                                              Duration leaseDuration, Duration ttl) {
        IdemRow row = jdbc.sql("""
                SELECT request_digest, state, owner, lease_until, lease_epoch, result_order_id,
                       response_digest, expires_at
                FROM arena.oa_idempotency_record WHERE intent_id = :intent
                """).param("intent", intentId)
                .query((rs, n) -> {
                    String orderId = rs.getString("result_order_id");
                    return new IdemRow(
                            rs.getString("request_digest"),
                            rs.getString("state"),
                            orderId == null ? null : UUID.fromString(orderId),
                            rs.getString("response_digest"),
                            rs.getTimestamp("lease_until"),
                            rs.getLong("lease_epoch"));
                }).single();

        if (!requestDigest.equals(row.requestDigest())) {
            return new IdempotencyClaim.Conflict();
        }
        String state = row.state();
        long epoch = row.epoch();

        switch (state) {
            case "CONSUMED" -> {
                return new IdempotencyClaim.Replay(row.resultOrderId(), row.responseDigest());
            }
            case "PROCESSING" -> {
                java.sql.Timestamp leaseUntil = row.leaseUntil();
                if (leaseUntil != null && leaseUntil.toInstant().isAfter(java.time.Instant.now())) {
                    return new IdempotencyClaim.InProgress();
                }
                // 租约过期回收：CAS 抢租约（并发下只有一个 owner 成功）
                int reclaimed = jdbc.sql("""
                        UPDATE arena.oa_idempotency_record
                        SET owner=:owner, lease_until=now()+make_interval(secs => :lease),
                            lease_epoch=lease_epoch+1, updated_at=now()
                        WHERE intent_id=:intent AND state='PROCESSING' AND lease_until <= now()
                        """).param("owner", owner).param("lease", leaseDuration.toSeconds())
                        .param("intent", intentId).update();
                if (reclaimed == 1) {
                    return new IdempotencyClaim.Claimed(epoch + 1);
                }
                return new IdempotencyClaim.InProgress();
            }
            case "NEW", "EXPIRED" -> {
                int claimed = jdbc.sql("""
                        UPDATE arena.oa_idempotency_record
                        SET state='PROCESSING', owner=:owner,
                            lease_until=now()+make_interval(secs => :lease),
                            lease_epoch=lease_epoch+1, updated_at=now()
                        WHERE intent_id=:intent AND state=:state
                        """).param("owner", owner).param("lease", leaseDuration.toSeconds())
                        .param("intent", intentId).param("state", state).update();
                if (claimed == 1) {
                    return new IdempotencyClaim.Claimed(epoch + 1);
                }
                // 并发竞争失败：按当前状态再判一次（本轮直接以重入语义给确定结果）
                return new IdempotencyClaim.InProgress();
            }
            default -> throw new IllegalStateException("未知幂等状态: " + state);
        }
    }

    @Override
    public boolean complete(String intentId, long leaseEpoch, UUID resultOrderId,
                            String responseDigest) {
        return jdbc.sql("""
                UPDATE arena.oa_idempotency_record
                SET state='CONSUMED', result_order_id=:orderId, response_digest=:digest,
                    owner=null, lease_until=null, updated_at=now()
                WHERE intent_id=:intent AND state='PROCESSING' AND lease_epoch=:epoch
                """).param("intent", intentId).param("epoch", leaseEpoch)
                .param("orderId", resultOrderId).param("digest", responseDigest)
                .update() == 1;
    }

    @Override
    public void release(String intentId, long leaseEpoch) {
        jdbc.sql("""
                UPDATE arena.oa_idempotency_record
                SET state='NEW', owner=null, lease_until=null, updated_at=now()
                WHERE intent_id=:intent AND state='PROCESSING' AND lease_epoch=:epoch
                """).param("intent", intentId).param("epoch", leaseEpoch).update();
    }
}
