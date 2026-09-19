package com.objwww.pr.control.it;

import com.objwww.pr.control.drill.application.DrillCorrelationPort;
import com.objwww.pr.control.infrastructure.persistence.PostgresDrillCorrelationReader;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * BA-180 真 PG 钉板：DR-06 演练关联口径新沿化（first_seen_at → episode_started_at）。
 * 同 incident_key 复燃的新 episode（first_seen 停在历史首见）必须命中——旧口径下
 * 这类关联恒 null、演练 outcome 恒 FAIL 失真；残留 firing（episode 起点早于注入
 * 窗）不命中（"新沿 vs 残留"区分）；首见新 episode 行为与旧口径一致。
 */
class PostgresDrillCorrelationReaderIT extends PostgresITBase {

    private static final String KEY =
            "alertname=ArenaPaymentOrderMismatch|service=order-arena|job=order-arena";
    private static final Instant INJECTED = Instant.parse("2026-09-19T07:17:24Z");
    private static final Instant WINDOW_END = INJECTED.plusSeconds(300);

    private PostgresDrillCorrelationReader reader;

    @BeforeEach
    void setUp() {
        reader = new PostgresDrillCorrelationReader(evalJdbc);
    }

    @Test
    @DisplayName("复燃新 episode（first_seen 历史、episode 起点在窗内）命中并携带 currentRcaRunId")
    void revivedEpisodeMatches() {
        UUID incident = insertIncident(KEY, "2026-09-16T21:14:34Z",
                "2026-09-19T07:18:04Z");
        UUID run = insertRun(incident);
        adminJdbc.sql("UPDATE incident SET current_rca_run_id=:run WHERE id=:id")
                .param("run", run).param("id", incident).update();

        Optional<DrillCorrelationPort.Correlation> hit =
                reader.correlate("ArenaPaymentOrderMismatch", INJECTED, WINDOW_END);

        assertThat(hit).isPresent();
        assertThat(hit.get().incidentId()).isEqualTo(incident);
        assertThat(hit.get().currentRcaRunId()).isEqualTo(run);
    }

    @Test
    @DisplayName("首见新 episode（first_seen=episode 起点同在窗内）命中——旧口径行为保持")
    void firstSeenEpisodeMatches() {
        UUID incident = insertIncident(KEY, "2026-09-19T07:18:04Z",
                "2026-09-19T07:18:04Z");

        Optional<DrillCorrelationPort.Correlation> hit =
                reader.correlate("ArenaPaymentOrderMismatch", INJECTED, WINDOW_END);

        assertThat(hit).isPresent();
        assertThat(hit.get().incidentId()).isEqualTo(incident);
        assertThat(hit.get().currentRcaRunId()).isNull();
    }

    @Test
    @DisplayName("残留 firing（episode 起点早于注入窗）不命中——新沿与残留区分")
    void residualFiringDoesNotMatch() {
        insertIncident(KEY, "2026-09-19T07:10:00Z", "2026-09-19T07:10:00Z");

        Optional<DrillCorrelationPort.Correlation> hit =
                reader.correlate("ArenaPaymentOrderMismatch", INJECTED, WINDOW_END);

        assertThat(hit).isEmpty();
    }

    @Test
    @DisplayName("异 alertname 不命中（incident_key 首段精确等值，不子串模糊）")
    void foreignAlertnameDoesNotMatch() {
        insertIncident("alertname=ArenaOrderStuck|service=order-arena|job=order-arena",
                "2026-09-19T07:18:04Z", "2026-09-19T07:18:04Z");

        Optional<DrillCorrelationPort.Correlation> hit =
                reader.correlate("ArenaPaymentOrderMismatch", INJECTED, WINDOW_END);

        assertThat(hit).isEmpty();
    }

    private UUID insertIncident(String key, String firstSeen, String episodeStarted) {
        UUID id = UUID.randomUUID();
        adminJdbc.sql("""
                INSERT INTO incident(id, incident_key, status, generation, episode_started_at,
                    first_seen_at, last_event_at, created_at, updated_at)
                VALUES (:id, :key, 'FIRING', 1, :ep, :first, now(), now(), now())
                """)
                .param("id", id).param("key", key)
                .param("ep", Timestamp.from(Instant.parse(episodeStarted)))
                .param("first", Timestamp.from(Instant.parse(firstSeen)))
                .update();
        return id;
    }

    private UUID insertRun(UUID incidentId) {
        UUID runId = UUID.randomUUID();
        adminJdbc.sql("""
                INSERT INTO rca_run(id, incident_id, generation, trigger_kind, state,
                    investigation_hash, created_at, updated_at)
                VALUES (:id, :inc, 1, 'INITIAL', 'QUEUED', :hash, now(), now())
                """)
                .param("id", runId).param("inc", incidentId)
                .param("hash", "a".repeat(64)).update();
        return runId;
    }
}
