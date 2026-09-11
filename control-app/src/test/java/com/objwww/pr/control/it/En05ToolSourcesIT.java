package com.objwww.pr.control.it;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.objwww.pr.control.alert.application.tool.ToolExecutor;
import com.objwww.pr.control.infrastructure.tool.AlertHistoryExecutor;
import com.objwww.pr.control.infrastructure.tool.ChangeDiffExecutor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * EN-05 真 PG 数据面（L1，195 窗真值；本机无 docker 自动跳过）：change_event_diff 与
 * alert_history_query 的<b>查询路径</b>（L0 只钉语义/形状，见各自 Test）。钉四件事：
 * <ul>
 *   <li>change.diff：窗内 ACTIVATE+ROLLBACK 行齐出（rollback_of 引用可辨）+ 窗前基线
 *       （T08：激活/回滚生效事实可查、时间顺序正确）；</li>
 *   <li>change.diff 空窗 → NO_DATA（模型可见，正常空结果非故障）；</li>
 *   <li>alert.history：firing→resolved 时间线按 starts_at/recorded_at 升序可回放；</li>
 *   <li>alert.history 空窗 → NO_DATA；两执行器只读（SELECT 模板零写副作用）。</li>
 * </ul>
 */
class En05ToolSourcesIT extends PostgresITBase {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Set<String> ALLOWLIST = Set.of("control-app");

    private JdbcClient jdbc;

    @BeforeEach
    void setUp() {
        jdbc = JdbcClient.create(controlDataSource());
    }

    @Test
    @DisplayName("change.diff：窗内 ACTIVATE/ROLLBACK 行 + 窗前基线（T08）")
    void changeDiffCarriesWindowRowsAndBaseline() throws Exception {
        Instant base = Instant.parse("2026-09-10T00:00:00Z");
        String rollbackOf = "e".repeat(64);
        seedChange("deploy-en05-0", "ACTIVATE", base.minusSeconds(3_600), null);
        seedChange("deploy-en05-1", "ACTIVATE", base, null);
        seedChange("deploy-en05-2", "ROLLBACK", base.plusSeconds(60), rollbackOf);

        byte[] body = new ChangeDiffExecutor(jdbc, ALLOWLIST).execute(exec(Map.of(
                "service", "control-app",
                "since", base.toString(),
                "until", base.plusSeconds(300).toString())));

        Map<?, ?> payload = JSON.readValue(body, Map.class);
        assertThat(payload.get("status")).isEqualTo("success");
        Map<?, ?> data = (Map<?, ?>) payload.get("data");
        assertThat(((Map<?, ?>) data.get("baseline")).get("deploy_id"))
                .isEqualTo("deploy-en05-0");
        var rows = (java.util.List<?>) data.get("result");
        assertThat(rows).hasSize(2);
        assertThat(String.valueOf(rows.get(1))).contains("ROLLBACK").contains(rollbackOf);
    }

    @Test
    @DisplayName("change.diff 空窗 → NO_DATA 模型可见（正常空结果非故障）")
    void changeDiffEmptyWindowIsNoData() {
        Instant base = Instant.parse("2026-09-10T00:00:00Z");
        assertThatThrownBy(() -> new ChangeDiffExecutor(jdbc, ALLOWLIST).execute(exec(Map.of(
                "service", "control-app",
                "since", base.plusSeconds(9_000).toString(),
                "until", base.plusSeconds(9_300).toString()))))
                .isInstanceOf(com.objwww.pr.control.alert.domain.tool.ToolModelVisibleException.class)
                .hasMessageContaining("NO_DATA");
    }

    @Test
    @DisplayName("alert.history：firing→resolved 时间线按 starts_at 升序可回放")
    void alertHistoryTimelineReplays() throws Exception {
        Instant base = Instant.parse("2026-09-10T00:00:00Z");
        String fingerprint = "fp-en05-" + UUID.randomUUID();
        UUID inboxId = seedInbox();
        UUID incidentId = seedIncident();
        seedAlertEvent(inboxId, incidentId, fingerprint, "firing", 0,
                base.minusSeconds(120), base.plusSeconds(60), base.minusSeconds(60));
        seedAlertEvent(inboxId, incidentId, fingerprint, "resolved", 0,
                base.minusSeconds(120), base, base.plusSeconds(30));

        byte[] body = new AlertHistoryExecutor(jdbc).execute(exec(Map.of(
                "fingerprint", fingerprint,
                "since", base.minusSeconds(3_600).toString(),
                "until", base.plusSeconds(3_600).toString())));

        var rows = JSON.readTree(body).path("data").path("result");
        assertThat(rows).hasSize(2);
        assertThat(rows.get(0).path("status").asText()).isEqualTo("firing");
        assertThat(rows.get(1).path("status").asText()).isEqualTo("resolved");
        assertThat(rows.get(1).path("ends_at").isNull()).isFalse();
    }

    @Test
    @DisplayName("alert.history 空窗 → NO_DATA；alertname 不匹配零行")
    void alertHistoryEmptyIsNoData() {
        Instant base = Instant.parse("2026-09-10T00:00:00Z");
        assertThatThrownBy(() -> new AlertHistoryExecutor(jdbc).execute(exec(Map.of(
                "alertname", "GhostAlert",
                "since", base.toString(),
                "until", base.plusSeconds(600).toString()))))
                .isInstanceOf(com.objwww.pr.control.alert.domain.tool.ToolModelVisibleException.class)
                .hasMessageContaining("NO_DATA");
    }

    // ------------------------------------------------------------------ 辅助

    private static ToolExecutor.ToolExecution exec(Map<String, Object> args) {
        return new ToolExecutor.ToolExecution(args,
                System.currentTimeMillis() + 10_000, 65_536);
    }

    /** admin 面 seed：change_event 行（V40 列面；rollback_of = char(64) 引用） */
    private void seedChange(String deployId, String action, Instant effectiveAt,
            String rollbackOf) {
        jdbc.sql("""
                INSERT INTO change_event (id, deploy_id, source, action, service,
                    environment, image_digest, config_digest, commit_sha, actor,
                    rollback_of, status, effective_at)
                VALUES (:id, :deployId, 'deployment', :action, 'control-app', 'prod',
                        repeat('a', 64), repeat('b', 64), repeat('c', 40), 'it',
                        :rollbackOf, 'SUCCEEDED', :effectiveAt)
                """)
                .param("id", UUID.randomUUID())
                .param("deployId", deployId)
                .param("action", action)
                .param("rollbackOf", rollbackOf)
                .param("effectiveAt", Timestamp.from(effectiveAt))
                .update();
    }

    /** admin 面 seed：alert_inbox 最小 NOT NULL 行（alert_event FK 依赖） */
    private UUID seedInbox() {
        UUID inboxId = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO alert_inbox (id, version, receiver, group_key, group_labels,
                    common_labels, common_annotations, group_status, payload_raw,
                    payload_digest, state, received_at, updated_at)
                VALUES (:id, '4', 'it', 'it-key', '{}' :: jsonb, '{}' :: jsonb,
                        '{}' :: jsonb, 'firing', 'raw' :: bytea, repeat('p', 64),
                        'PROCESSED', now(), now())
                """)
                .param("id", inboxId)
                .update();
        return inboxId;
    }

    /** admin 面 seed：incident 最小行（alert_event FK 依赖） */
    private UUID seedIncident() {
        UUID incidentId = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO incident (id, incident_key, status, generation,
                    episode_started_at, first_seen_at, last_event_at, created_at, updated_at)
                VALUES (:id, 'HighErrorRate|control-app', 'OPEN', 0,
                        now(), now(), now(), now(), now())
                """)
                .param("id", incidentId)
                .update();
        return incidentId;
    }

    /** admin 面 seed：alert_event 行（AM 原文小写状态，§6.3 双哈希列） */
    private void seedAlertEvent(UUID inboxId, UUID incidentId, String fingerprint,
            String status, int generation, Instant startsAt, Instant endsAt,
            Instant recordedAt) {
        jdbc.sql("""
                INSERT INTO alert_event (id, inbox_id, incident_id, generation, fingerprint,
                    status, labels, annotations, starts_at, ends_at, payload_hash,
                    investigation_hash, recorded_at)
                VALUES (:id, :inbox, :incident, :generation, :fp, :status,
                        '{"alertname":"HighErrorRate","service":"control-app"}' :: jsonb,
                        '{}' :: jsonb, :starts, :ends,
                        md5(random() :: text) || md5(random() :: text),
                        md5(random() :: text) || md5(random() :: text), :recorded)
                """)
                .param("id", UUID.randomUUID())
                .param("inbox", inboxId)
                .param("incident", incidentId)
                .param("generation", generation)
                .param("fp", fingerprint)
                .param("status", status)
                .param("starts", Timestamp.from(startsAt))
                .param("ends", endsAt == null ? null : Timestamp.from(endsAt))
                .param("recorded", Timestamp.from(recordedAt))
                .update();
    }
}
