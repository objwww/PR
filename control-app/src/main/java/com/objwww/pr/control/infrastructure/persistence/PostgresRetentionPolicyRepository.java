package com.objwww.pr.control.infrastructure.persistence;

import com.objwww.pr.control.ops.domain.model.LegalHold;
import com.objwww.pr.control.ops.domain.model.RetentionPolicy;
import com.objwww.pr.control.ops.domain.repository.RetentionPolicyRepository;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.sql.Timestamp;
import java.sql.Types;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * V29 保留域三表的 Postgres 实现（M5-18）。latestPolicy = policy_version 最大行
 * （同版本取 created_at 最新，确定性 tie-break）；releaseHold 带 released_at IS NULL
 * 谓词——二次释放 0 行返回 false（幂等二次释放零容忍的 DB 面）。
 */
public class PostgresRetentionPolicyRepository implements RetentionPolicyRepository {

    private final JdbcClient jdbc;

    public PostgresRetentionPolicyRepository(JdbcClient jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc);
    }

    @Override
    public void insertPolicy(RetentionPolicy policy) {
        jdbc.sql("""
                INSERT INTO retention_policy (
                    id, policy_version, hot_retention_days, cold_location,
                    legal_hold, created_at
                ) VALUES (
                    :id, :version, :hotDays, :cold, :legalHold, :createdAt
                )
                """)
                .param("id", policy.id())
                .param("version", policy.policyVersion())
                .param("hotDays", policy.hotRetentionDays())
                .param("cold", policy.coldLocation())
                .param("legalHold", policy.legalHold())
                .param("createdAt", Timestamp.from(policy.createdAt()))
                .update();
    }

    @Override
    public Optional<RetentionPolicy> latestPolicy() {
        return jdbc.sql("""
                        SELECT id, policy_version, hot_retention_days, cold_location,
                               legal_hold, created_at
                          FROM retention_policy
                         ORDER BY policy_version DESC, created_at DESC
                         LIMIT 1
                        """)
                .query((rs, i) -> new RetentionPolicy(
                        rs.getObject("id", UUID.class),
                        rs.getInt("policy_version"),
                        rs.getLong("hot_retention_days"),
                        rs.getString("cold_location"),
                        rs.getBoolean("legal_hold"),
                        rs.getTimestamp("created_at").toInstant()))
                .optional();
    }

    @Override
    public UUID insertHold(String scope, String reason, String createdBy, Instant at) {
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO legal_hold (id, scope, reason, created_by, created_at)
                VALUES (:id, :scope, :reason, :createdBy, :createdAt)
                """)
                .param("id", id)
                .param("scope", scope)
                .param("reason", reason)
                .param("createdBy", createdBy)
                .param("createdAt", Timestamp.from(at))
                .update();
        return id;
    }

    @Override
    public List<LegalHold> activeHolds() {
        return jdbc.sql("""
                        SELECT id, scope, reason, created_by, created_at, released_at
                          FROM legal_hold
                         WHERE released_at IS NULL
                         ORDER BY created_at, id
                        """)
                .query((rs, i) -> new LegalHold(
                        rs.getObject("id", UUID.class),
                        rs.getString("scope"),
                        rs.getString("reason"),
                        rs.getString("created_by"),
                        rs.getTimestamp("created_at").toInstant(),
                        null))
                .list();
    }

    @Override
    public boolean releaseHold(UUID holdId, Instant at) {
        return jdbc.sql("""
                UPDATE legal_hold SET released_at = :at
                 WHERE id = :id AND released_at IS NULL
                """)
                .param("at", Timestamp.from(at))
                .param("id", holdId, Types.OTHER)
                .update() > 0;
    }
}
