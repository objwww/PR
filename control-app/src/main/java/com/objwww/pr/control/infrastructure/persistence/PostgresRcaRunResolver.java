package com.objwww.pr.control.infrastructure.persistence;

import com.objwww.pr.control.eval.application.RcaRunResolver;
import com.objwww.pr.control.eval.domain.GoldenCase;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * RcaRunResolver 的 Postgres 实现（M3-17）：
 * <ul>
 *   <li>靶场场景（chaos_family 非空）：arena.oa_scenario_map 回填行（M2-24 C-6 归属，
 *       eval_app 只读）；scenario_id 与激活同法小写；</li>
 *   <li>flagd 场景（S1/S2，无靶场 map）：incident_key 匹配期望 alertname 且
 *       rca_run.created_at 晚于激活时刻的最新 run。</li>
 * </ul>
 * 轮询直到 timeoutSeconds 耗尽；全程只读，找不到 = empty（评分落缺席，不猜不凑）。
 */
public class PostgresRcaRunResolver implements RcaRunResolver {

    private static final long POLL_INTERVAL_MILLIS = 2_000;

    private final JdbcClient jdbc;
    private final Sleeper sleeper;

    public PostgresRcaRunResolver(JdbcClient jdbc, Sleeper sleeper) {
        this.jdbc = Objects.requireNonNull(jdbc);
        this.sleeper = Objects.requireNonNull(sleeper);
    }

    @FunctionalInterface
    public interface Sleeper {
        void sleep(long millis) throws InterruptedException;
    }

    @Override
    public Optional<UUID> resolve(GoldenCase golden, Instant activatedAt, int timeoutSeconds) {
        long deadline = System.currentTimeMillis() + timeoutSeconds * 1000L;
        while (System.currentTimeMillis() < deadline) {
            Optional<UUID> found = golden.chaosFamily() == null
                    ? resolveByIncident(golden, activatedAt)
                    : resolveByScenarioMap(golden);
            if (found.isPresent()) {
                return found;
            }
            try {
                sleeper.sleep(POLL_INTERVAL_MILLIS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return Optional.empty();
            }
        }
        return Optional.empty();
    }

    private Optional<UUID> resolveByScenarioMap(GoldenCase golden) {
        List<String> rows = jdbc.sql("""
                        SELECT run_id FROM arena.oa_scenario_map
                        WHERE scenario_id = :sid AND run_id IS NOT NULL
                        ORDER BY mapping_version DESC LIMIT 1
                        """)
                .param("sid", golden.scenarioId().toLowerCase())
                .query((rs, i) -> rs.getString("run_id"))
                .list();
        return rows.isEmpty() ? Optional.empty() : parseUuid(rows.getFirst());
    }

    private Optional<UUID> resolveByIncident(GoldenCase golden, Instant activatedAt) {
        String alertname = golden.expectedSymptomCodes().isEmpty()
                ? "" : golden.expectedSymptomCodes().getFirst();
        List<String> rows = jdbc.sql("""
                        SELECT r.id FROM rca_run r
                        JOIN incident i ON i.id = r.incident_id
                        WHERE r.created_at > :since
                          AND i.incident_key LIKE :pattern
                        ORDER BY r.created_at DESC LIMIT 1
                        """)
                .param("since", Timestamp.from(activatedAt))
                .param("pattern", "%alertname=" + alertname + "%")
                .query((rs, i) -> rs.getString("id"))
                .list();
        return rows.isEmpty() ? Optional.empty() : parseUuid(rows.getFirst());
    }

    private static Optional<UUID> parseUuid(String value) {
        try {
            return Optional.of(UUID.fromString(value));
        } catch (RuntimeException e) {
            return Optional.empty();
        }
    }
}
