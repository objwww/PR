package com.objwww.pr.control.infrastructure.persistence;

import com.objwww.pr.control.release.domain.repository.CanaryDecisionLogRepository;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.sql.Timestamp;
import java.util.Objects;

/**
 * canary_route_decision 的 Postgres 实现（V25；M5-10）。append-only：只 append 与
 * countNativeDecisions（爆炸半径重数源），零 UPDATE/DELETE 路径（授权面同构封死）。
 */
public class PostgresCanaryDecisionLogRepository implements CanaryDecisionLogRepository {

    private static final String APPEND_SQL = """
            INSERT INTO canary_route_decision (
                run_id, stickiness_key, canary_bucket, percent, bundle_digest,
                decision, created_at
            ) VALUES (
                :runId, :stickinessKey, :bucket, :percent, :bundleDigest,
                :decision, :createdAt
            )
            """;

    private static final String COUNT_NATIVE_SQL = """
            SELECT count(*) FROM canary_route_decision
             WHERE decision IN ('WHITELISTED', 'BUCKETED_NATIVE')
            """;

    private final JdbcClient jdbc;

    public PostgresCanaryDecisionLogRepository(JdbcClient jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc 不得为 null");
    }

    @Override
    public long countNativeDecisions() {
        Long count = jdbc.sql(COUNT_NATIVE_SQL).query(Long.class).single();
        return count == null ? 0L : count;
    }

    @Override
    public void append(DecisionRow row) {
        jdbc.sql(APPEND_SQL)
                .param("runId", row.runId())
                .param("stickinessKey", row.stickinessKey())
                .param("bucket", row.bucket())
                .param("percent", row.percent())
                .param("bundleDigest", row.bundleDigest() == null
                        ? null : row.bundleDigest().hex())
                .param("decision", row.decision())
                .param("createdAt", Timestamp.from(row.createdAt()))
                .update();
    }
}
