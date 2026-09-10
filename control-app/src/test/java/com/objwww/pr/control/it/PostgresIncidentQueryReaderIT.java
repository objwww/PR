package com.objwww.pr.control.it;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.objwww.pr.control.alert.domain.repository.IncidentQueryReader.IncidentSummary;
import com.objwww.pr.control.infrastructure.persistence.PostgresIncidentQueryReader;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * UI-1 PostgresIncidentQueryReader 真 PG 钉（failsafe *IT；本机无 docker 自动跳过）。
 * 缘起：195 部署验证实捕——summary 的 avg() 返回 numeric，
 * {@code rs.getObject("mttr", Double.class)} 在真驱动上直抛
 * "conversion to class java.lang.Double from numeric not supported"
 * （假件单测不可见，本地 1104 绿全漏）。
 */
class PostgresIncidentQueryReaderIT extends PostgresITBase {

    private PostgresIncidentQueryReader reader;

    @BeforeEach
    void setUp() {
        reader = new PostgresIncidentQueryReader(controlJdbc, new ObjectMapper());
    }

    @Test
    void summaryMapsAvgNumericToDouble() {
        Instant now = Instant.now();
        // 一条 24h 内 resolved（mttr=30min），一条 firing
        insertIncident(UUID.randomUUID(), "k-resolved", "RESOLVED",
                now.minus(Duration.ofHours(1)), now.minus(Duration.ofMinutes(30)));
        insertIncident(UUID.randomUUID(), "k-firing", "FIRING",
                now.minus(Duration.ofMinutes(10)), null);

        IncidentSummary out = reader.summary(now.minus(Duration.ofHours(24)));

        assertThat(out.mttrMinutes24h()).isNotNull().isEqualTo(30.0);
        assertThat(out.firingTotal()).isEqualTo(1);
        assertThat(out.unassigned()).isEqualTo(1);
    }

    @Test
    void summaryReturnsNullMttrWhenNoRecentResolved() {
        Instant now = Instant.now();
        insertIncident(UUID.randomUUID(), "k-firing-only", "FIRING", now, null);

        IncidentSummary out = reader.summary(now.minus(Duration.ofHours(24)));

        assertThat(out.mttrMinutes24h()).isNull();
        assertThat(out.firingTotal()).isEqualTo(1);
    }

    private void insertIncident(UUID id, String key, String status,
                                Instant episodeStartedAt, Instant resolvedAt) {
        Instant at = resolvedAt != null ? resolvedAt : episodeStartedAt;
        controlJdbc.sql("""
                        insert into incident (id, incident_key, status, episode_started_at,
                            resolved_at, received_count, distinct_event_count, notification_count,
                            first_seen_at, last_event_at, created_at, updated_at)
                        values (:id, :key, :status, :esa, :ra, 1, 1, 0, :esa, :at, :at, :at)
                        """)
                .param("id", id)
                .param("key", key)
                .param("status", status)
                .param("esa", java.sql.Timestamp.from(episodeStartedAt))
                .param("ra", resolvedAt == null ? null : java.sql.Timestamp.from(resolvedAt))
                .param("at", java.sql.Timestamp.from(at))
                .update();
    }
}
