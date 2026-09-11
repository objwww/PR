package com.objwww.pr.control.it;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.objwww.pr.control.alert.application.rag.RunbookCorpusPublisher;
import com.objwww.pr.control.alert.application.rag.RunbookCorpusStore;
import com.objwww.pr.control.alert.application.tool.ToolExecutor;
import com.objwww.pr.control.alert.domain.tool.ToolControlPlaneException;
import com.objwww.pr.control.alert.domain.tool.ToolModelVisibleException;
import com.objwww.pr.control.alert.domain.tool.ToolModelVisibleReason;
import com.objwww.pr.control.infrastructure.persistence.PostgresReleaseAssetRepository;
import com.objwww.pr.control.infrastructure.rag.HistoryRcaSearchExecutor;
import com.objwww.pr.shared.Digest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * EN-07 真 PG 数据面（L1，195 窗真值；本机无 docker 自动跳过）：固定语料走 release_asset
 * 真表（V60 授权面：control_app select,insert）往返 + 幂等零新行；history_rca_search
 * 走真 V7 三表 JOIN——service 结构化过滤先行（R10 越权零泄漏）、只取 STRUCTURE_VALIDATED
 * （历史判例准入面）、空窗如实 NO_DATA（R02）。
 */
class En07RagSourcesIT extends PostgresITBase {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    @DisplayName("语料发布→真 release_asset 往返；重发幂等零新行（R07 新快照的持久面）")
    void corpusRoundTripThroughRealPg() {
        PostgresReleaseAssetRepository assets =
                new PostgresReleaseAssetRepository(controlDataSource());
        RunbookCorpusPublisher publisher = new RunbookCorpusPublisher(assets);
        RunbookCorpusStore store = new RunbookCorpusStore(assets);

        Digest corpus = publisher.publish(List.of(
                        new RunbookCorpusPublisher.DocInput("kafka-consumer-lag",
                                "Kafka 消费积压处置", "分区与吞吐排查步骤", "1) 查分区 2) 查吞吐",
                                List.of("kafka"), null, null)),
                "en07-it", Instant.parse("2026-09-11T00:00:00Z"));

        RunbookCorpusStore.CorpusSnapshot snapshot = store.load(corpus);
        assertThat(snapshot.entries()).hasSize(1);
        assertThat(store.document(snapshot, snapshot.entries().get(0)).text())
                .contains("查分区");
        Digest again = publisher.publish(List.of(
                        new RunbookCorpusPublisher.DocInput("kafka-consumer-lag",
                                "Kafka 消费积压处置", "分区与吞吐排查步骤", "1) 查分区 2) 查吞吐",
                                List.of("kafka"), null, null)),
                "en07-it", Instant.parse("2026-09-11T00:00:00Z"));
        assertThat(again).isEqualTo(corpus);
        assertThat(assetsRows()).as("重发幂等：1 文档 + 1 目录").isEqualTo(2);
    }

    @Test
    @DisplayName("history_rca_search：service 先行过滤 + 只取 STRUCTURE_VALIDATED（R10/R06）")
    void historySearchFiltersByServiceAndValidation() {
        Instant firedAt = Instant.parse("2026-09-10T00:00:00Z");
        UUID orderIncident = seedIncident("alertname=HighLatency|service=order-service");
        UUID payIncident = seedIncident("alertname=HighLatency|service=payment-api");
        UUID orderRun = seedRcaChain(orderIncident);
        UUID payRun = seedRcaChain(payIncident);
        UUID orderAttempt = firstAttemptOf(orderRun);
        UUID payAttempt = firstAttemptOf(payRun);
        seedReport(orderRun, orderAttempt, "STRUCTURE_VALIDATED", "上次根因：下游超时",
                firedAt);
        seedReport(orderRun, orderAttempt, "REJECTED_MALFORMED", "坏结构不入判例",
                firedAt);
        seedReport(payRun, payAttempt, "STRUCTURE_VALIDATED", "越权服务不得泄漏",
                firedAt);

        HistoryRcaSearchExecutor executor =
                new HistoryRcaSearchExecutor(controlJdbc, Set.of("order-service"));

        JsonNode body = read(executor.execute(new ToolExecutor.ToolExecution(
                Map.of("service", "order-service",
                        "since", "2026-09-09T00:00:00Z",
                        "until", "2026-09-11T00:00:00Z"),
                Long.MAX_VALUE, 65_536)));

        assertThat(body.path("status").asText()).isEqualTo("success");
        assertThat(body.path("data").path("result")).hasSize(1);
        assertThat(body.path("data").path("result").get(0).path("prior_summary").asText())
                .isEqualTo("上次根因：下游超时");
        assertThat(body.path("data").path("result").get(0).path("incident_key").asText())
                .contains("order-service");
        assertThat(body.path("data").path("note").asText())
                .as("R06：历史参考标记与反证纪律")
                .contains("参考").contains("根因");

        assertThatThrownBy(() -> executor.execute(new ToolExecutor.ToolExecution(
                Map.of("service", "payment-api",
                        "since", "2026-09-09T00:00:00Z",
                        "until", "2026-09-11T00:00:00Z"),
                Long.MAX_VALUE, 65_536)))
                .as("R10：越权 service 发出前拒")
                .isInstanceOf(ToolControlPlaneException.class);

        assertThatThrownBy(() -> executor.execute(new ToolExecutor.ToolExecution(
                Map.of("service", "order-service",
                        "since", "2026-01-01T00:00:00Z",
                        "until", "2026-01-02T00:00:00Z"),
                Long.MAX_VALUE, 65_536)))
                .as("R02：空窗如实 NO_DATA")
                .isInstanceOf(ToolModelVisibleException.class)
                .hasFieldOrPropertyWithValue("reason", ToolModelVisibleReason.NO_DATA);
    }

    // ------------------------------------------------------------------ 种子与辅助

    private int assetsRows() {
        return controlJdbc.sql("SELECT count(*) AS n FROM release_asset")
                .query((rs, i) -> rs.getInt("n")).single();
    }

    private UUID seedIncident(String incidentKey) {
        UUID id = UUID.randomUUID();
        controlJdbc.sql("""
                INSERT INTO incident (id, incident_key, status, generation,
                    episode_started_at, first_seen_at, last_event_at, created_at, updated_at)
                VALUES (:id, :key, 'FIRING', 0,
                    :at, :at, :at, :at, :at)
                """)
                .param("id", id).param("key", incidentKey)
                .param("at", java.sql.Timestamp.from(Instant.parse("2026-09-10T00:00:00Z")))
                .update();
        return id;
    }

    private UUID seedRcaChain(UUID incidentId) {
        UUID runId = UUID.randomUUID();
        controlJdbc.sql("""
                INSERT INTO rca_run (id, incident_id, generation, state,
                    investigation_hash, finished_at, created_at, updated_at)
                VALUES (:id, :incident, 0, 'SUCCEEDED',
                    :hash, :at, :at, :at)
                """)
                .param("id", runId).param("incident", incidentId)
                .param("hash", "ab".repeat(32))
                .param("at", java.sql.Timestamp.from(Instant.parse("2026-09-10T00:00:00Z")))
                .update();
        UUID taskId = UUID.randomUUID();
        controlJdbc.sql("""
                INSERT INTO rca_task (id, run_id, task_key, state,
                    available_at, ready_since, deadline_at, created_at, updated_at)
                VALUES (:id, :run, 'HOLMES_INVESTIGATE', 'DONE',
                    :at, :at, :at, :at, :at)
                """)
                .param("id", taskId).param("run", runId)
                .param("at", java.sql.Timestamp.from(Instant.parse("2026-09-10T00:00:00Z")))
                .update();
        controlJdbc.sql("""
                INSERT INTO rca_attempt (id, task_id, attempt_no, lease_epoch, worker_id,
                    status, started_at, finished_at)
                VALUES (:id, :task, 1, 0, 'en07-it', 'SUCCEEDED', :at, :at)
                """)
                .param("id", UUID.randomUUID()).param("task", taskId)
                .param("at", java.sql.Timestamp.from(Instant.parse("2026-09-10T00:00:00Z")))
                .update();
        return runId;
    }

    private UUID firstAttemptOf(UUID runId) {
        return controlJdbc.sql("""
                SELECT a.id FROM rca_attempt a
                  JOIN rca_task t ON t.id = a.task_id
                 WHERE t.run_id = :run
                """).param("run", runId)
                .query((rs, i) -> rs.getObject("id", java.util.UUID.class)).single();
    }

    private void seedReport(UUID runId, UUID attemptId, String validationStatus,
            String summary, Instant createdAt) {
        controlJdbc.sql("""
                INSERT INTO rca_report (id, run_id, attempt_id, schema_version,
                    validation_status, package_json, raw_text, created_at)
                VALUES (:id, :run, :attempt, 2, :status,
                    CAST(:pkg AS jsonb), :raw, :at)
                """)
                .param("id", UUID.randomUUID()).param("run", runId)
                .param("attempt", attemptId).param("status", validationStatus)
                .param("pkg", "{\"summary\":\"" + summary + "\"}")
                .param("raw", "raw-" + summary)
                .param("at", java.sql.Timestamp.from(createdAt))
                .update();
    }

    private static JsonNode read(byte[] body) {
        try {
            return MAPPER.readTree(new String(body, StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
