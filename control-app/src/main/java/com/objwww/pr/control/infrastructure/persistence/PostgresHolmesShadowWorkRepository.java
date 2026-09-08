package com.objwww.pr.control.infrastructure.persistence;

import com.objwww.pr.control.alert.domain.repository.HolmesShadowWorkRepository;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * V34 holmes_shadow_work 仓储（M6-05）：SKIP LOCKED 批量认领 + 三元 CAS 收口。
 *
 * <p>认领面单语句原子（BA-46 同律——无 check-then-act 窗）：内层 SELECT ... FOR
 * UPDATE SKIP LOCKED 选行（QUEUED/FAILED 可领、LEASED 租约过期可回收、
 * attempts &lt; max_attempts 有界），外层 UPDATE 认领即租约续身 + attempts/epoch
 * 双 +1 + RETURNING 全列。并发 worker 各领各的行，撞锁行被跳过不等待。
 *
 * <p>装配面纪律同族（无 @Repository 注解）：只经 PersistenceConfig @Bean 注册，
 * 无 DataSource profile 不激活（ControlContextSmokeTest 契约）。
 */
public class PostgresHolmesShadowWorkRepository implements HolmesShadowWorkRepository {

    private static final String ENQUEUE_SQL = """
            INSERT INTO holmes_shadow_work (shadow_key, kind, native_run_id, incident_id,
                generation, snapshot_digest, state, attempts, max_attempts)
            VALUES (:shadowKey, :kind, :nativeRunId, :incidentId, :generation,
                :snapshotDigest, 'QUEUED', 0, :maxAttempts)
            ON CONFLICT (shadow_key) DO NOTHING
            """;

    private static final String COUNT_SQL = """
            SELECT count(*) FROM holmes_shadow_work WHERE created_at >= :after
            """;

    private static final String FIND_SQL = """
            SELECT id, shadow_key, kind, native_run_id, incident_id, generation,
                   snapshot_digest, state, attempts, max_attempts, lease_owner,
                   lease_until, lease_epoch, tokens_spent, last_error, created_at, updated_at
            FROM holmes_shadow_work WHERE shadow_key = :shadowKey
            """;

    private static final String CLAIM_SQL = """
            UPDATE holmes_shadow_work w
            SET state = 'LEASED', lease_owner = :owner, lease_until = :until,
                lease_epoch = w.lease_epoch + 1, attempts = w.attempts + 1,
                updated_at = :now
            WHERE w.id IN (
                SELECT c.id FROM holmes_shadow_work c
                WHERE (c.state IN ('QUEUED', 'FAILED')
                           AND c.attempts < c.max_attempts)
                  OR (c.state = 'LEASED' AND c.lease_until < :now
                           AND c.attempts < c.max_attempts)
                ORDER BY c.created_at
                LIMIT :limit
                FOR UPDATE SKIP LOCKED
            )
            RETURNING w.id, w.shadow_key, w.kind, w.native_run_id, w.incident_id,
                      w.generation, w.snapshot_digest, w.state, w.attempts,
                      w.max_attempts, w.lease_owner, w.lease_until, w.lease_epoch,
                      w.tokens_spent, w.last_error, w.created_at, w.updated_at
            """;

    private static final String COMPLETE_SQL = """
            UPDATE holmes_shadow_work
            SET state = 'SUCCEEDED', tokens_spent = :tokens, updated_at = :now
            WHERE id = :id AND state = 'LEASED'
              AND lease_owner = :owner AND lease_epoch = :epoch
            """;

    private static final String MARK_FAILED_SQL = """
            UPDATE holmes_shadow_work
            SET state = CASE WHEN attempts >= max_attempts THEN 'EXHAUSTED'
                             ELSE 'FAILED' END,
                last_error = :error, updated_at = :now
            WHERE id = :id AND state = 'LEASED'
              AND lease_owner = :owner AND lease_epoch = :epoch
            """;

    private final JdbcClient jdbc;

    public PostgresHolmesShadowWorkRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public boolean enqueue(ShadowWorkRow row) {
        // ON CONFLICT DO NOTHING（V32 uq_ec_pair 同律）：撞确定性 key = 幂等败者
        // 返 false，不抛（端口契约：Sampler 重放窗以此记 ALREADY_ENQUEUED）
        int inserted = jdbc.sql(ENQUEUE_SQL)
                .param("shadowKey", row.shadowKey())
                .param("kind", row.kind())
                .param("nativeRunId", row.nativeRunId())
                .param("incidentId", row.incidentId())
                .param("generation", row.generation())
                .param("snapshotDigest", row.snapshotDigest())
                .param("maxAttempts", row.maxAttempts())
                .update();
        return inserted == 1;
    }

    @Override
    public long countCreatedSince(Instant after) {
        return jdbc.sql(COUNT_SQL)
                .param("after", Timestamp.from(after))
                .query((rs, i) -> rs.getLong(1))
                .single();
    }

    @Override
    public Optional<ShadowWorkRow> findByShadowKey(String shadowKey) {
        return jdbc.sql(FIND_SQL)
                .param("shadowKey", shadowKey)
                .query((rs, i) -> mapRow(rs))
                .optional();
    }

    @Override
    public List<ShadowWorkRow> claimBatch(String owner, Instant now, Duration lease,
            int limit) {
        return jdbc.sql(CLAIM_SQL)
                .param("owner", owner)
                .param("until", Timestamp.from(now.plus(lease)))
                .param("now", Timestamp.from(now))
                .param("limit", limit)
                .query((rs, i) -> mapRow(rs))
                .list();
    }

    @Override
    public int complete(long id, String owner, int leaseEpoch, Integer tokensSpent,
            Instant now) {
        return jdbc.sql(COMPLETE_SQL)
                .param("tokens", tokensSpent)
                .param("now", Timestamp.from(now))
                .param("id", id)
                .param("owner", owner)
                .param("epoch", leaseEpoch)
                .update();
    }

    @Override
    public int markFailed(long id, String owner, int leaseEpoch, String error, Instant now) {
        return jdbc.sql(MARK_FAILED_SQL)
                .param("error", error)
                .param("now", Timestamp.from(now))
                .param("id", id)
                .param("owner", owner)
                .param("epoch", leaseEpoch)
                .update();
    }

    private static ShadowWorkRow mapRow(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new ShadowWorkRow(
                rs.getLong("id"),
                rs.getString("shadow_key"),
                rs.getString("kind"),
                rs.getObject("native_run_id", UUID.class),
                rs.getObject("incident_id", UUID.class),
                rs.getInt("generation"),
                rs.getString("snapshot_digest"),
                rs.getString("state"),
                rs.getInt("attempts"),
                rs.getInt("max_attempts"),
                rs.getString("lease_owner"),
                toInstant(rs.getTimestamp("lease_until")),
                rs.getInt("lease_epoch"),
                rs.getObject("tokens_spent") == null ? null : rs.getInt("tokens_spent"),
                rs.getString("last_error"),
                toInstant(rs.getTimestamp("created_at")),
                toInstant(rs.getTimestamp("updated_at")));
    }

    private static Instant toInstant(Timestamp ts) {
        return ts == null ? null : ts.toInstant();
    }
}
