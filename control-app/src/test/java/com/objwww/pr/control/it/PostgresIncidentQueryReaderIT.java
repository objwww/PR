package com.objwww.pr.control.it;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.objwww.pr.control.alert.domain.repository.IncidentQueryReader.IncidentPage;
import com.objwww.pr.control.alert.domain.repository.IncidentQueryReader.IncidentRow;
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

    // ------------------------------------------------------------------ UX-03（方案 §三.2）：负责人列 + 高级筛选

    @Test
    void listMapsOwnerFromLatestOpenCaseOnly() {
        Instant now = Instant.now();
        UUID withOwner = UUID.randomUUID();
        UUID noCase = UUID.randomUUID();
        insertIncident(withOwner, "k-owned", "FIRING", now.minus(Duration.ofMinutes(5)), null);
        insertIncident(noCase, "k-bare", "FIRING", now.minus(Duration.ofMinutes(4)), null);
        // 同 incident 多单：旧 OPEN + 新 ACKED + RESOLVED（RESOLVED 不进 owner 面）
        insertCase(withOwner, "older-op", "OPEN", now.minus(Duration.ofHours(2)));
        insertCase(withOwner, "newer-op", "ACKED", now.minus(Duration.ofHours(1)));
        insertCase(withOwner, "resolved-op", "RESOLVED", now);

        IncidentPage page = reader.listIncidents(null, null, null, null, null, null, null,
                null, null, 50);

        IncidentRow owned = page.items().stream()
                .filter(r -> r.incidentId().equals(withOwner)).findFirst().orElseThrow();
        IncidentRow bare = page.items().stream()
                .filter(r -> r.incidentId().equals(noCase)).findFirst().orElseThrow();
        assertThat(owned.owner()).as("created_at 最新 open case 的负责人").isEqualTo("newer-op");
        assertThat(bare.owner()).as("无 case → null（前端显未认领）").isNull();
    }

    @Test
    void listFiltersByLastEventWindowAndHasOwner() {
        Instant now = Instant.now();
        UUID inWindow = UUID.randomUUID();
        UUID oldOwned = UUID.randomUUID();
        insertIncident(inWindow, "k-recent", "FIRING", now.minus(Duration.ofHours(1)), null);
        insertIncident(oldOwned, "k-old", "FIRING", now.minus(Duration.ofDays(3)), null);
        insertCase(oldOwned, "op-old", "OPEN", now.minus(Duration.ofDays(2)));

        // 时间窗闭区间：只留近 24h
        IncidentPage window = reader.listIncidents(null, null, null, null, null,
                now.minus(Duration.ofHours(24)), now, null, null, 50);
        assertThat(window.items()).extracting(r -> r.incidentId())
                .containsExactly(inWindow);
        assertThat(window.total()).isEqualTo(1);

        // hasOwner=true 仅取有 open 负责人行；false 互补
        IncidentPage owned = reader.listIncidents(null, null, null, null, null, null, null,
                Boolean.TRUE, null, 50);
        assertThat(owned.items()).extracting(r -> r.incidentId())
                .containsExactly(oldOwned);
        IncidentPage unowned = reader.listIncidents(null, null, null, null, null, null, null,
                Boolean.FALSE, null, 50);
        assertThat(unowned.items()).extracting(r -> r.incidentId())
                .containsExactly(inWindow);

        // 组合：时间窗 + hasOwner 叠加（window 内无负责人行）
        IncidentPage combo = reader.listIncidents(null, null, null, null, null,
                now.minus(Duration.ofHours(24)), now, Boolean.TRUE, null, 50);
        assertThat(combo.items()).isEmpty();
        assertThat(combo.total()).isZero();
    }

    /** UX-03：直插 operator_case（control_app 面；V26 约束 evidence_refs N≥1 兜底） */
    private void insertCase(UUID incidentId, String owner, String status, Instant createdAt) {
        controlJdbc.sql("""
                        insert into operator_case (id, tenant, fingerprint, subject, priority,
                            reason_code, status, owner, incident_id, evidence_refs, created_at)
                        values (:id, 'tenant-1', :fp, 'IT 用例', 'P1', 'ALERT_FIRING', :status,
                            :owner, :incidentId, '["e1"]'::jsonb, :createdAt)
                        """)
                .param("id", UUID.randomUUID())
                .param("fp", "fp-" + UUID.randomUUID())
                .param("status", status)
                .param("owner", owner)
                .param("incidentId", incidentId)
                .param("createdAt", java.sql.Timestamp.from(createdAt))
                .update();
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
