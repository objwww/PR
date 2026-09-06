package com.objwww.pr.control.it;

import com.objwww.pr.control.alert.domain.budget.BudgetProbe;
import com.objwww.pr.control.alert.domain.budget.IncidentBudgetLedger;
import com.objwww.pr.control.infrastructure.persistence.PostgresIncidentBudgetLedger;
import com.objwww.pr.shared.Digest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * M4-09 真 PG Incident 窗口预算（V13 incident_budget_entry）：跨 Run 累计、24h/7d
 * 窗口独立滚动、耗尽不派生（拒绝不落账）、存储不可用 fail-closed、同 incident 并发
 * admission 恰好放行一个（incident 行锁串行化）。
 */
class AlertIncidentBudgetIT extends PostgresITBase {

    private PostgresIncidentBudgetLedger ledger;

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
                    run_budget_state, run_budget_entry, incident_budget_entry
                RESTART IDENTITY CASCADE
                """).update();
        ledger = new PostgresIncidentBudgetLedger(controlJdbc, controlTx);
    }

    @Test
    void itC01_crossRunAccumulationAndExhaustionDoesNotDerive() {
        UUID incident = seedIncident();
        BudgetProbe first = ledger.admit(incident, UUID.randomUUID(), 6, 10, 20);
        assertThat(first.allowed()).isTrue();
        assertThat(first.consumed()).isZero();
        assertThat(first.remaining()).isEqualTo(10); // min(10-0, 20-0)

        // 第二个 run：24h 窗 6+6=12 > 10 → 拒，且拒绝不落账（耗尽不派生）
        BudgetProbe second = ledger.admit(incident, UUID.randomUUID(), 6, 10, 20);
        assertThat(second.allowed()).isFalse();
        assertThat(second.consumed()).isEqualTo(6);
        assertThat(second.remaining()).isEqualTo(4); // min(10-6, 20-6)
        assertThat(count("incident_budget_entry")).isEqualTo(2); // 仅首笔的双窗口行

        // 别的事故独立账本，不受累
        assertThat(ledger.admit(seedIncident(), UUID.randomUUID(), 6, 10, 20).allowed()).isTrue();
    }

    @Test
    void itC02_windowsRollIndependently() {
        UUID incident = seedIncident();
        assertThat(ledger.admit(incident, UUID.randomUUID(), 5, 5, 10).allowed()).isTrue();
        // 24h 窗打满：第二笔被 24h 拒
        assertThat(ledger.admit(incident, UUID.randomUUID(), 5, 5, 10).allowed()).isFalse();

        // 窗口滚动：把已有 entry 拨回 25h 前 → 移出 24h 窗、仍在 7d 窗
        adminJdbc.sql("UPDATE incident_budget_entry SET created_at = created_at - interval '25 hours'")
                .update();
        assertThat(ledger.admit(incident, UUID.randomUUID(), 5, 5, 10).allowed()).isTrue();

        // 7d 窗现已满（5+5=10）：再一笔被 7d 拒——证明 7d 仍在累计
        assertThat(ledger.admit(incident, UUID.randomUUID(), 1, 5, 10).allowed()).isFalse();
        assertThat(count("incident_budget_entry")).isEqualTo(4);
    }

    @Test
    void itC03_storageUnavailableFailsClosed() {
        UUID incident = seedIncident();
        try {
            adminJdbc.sql("REVOKE INSERT, UPDATE ON incident_budget_entry FROM control_app")
                    .update();
            assertThatThrownBy(() -> ledger.admit(incident, UUID.randomUUID(), 5, 10, 20))
                    .isInstanceOf(RuntimeException.class); // fail-closed：调用方放弃派生 run
            assertThat(count("incident_budget_entry")).isZero();
        } finally {
            adminJdbc.sql("""
                    GRANT SELECT, INSERT, UPDATE ON run_budget_state, run_budget_entry,
                        incident_budget_entry TO control_app
                    """).update();
        }
    }

    @Test
    void itC04_concurrentAdmissionsAdmitExactlyOne() throws Exception {
        UUID incident = seedIncident();
        ExecutorService pool = Executors.newFixedThreadPool(8);
        try {
            AtomicInteger allowed = new AtomicInteger();
            CountDownLatch start = new CountDownLatch(1);
            List<Future<?>> futures = new ArrayList<>();
            for (int i = 0; i < 8; i++) {
                futures.add(pool.submit(() -> {
                    start.await();
                    if (ledger.admit(incident, UUID.randomUUID(), 1, 1, 1).allowed()) {
                        allowed.incrementAndGet();
                    }
                    return null;
                }));
            }
            start.countDown();
            for (Future<?> f : futures) {
                f.get(60, TimeUnit.SECONDS);
            }
            assertThat(allowed.get()).isEqualTo(1); // 双窗限额 1：恰一放行
            assertThat(count("incident_budget_entry")).isEqualTo(2); // 放行者的双窗口行
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void itC05_unknownIncidentFailsClosed() {
        assertThatThrownBy(() -> ledger.admit(UUID.randomUUID(), UUID.randomUUID(), 1, 10, 20))
                .isInstanceOf(IllegalStateException.class);
    }

    private UUID seedIncident() {
        UUID id = UUID.randomUUID();
        controlJdbc.sql("""
                INSERT INTO incident(id, incident_key, status, generation, episode_started_at,
                    first_seen_at, last_event_at, created_at, updated_at)
                VALUES (:id, :key, 'FIRING', 0, now(), now(), now(), now(), now())
                """).param("id", id)
                .param("key", "alertname=HighErrorRate|service=edge-" + Digest.sha256Of(id.toString()).value())
                .update();
        return id;
    }
}
