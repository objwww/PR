package com.objwww.pr.control.it;

import com.objwww.pr.control.alert.domain.evidence.EvidenceEnvelope;
import com.objwww.pr.control.alert.domain.evidence.EvidenceRepository;
import com.objwww.pr.control.alert.domain.evidence.EvidenceSnapshotBuilder;
import com.objwww.pr.control.alert.domain.evidence.EvidenceSnapshotRepository;
import com.objwww.pr.control.infrastructure.persistence.PostgresEvidenceRepository;
import com.objwww.pr.control.infrastructure.persistence.PostgresEvidenceSnapshotRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.objwww.pr.shared.Digest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * M4-20 真 PG 证据快照（V16）：同事实同 digest（成员顺序/证据 id 无关）、重复冻结幂等、
 * 冻结不可变（迟到证据不改旧快照——成员表零更新路径）、parent 链。
 */
class AlertEvidenceSnapshotIT extends PostgresITBase {

    private PostgresEvidenceRepository evidence;
    private PostgresEvidenceSnapshotRepository snapshots;
    private UUID runId;

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String CONFIG = "cfg-" + "c".repeat(8);
    private static final String TOOLS = "tools-" + "t".repeat(8);

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
        evidence = new PostgresEvidenceRepository(controlJdbc, controlTx, MAPPER);
        snapshots = new PostgresEvidenceSnapshotRepository(controlJdbc, controlTx);
        runId = seedRun();
    }

    @Test
    void itS1_同事实同digest_重复冻结幂等() {
        EvidenceEnvelope a = insertEvidence("latency-high");
        EvidenceEnvelope b = insertEvidence("error-rate-5xx");
        EvidenceSnapshotBuilder.SnapshotInput input = input(
                member(a), member(b));
        String digest = EvidenceSnapshotBuilder.digest(input).hex();

        EvidenceSnapshotRepository.FrozenSnapshot snapshot = new EvidenceSnapshotRepository.FrozenSnapshot(
                UUID.randomUUID(), runId, digest, 0, CONFIG, TOOLS, null);
        assertThat(snapshots.freeze(snapshot, List.of(
                new EvidenceSnapshotRepository.SnapshotMemberRow(a.evidenceId(),
                        a.evidenceType(), a.payloadDigest()),
                new EvidenceSnapshotRepository.SnapshotMemberRow(b.evidenceId(),
                        b.evidenceType(), b.payloadDigest())))).isTrue();

        // 重复冻结（同事实重新摄取→新证据 id、同 digest 语义由 builder 保证；此处直接重放同输入）
        assertThat(snapshots.freeze(new EvidenceSnapshotRepository.FrozenSnapshot(
                UUID.randomUUID(), runId, digest, 0, CONFIG, TOOLS, null), List.of()))
                .isFalse(); // 幂等：不重复落行
        assertThat(count("rca_evidence_snapshot")).isEqualTo(1);
        assertThat(snapshots.find(runId, digest)).isPresent();
    }

    @Test
    void itS2_迟到证据不改旧快照_成员表零更新路径() {
        EvidenceEnvelope first = insertEvidence("metric-1");
        String digest = EvidenceSnapshotBuilder.digest(input(member(first))).hex();
        snapshots.freeze(new EvidenceSnapshotRepository.FrozenSnapshot(
                UUID.randomUUID(), runId, digest, 0, CONFIG, TOOLS, null),
                List.of(new EvidenceSnapshotRepository.SnapshotMemberRow(first.evidenceId(),
                        first.evidenceType(), first.payloadDigest())));
        // 快照冻结后才到的证据
        EvidenceEnvelope late = insertEvidence("late-arriving");
        EvidenceSnapshotRepository.FrozenSnapshot frozen =
                snapshots.find(runId, digest).orElseThrow();
        assertThat(snapshots.membersOf(frozen.snapshotId())).hasSize(1); // 迟到者不在旧快照
        assertThat(frozen.snapshotDigest()).isEqualTo(digest); // 旧快照摘要不变
        assertThat(late.evidenceId()).isNotNull();
    }

    @Test
    void itS3_parent链_相邻快照可追溯() {
        EvidenceEnvelope e1 = insertEvidence("gen0-fact");
        String d1 = EvidenceSnapshotBuilder.digest(input(member(e1))).hex();
        snapshots.freeze(new EvidenceSnapshotRepository.FrozenSnapshot(
                UUID.randomUUID(), runId, d1, 0, CONFIG, TOOLS, null),
                List.of(new EvidenceSnapshotRepository.SnapshotMemberRow(e1.evidenceId(),
                        e1.evidenceType(), e1.payloadDigest())));
        // 新快照（config 变化 → digest 必变）以旧快照为 parent
        EvidenceEnvelope e2 = insertEvidence("gen0-fact-2");
        EvidenceSnapshotBuilder.SnapshotInput input2 = new EvidenceSnapshotBuilder.SnapshotInput(
                0, "cfg-changed" + "c".repeat(4), TOOLS, List.of(member(e2)));
        String d2 = EvidenceSnapshotBuilder.digest(input2).hex();
        assertThat(d2).isNotEqualTo(d1);
        snapshots.freeze(new EvidenceSnapshotRepository.FrozenSnapshot(
                UUID.randomUUID(), runId, d2, 0, input2.configDigest(), TOOLS, d1),
                List.of(new EvidenceSnapshotRepository.SnapshotMemberRow(e2.evidenceId(),
                        e2.evidenceType(), e2.payloadDigest())));
        assertThat(snapshots.find(runId, d2).orElseThrow().parentSnapshotDigest()).isEqualTo(d1);
    }

    // ------------------------------------------------------------------ 夹具

    private EvidenceSnapshotBuilder.Member member(EvidenceEnvelope envelope) {
        return new EvidenceSnapshotBuilder.Member(envelope.evidenceType(),
                envelope.payloadDigest());
    }

    private EvidenceSnapshotBuilder.SnapshotInput input(
            EvidenceSnapshotBuilder.Member... members) {
        return new EvidenceSnapshotBuilder.SnapshotInput(0, CONFIG, TOOLS, List.of(members));
    }

    private EvidenceEnvelope insertEvidence(String marker) {
        EvidenceEnvelope env = EvidenceEnvelope.create(UUID.randomUUID(), runId, null,
                "METRIC_QUERY", EvidenceEnvelope.SCHEMA_VERSION, 0, "prom.query@1.0.0",
                Map.of("marker", marker), null, null, Map.of("marker", marker));
        evidence.insert(env);
        return env;
    }

    private UUID seedRun() {
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
                VALUES (:id, :inc, 0, 'INITIAL', 'QUEUED', :hash, now(), now())
                """).param("id", run).param("inc", incident)
                .param("hash", Digest.sha256Of("it-" + run).value()).update();
        return run;
    }
}
