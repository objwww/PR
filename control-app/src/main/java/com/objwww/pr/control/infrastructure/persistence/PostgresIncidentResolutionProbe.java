package com.objwww.pr.control.infrastructure.persistence;

import com.objwww.pr.control.eval.application.IncidentResolutionProbe;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.util.List;
import java.util.Objects;

/**
 * {@link IncidentResolutionProbe} 的 Postgres 实现：按期望 alertname 匹配 incident_key
 * （前缀精确到 '|' 边界，避免 alertname 前缀歧义），取 last_event_at 最新一行——
 * 无行（从未铸单）或 RESOLVED = episode 已关闭；FIRING = 残留现场，轮询至超时。
 * 轮询间隔 2s；超时返回 false 不抛中断批量（与 PrometheusAlertProbe 同律）。
 */
public class PostgresIncidentResolutionProbe implements IncidentResolutionProbe {

    private static final long POLL_INTERVAL_MILLIS = 2_000;

    private final JdbcClient jdbc;
    private final Sleeper sleeper;

    public PostgresIncidentResolutionProbe(JdbcClient jdbc, Sleeper sleeper) {
        this.jdbc = Objects.requireNonNull(jdbc);
        this.sleeper = Objects.requireNonNull(sleeper);
    }

    @FunctionalInterface
    public interface Sleeper {
        void sleep(long millis) throws InterruptedException;
    }

    @Override
    public boolean awaitIncidentResolved(String alertname, int maxWaitSeconds) {
        long deadline = System.currentTimeMillis() + maxWaitSeconds * 1000L;
        while (System.currentTimeMillis() < deadline) {
            if (isResolved(alertname)) {
                return true;
            }
            try {
                sleeper.sleep(POLL_INTERVAL_MILLIS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return isResolved(alertname);
    }

    /** 最新 incident 行缺席或 RESOLVED？（FIRING = 上轮 episode 未关闭） */
    private boolean isResolved(String alertname) {
        List<String> rows = jdbc.sql("""
                        SELECT status FROM incident
                        WHERE incident_key LIKE 'alertname=' || :an || '|%'
                           OR incident_key = 'alertname=' || :an
                        ORDER BY last_event_at DESC LIMIT 1
                        """)
                .param("an", alertname)
                .query((rs, i) -> rs.getString("status"))
                .list();
        return rows.isEmpty() || "RESOLVED".equals(rows.getFirst());
    }
}
