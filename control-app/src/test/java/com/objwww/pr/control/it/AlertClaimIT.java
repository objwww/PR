package com.objwww.pr.control.it;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.objwww.pr.control.alert.domain.claim.ClaimIdentity;
import com.objwww.pr.control.alert.domain.claim.ClaimLifecycle;
import com.objwww.pr.control.alert.domain.claim.ClaimProjection;
import com.objwww.pr.control.alert.domain.claim.ClaimStatus;
import com.objwww.pr.control.alert.domain.claim.ClaimStore;
import com.objwww.pr.control.alert.domain.claim.ClaimVerdict;
import com.objwww.pr.control.alert.domain.claim.EvidenceBasis;
import com.objwww.pr.control.infrastructure.persistence.PostgresClaimStore;
import com.objwww.pr.shared.Digest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * M4-21/22 真 PG 断言投影（V17）：四分支矩阵落库语义 + 并发首插/CAS 修订 +
 * 历史不可变（SUPERSEDED 只动 lifecycle，内容列零改写；修订前内容活在事件账本）+
 * 不建空 Claim（CLAIM_UNRESOLVED 只进事件账本）。判定语义的穷举 UT 在
 * ClaimProjectionTest / ClaimReducerTest；本类证 SQL 执行与并发面。
 */
class AlertClaimIT extends PostgresITBase {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private PostgresClaimStore store;
    private UUID runId;

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
                    rca_snapshot_member, rca_claim
                RESTART IDENTITY CASCADE
                """).update();
        store = new PostgresClaimStore(controlJdbc, controlTx, MAPPER, eventAppender());
        runId = seedRun();
    }

    @Test
    void itC1_CREATED_落行且判定事件同事务落账() {
        ClaimVerdict v1 = verdict(0, ClaimStatus.TRUE, "r-v1", "policy-v1");

        ClaimStore.ClaimAppendResult r1 = store.append(runId, v1);

        assertThat(r1.outcome()).isEqualTo(ClaimProjection.Outcome.CREATED);
        assertThat(r1.supersededCount()).isZero();
        List<ClaimStore.ClaimRow> rows = store.findByRunId(runId);
        assertThat(rows).hasSize(1);
        ClaimStore.ClaimRow row = rows.get(0);
        assertThat(row.fingerprint()).isEqualTo(v1.fingerprint());
        assertThat(row.claimHash()).isEqualTo(v1.contentHash());
        assertThat(row.status()).isEqualTo(ClaimStatus.TRUE);
        assertThat(row.evidenceBasis()).isEqualTo(EvidenceBasis.MULTI_SOURCE_CONSISTENT);
        assertThat(row.lifecycle()).isEqualTo(ClaimLifecycle.ACTIVE);
        assertThat(row.sources()).containsExactly("logs-agent", "metrics-agent");
        assertThat(row.evidenceRefs()).containsExactly("ev-1", "ev-2");
        assertThat(eventTypes()).containsExactly("CLAIM_CREATED");
    }

    @Test
    void itC2_UNCHANGED_幂等_行与事件都不重复() {
        ClaimVerdict v1 = verdict(0, ClaimStatus.TRUE, "r-v1", "policy-v1");
        store.append(runId, v1);

        ClaimStore.ClaimAppendResult r2 = store.append(runId, v1);
        ClaimStore.ClaimAppendResult r3 = store.append(runId, v1);

        assertThat(r2.outcome()).isEqualTo(ClaimProjection.Outcome.UNCHANGED);
        assertThat(r3.outcome()).isEqualTo(ClaimProjection.Outcome.UNCHANGED);
        assertThat(store.findByRunId(runId)).hasSize(1);
        // 同 event_id 同 digest 重放 → 事件账本幂等：UNCHANGED 事件只落一行
        assertThat(eventTypes())
                .containsExactly("CLAIM_CREATED", "CLAIM_UNCHANGED");
        assertThat(eventCount("CLAIM_UNCHANGED")).isEqualTo(1);
    }

    @Test
    void itC3_REVISED_CAS更新当前投影_旧内容活在事件账本() {
        ClaimVerdict v1 = verdict(0, ClaimStatus.TRUE, "r-v1", "policy-v1");
        ClaimStore.ClaimAppendResult created = store.append(runId, v1);
        ClaimVerdict v2 = verdict(0, ClaimStatus.FALSE, "r-v2", "policy-v1");

        ClaimStore.ClaimAppendResult r2 = store.append(runId, v2);

        assertThat(r2.outcome()).isEqualTo(ClaimProjection.Outcome.REVISED);
        assertThat(r2.priorHash()).isEqualTo(v1.contentHash());
        List<ClaimStore.ClaimRow> rows = store.findByRunId(runId);
        assertThat(rows).hasSize(1); // 当前投影被更新，不是新行
        ClaimStore.ClaimRow row = rows.get(0);
        assertThat(row.claimHash()).isEqualTo(v2.contentHash());
        assertThat(row.status()).isEqualTo(ClaimStatus.FALSE);
        assertThat(row.lifecycle()).isEqualTo(ClaimLifecycle.ACTIVE);
        // 历史不可变落库面：修订前内容在 CLAIM_REVISED 事件载荷里
        String payload = eventPayloadContaining("CLAIM_REVISED");
        assertThat(payload).contains(v1.contentHash()).contains("r-v1");
        assertThat(payload).contains(v2.contentHash()).contains("r-v2");
        assertThat(created.eventSeq()).isPositive();
    }

    @Test
    void itC4_新代落新行_旧记录只标SUPERSEDED内容零改写() {
        ClaimVerdict gen0 = verdict(0, ClaimStatus.TRUE, "r-gen0", "policy-v1");
        ClaimStore.ClaimAppendResult first = store.append(runId, gen0);
        ClaimVerdict gen1 = new ClaimVerdict("latency-high", "scope", "2026-09-05/1h", 1L, null,
                ClaimStatus.FALSE, EvidenceBasis.MULTI_SOURCE_CONSISTENT,
                List.of("metrics-agent"), "r-gen1", List.of("ev-1", "ev-2"), "policy-v1");

        ClaimStore.ClaimAppendResult second = store.append(runId, gen1);

        assertThat(second.outcome()).isEqualTo(ClaimProjection.Outcome.CREATED);
        assertThat(second.supersededCount()).isEqualTo(1);
        List<ClaimStore.ClaimRow> rows = store.findByRunId(runId);
        assertThat(rows).hasSize(2);
        ClaimStore.ClaimRow oldRow = rows.stream()
                .filter(r -> r.fingerprint().equals(gen0.fingerprint())).findFirst().orElseThrow();
        // 历史不可变：内容列与首写完全一致，只有 lifecycle 变了
        assertThat(oldRow.claimHash()).isEqualTo(gen0.contentHash());
        assertThat(oldRow.status()).isEqualTo(ClaimStatus.TRUE);
        assertThat(oldRow.reason()).isEqualTo("r-gen0");
        assertThat(oldRow.claimHash()).isEqualTo(first.claimHash());
        assertThat(oldRow.lifecycle()).isEqualTo(ClaimLifecycle.SUPERSEDED);
        ClaimStore.ClaimRow newRow = rows.stream()
                .filter(r -> r.fingerprint().equals(gen1.fingerprint())).findFirst().orElseThrow();
        assertThat(newRow.lifecycle()).isEqualTo(ClaimLifecycle.ACTIVE);
        // CLAIM_CREATED 载荷记录被取代者（审计）
        assertThat(eventPayloadContaining("CLAIM_CREATED"))
                .contains(gen0.fingerprint());
    }

    @Test
    void itC5_不同scope同键_不同事实互不取代() {
        ClaimStore.ClaimAppendResult a = store.append(runId, verdict(0, ClaimStatus.TRUE, "r-a", "policy-v1"));
        ClaimVerdict otherScope = new ClaimVerdict("latency-high", "other-scope", "2026-09-05/1h",
                1L, null, ClaimStatus.FALSE, EvidenceBasis.SINGLE_SOURCE,
                List.of("metrics-agent"), "r-b", List.of("ev-9"), "policy-v1");
        ClaimStore.ClaimAppendResult b = store.append(runId, otherScope);

        assertThat(b.outcome()).isEqualTo(ClaimProjection.Outcome.CREATED);
        assertThat(b.supersededCount()).isZero();
        assertThat(store.findByRunId(runId))
                .allMatch(r -> r.lifecycle() == ClaimLifecycle.ACTIVE);
        assertThat(a.supersededCount()).isZero();
    }

    @Test
    void itC6_并发同fingerprint首插_恰一行全员收敛() throws Exception {
        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            ClaimVerdict v = verdict(0, ClaimStatus.TRUE, "r-v1", "policy-v1");
            List<Callable<ClaimStore.ClaimAppendResult>> tasks = new java.util.ArrayList<>();
            for (int i = 0; i < threads; i++) {
                tasks.add(() -> store.append(runId, v));
            }
            List<Future<ClaimStore.ClaimAppendResult>> futures = pool.invokeAll(tasks);
            List<ClaimProjection.Outcome> outcomes = futures.stream()
                    .map(f -> {
                        try {
                            return f.get(10, TimeUnit.SECONDS).outcome();
                        } catch (Exception e) {
                            throw new IllegalStateException(e);
                        }
                    })
                    .toList();
            assertThat(outcomes).hasSize(threads);
            assertThat(outcomes).allMatch(o -> o == ClaimProjection.Outcome.CREATED
                    || o == ClaimProjection.Outcome.UNCHANGED);
            assertThat(store.findByRunId(runId)).hasSize(1);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void itC7_并发修订CAS_败者重读重判最终收敛一致() throws Exception {
        ClaimVerdict v1 = verdict(0, ClaimStatus.TRUE, "r-v1", "policy-v1");
        store.append(runId, v1);
        ClaimVerdict va = verdict(0, ClaimStatus.FALSE, "r-a", "policy-v1");
        ClaimVerdict vb = new ClaimVerdict("latency-high", "scope", "2026-09-05/1h", 0L, null,
                ClaimStatus.FALSE, EvidenceBasis.SINGLE_SOURCE,
                List.of("metrics-agent"), "r-b", List.of("ev-1", "ev-2"), "policy-v1");
        assertThat(va.fingerprint()).isEqualTo(vb.fingerprint()); // 同身份不同内容

        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            List<Future<ClaimStore.ClaimAppendResult>> futures = pool.invokeAll(List.of(
                    () -> store.append(runId, va),
                    () -> store.append(runId, vb)));
            for (Future<ClaimStore.ClaimAppendResult> f : futures) {
                assertThat(f.get(10, TimeUnit.SECONDS).outcome())
                        .isIn(ClaimProjection.Outcome.REVISED, ClaimProjection.Outcome.UNCHANGED);
            }
        } finally {
            pool.shutdownNow();
        }
        // 收敛不变式：行内容自洽（hash 与行上内容列重算一致），仍是单行
        List<ClaimStore.ClaimRow> rows = store.findByRunId(runId);
        assertThat(rows).hasSize(1);
        ClaimStore.ClaimRow row = rows.get(0);
        String recomputed = new ClaimVerdict(row.claimKey(), row.scope(), row.timeRange(),
                row.observedGeneration(), row.snapshotDigest(), row.status(),
                row.evidenceBasis(), row.sources(), row.reason(), row.evidenceRefs(),
                row.policyVersion()).contentHash();
        assertThat(row.claimHash()).isEqualTo(recomputed);
        // 恰一次或两次 CAS 生效（败者重读后若内容仍异则基于最新内容再修订，均收敛）
        long revisedEvents = eventCount("CLAIM_REVISED");
        assertThat(revisedEvents).isBetween(1L, 2L);
    }

    @Test
    void itC8_无结论不建空Claim_只落幂等UNRESOLVED事件() {
        ClaimIdentity identity = new ClaimIdentity("dns-resolution", "scope", "2026-09-05/1h",
                0L, null);

        long seq1 = store.markUnresolved(runId, identity, "policy-v1");
        long seq2 = store.markUnresolved(runId, identity, "policy-v1");

        assertThat(seq2).isEqualTo(seq1); // 幂等重放返回既有 seq
        assertThat(store.findByRunId(runId)).isEmpty(); // 不建空 Claim
        assertThat(eventCount("CLAIM_UNRESOLVED")).isEqualTo(1);
    }

    // ------------------------------------------------------------------ 夹具

    private ClaimVerdict verdict(long generation, ClaimStatus status, String reason,
            String policyVersion) {
        // 双源一致（MULTI_SOURCE_CONSISTENT）的稳定 verdict；身份 = latency-high/scope/1h/gen
        return new ClaimVerdict("latency-high", "scope", "2026-09-05/1h", generation, null,
                status, EvidenceBasis.MULTI_SOURCE_CONSISTENT,
                List.of("logs-agent", "metrics-agent"), reason, List.of("ev-1", "ev-2"),
                policyVersion);
    }

    private long eventCount(String eventType) {
        return adminJdbc.sql("SELECT count(*) FROM rca_event WHERE event_type = :type")
                .param("type", eventType).query(Long.class).single();
    }

    private List<String> eventTypes() {
        return controlJdbc.sql(
                        "select event_type from rca_event where run_id = :run order by seq")
                .param("run", runId)
                .query((rs, n) -> rs.getString("event_type"))
                .list();
    }

    private String eventPayloadContaining(String eventType) {
        return controlJdbc.sql(
                        "select payload from rca_event where run_id = :run and event_type = :type")
                .param("run", runId).param("type", eventType)
                .query((rs, n) -> rs.getString("payload"))
                .list()
                .stream().collect(Collectors.joining("|"));
    }

    private com.objwww.pr.control.alert.domain.event.RcaEventAppender eventAppender() {
        return new com.objwww.pr.control.infrastructure.persistence.PostgresRcaEventAppender(
                controlJdbc, controlTx, independentTx());
    }

    private org.springframework.transaction.support.TransactionOperations independentTx() {
        org.springframework.transaction.support.TransactionTemplate template =
                new org.springframework.transaction.support.TransactionTemplate(
                        new org.springframework.jdbc.datasource.DataSourceTransactionManager(
                                controlDataSource()));
        template.setPropagationBehavior(
                org.springframework.transaction.TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        return template;
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
