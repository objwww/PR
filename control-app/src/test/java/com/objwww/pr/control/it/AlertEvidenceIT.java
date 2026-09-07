package com.objwww.pr.control.it;

import com.objwww.pr.control.alert.domain.evidence.EvidenceEnvelope;
import com.objwww.pr.control.alert.domain.evidence.EvidenceRepository;
import com.objwww.pr.control.infrastructure.persistence.PostgresEvidenceRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.objwww.pr.shared.Digest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * M4-19 真 PG 证据契约（V16 rca_evidence）：canonical bytes 原样回读（TEXT 列字节稳定）、
 * 单字节篡改可检出、同 schema_version 不同 generation 跨代拒绝、canonical 形数字归一
 * 往返稳定。
 */
class AlertEvidenceIT extends PostgresITBase {

    private PostgresEvidenceRepository repository;
    private UUID runId;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Override
    @BeforeEach
    void truncateAll() {
        adminJdbc.sql("""
                TRUNCATE pr_subject, pr_revision, review_run, run_step, work_item, step_attempt,
                    execution_event, outbox_command, outbox_dependency, publication_resource,
                    review_finding, artifact, webhook_inbox, step_checkpoint, repair_request,
                    model_call_ledger, tool_call, sandbox_job, artifact_grant,
                    alert_inbox, alert_event, incident, rca_run, rca_task, rca_attempt,
                    rca_report, external_invocation_ledger, rca_task_edge,
                    run_budget_state, run_budget_entry, incident_budget_entry, rca_event,
                    rca_tool_invocation, rca_evidence, rca_evidence_snapshot,
                    rca_snapshot_member
                RESTART IDENTITY CASCADE
                """).update();
        repository = new PostgresEvidenceRepository(controlJdbc, controlTx, MAPPER);
        runId = seedRun(0);
    }

    private EvidenceEnvelope envelope(Map<String, Object> payload, long generation) {
        return EvidenceEnvelope.create(UUID.randomUUID(), runId, null, "METRIC_QUERY",
                EvidenceEnvelope.SCHEMA_VERSION, generation, "prom.query@1.0.0",
                Map.of("labels", "job=api", "zone", 1),
                Instant.ofEpochSecond(100), Instant.ofEpochSecond(200), payload);
    }

    @Test
    void itV1_canonicalBytes原样回读_数字归一往返稳定() {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("zeta", 1.0); // canonical 归一为 1（jsonb 做不到的往返稳定性）
        payload.put("alpha", "up");
        EvidenceEnvelope env = envelope(payload, 0);
        repository.insert(env);

        EvidenceEnvelope read = repository.findById(env.evidenceId()).orElseThrow();
        assertThat(read.canonicalPayload())
                .isEqualTo("{\"alpha\":\"up\",\"zeta\":1}"); // 排序+归一原样回读
        assertThat(read.payloadDigest()).isEqualTo(env.payloadDigest());
        assertThat(Digest.sha256Of(read.canonicalPayload()).value())
                .isEqualTo(read.payloadDigest()); // 五步④：读出字节重算比对
        // scope canonical 字节往返稳定（1 → "1"）
        assertThat(read.scope()).containsEntry("zone", 1);
        assertThat(read.rowDigest()).isEqualTo(env.rowDigest());
    }

    @Test
    void itV2_单字节篡改可检出() {
        EvidenceEnvelope env = envelope(new LinkedHashMap<>(Map.of("value", "up")), 0);
        repository.insert(env);
        // 同长度单点篡改（up→do），digest 不改 → 读路径必须显式拒绝
        adminJdbc.sql("UPDATE rca_evidence SET payload = replace(payload, 'up', 'do')")
                .update();
        assertThatThrownBy(() -> repository.findById(env.evidenceId()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("EVIDENCE_TAMPERED");
        assertThatThrownBy(() -> repository.findByRunId(runId))
                .hasMessageContaining("EVIDENCE_TAMPERED");
    }

    @Test
    void itV3_同schemaVersion不同generation_跨代拒绝() {
        // run 当前代 = 0；代 1 的证据（同 schema_version）在准入面拒绝
        assertThatThrownBy(() -> repository.insert(envelope(Map.of("v", 1), 1)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("EVIDENCE_CROSS_GENERATION");
        assertThat(count("rca_evidence")).isZero();
        // 当前代放行
        EvidenceEnvelope current = envelope(Map.of("v", 1), 0);
        repository.insert(current);
        assertThat(repository.findById(current.evidenceId())).isPresent();
    }

    @Test
    void itV4_run缺行显式失败() {
        EvidenceEnvelope orphan = EvidenceEnvelope.create(UUID.randomUUID(), UUID.randomUUID(),
                null, "METRIC_QUERY", EvidenceEnvelope.SCHEMA_VERSION, 0, "prom.query@1.0.0",
                Map.of(), null, null, Map.of("v", 1));
        assertThatThrownBy(() -> repository.insert(orphan))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("run 不存在");
    }

    private UUID seedRun(long generation) {
        UUID incident = UUID.randomUUID();
        controlJdbc.sql("""
                INSERT INTO incident(id, incident_key, status, generation, episode_started_at,
                    first_seen_at, last_event_at, created_at, updated_at)
                VALUES (:id, :key, 'FIRING', 0, now(), now(), now(), now(), now())
                """).param("id", incident)
                .param("key", "alertname=HighErrorRate|service=edge-" + incident).update();
        UUID run = UUID.randomUUID();
        controlJdbc.sql("""
                INSERT INTO rca_run(id, incident_id, generation, trigger_kind, state,
                    investigation_hash, created_at, updated_at)
                VALUES (:id, :inc, :gen, 'INITIAL', 'QUEUED', :hash, now(), now())
                """).param("id", run).param("inc", incident).param("gen", generation)
                .param("hash", Digest.sha256Of("it-" + run).value()).update();
        return run;
    }
}
