package com.objwww.pr.control.it;

import com.objwww.pr.control.alert.application.AlertClock;
import com.objwww.pr.control.alert.application.RcaRunOrchestrator;
import com.objwww.pr.control.alert.application.RcaTaskExecutor;
import com.objwww.pr.control.alert.application.ReportCompletedNotifier;
import com.objwww.pr.control.alert.domain.model.RcaAttempt;
import com.objwww.pr.control.alert.domain.model.RcaAttemptStatus;
import com.objwww.pr.control.alert.domain.model.RcaRun;
import com.objwww.pr.control.alert.domain.model.RcaRunState;
import com.objwww.pr.control.alert.domain.model.RcaTask;
import com.objwww.pr.control.alert.domain.model.RcaTaskState;
import com.objwww.pr.control.alert.domain.model.RunTrigger;
import com.objwww.pr.control.alert.domain.model.ValidationStatus;
import com.objwww.pr.control.alert.domain.repository.IncidentRepository;
import com.objwww.pr.control.alert.domain.repository.RcaAttemptRepository;
import com.objwww.pr.control.alert.domain.repository.RcaReportRepository;
import com.objwww.pr.control.alert.domain.repository.RcaRunRepository;
import com.objwww.pr.control.alert.domain.repository.RcaTaskRepository;
import com.objwww.pr.control.alert.domain.service.SlaPolicy;
import com.objwww.pr.control.alert.support.AlertInMemoryStores;
import com.objwww.pr.control.infrastructure.observability.AlertMetrics;
import com.objwww.pr.control.infrastructure.persistence.PostgresIncidentRepository;
import com.objwww.pr.control.infrastructure.persistence.PostgresNotifyOutboxRepository;
import com.objwww.pr.control.infrastructure.persistence.PostgresRcaAttemptRepository;
import com.objwww.pr.control.infrastructure.persistence.PostgresRcaReportRepository;
import com.objwww.pr.control.infrastructure.persistence.PostgresRcaRunRepository;
import com.objwww.pr.control.infrastructure.persistence.PostgresRcaTaskRepository;
import com.objwww.pr.control.alert.domain.repository.InvestigationResultRepository;
import com.objwww.pr.control.alert.domain.repository.RcaToolCallRepository;
import com.objwww.pr.control.infrastructure.persistence.PostgresInvestigationResultRepository;
import com.objwww.pr.control.infrastructure.persistence.PostgresRcaToolCallRepository;
import com.objwww.pr.control.infrastructure.persistence.PostgresReportPublicationRepository;
import com.objwww.pr.control.infrastructure.persistence.PostgresSchedulerSlotRepository;
import com.objwww.pr.shared.Digest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * M4-07 generation fence 真 PG 集成（INV-AM4-4）：旧 generation 结果只能 STALE，不污染新 Run。
 * 三面栅栏——claim（SQL 只领活跃 run 任务）、finish（SUPERSEDED run 收尾 → task STALE +
 * 报告/publication/outbox 零落档）、对照（活跃 run 正常领取）。
 */
class AlertGenerationFenceIT extends PostgresITBase {

    private RcaTaskRepository tasks;
    private RcaRunRepository runs;
    private RcaRunOrchestrator orchestrator;

    @Override
    @BeforeEach
    void truncateAll() {
        super.truncateAll();
        adminJdbc.sql("INSERT INTO scheduler_slot(scope, slot_no) VALUES ('rca', 1), ('rca', 2)")
                .update();
        tasks = new PostgresRcaTaskRepository(controlJdbc);
        runs = new PostgresRcaRunRepository(controlJdbc);
        IncidentRepository incidents = new PostgresIncidentRepository(controlJdbc);
        RcaAttemptRepository attempts = new PostgresRcaAttemptRepository(controlJdbc);
        RcaReportRepository reports = new PostgresRcaReportRepository(controlJdbc);
        InvestigationResultRepository investigations =
                new PostgresInvestigationResultRepository(controlJdbc);
        RcaToolCallRepository toolCalls = new PostgresRcaToolCallRepository(controlJdbc);
        var publications = new com.objwww.pr.control.infrastructure.persistence
                .PostgresReportPublicationRepository(controlJdbc);
        var outboxes = new com.objwww.pr.control.infrastructure.persistence
                .PostgresNotifyOutboxRepository(controlJdbc);
        orchestrator = new RcaRunOrchestrator(tasks, runs, attempts, reports, incidents,
                new PostgresSchedulerSlotRepository(controlJdbc), investigations, toolCalls,
                new ReportCompletedNotifier(publications, outboxes, List.of("test"),
                        "am3-candidate-v1", 280),
                new AlertInMemoryStores.Cas(), SlaPolicy.defaults(),
                Instant::now, "rca", AlertMetrics.NOOP);
    }

    @Test
    void claimNextSkipsTasksOfSupersededRunAndPicksActiveRun() {
        Seed old = seedIncidentWithRun("fence-old", 0);
        UUID staleTask = insertTask(old.runId(), "OLD_TASK");
        supersede(old.runId(), 0);

        Seed fresh = seedIncidentWithRun("fence-new", 5);
        UUID activeTask = insertTask(fresh.runId(), "NEW_TASK");

        Optional<RcaTask> claimed = tasks.claimNext("it-worker", Instant.now(),
                Duration.ofMinutes(5));

        assertThat(claimed).isPresent();
        assertThat(claimed.orElseThrow().id()).as("只领活跃 run 的任务").isEqualTo(activeTask);
        assertThat(tasks.findById(staleTask).orElseThrow().state())
                .as("死 run 的 READY 任务不可领取，保持原态").isEqualTo(RcaTaskState.READY);
        assertThat(tasks.findById(activeTask).orElseThrow().state()).isEqualTo(RcaTaskState.LEASED);
    }

    @Test
    void staleFinishDoesNotPolluteNewRun() {
        // 场景：worker 领取任务拿到租约 → 租约期内 run 被取代 → worker 携在期租约
        // 直接收尾。必须先 claim 再 supersede——taskId 只有经 claimNext 才有租约，
        // 无租约的行会先被 requireCurrentLease 拒成 LEASE_REJECTED，到不了代际栅栏
        Seed old = seedIncidentWithRun("fence-fin", 0);
        UUID taskId = insertTask(old.runId(), "OLD_WORK");
        RcaTask leased = tasks.claimNext("it-worker", Instant.now(),
                Duration.ofMinutes(5)).orElseThrow();
        assertThat(leased.id()).as("先领取旧 run 任务（真实租约在手）").isEqualTo(taskId);
        supersede(old.runId(), 0);
        Seed fresh = seedIncidentWithRun("fence-new", 1);
        insertTask(fresh.runId(), "NEW_WORK");

        RcaAttempt attempt = new RcaAttempt(UUID.randomUUID(), taskId, 1, 1, "it-worker",
                RcaAttemptStatus.STARTED, null, null, null, Instant.now(), null, null);
        RcaRunOrchestrator.FinishOutcome outcome = orchestrator.finishTask(
                tasks.findById(taskId).orElseThrow(), "it-worker", -1, -1,
                RcaTaskExecutor.ExecutionResult.success(artifact()),
                attempt);

        assertThat(outcome).isEqualTo(RcaRunOrchestrator.FinishOutcome.STALE_GENERATION);
        assertThat(tasks.findById(taskId).orElseThrow().state())
                .as("旧代结果只能 STALE").isEqualTo(RcaTaskState.STALE);
        assertThat(count("rca_report")).as("旧代结果零报告").isZero();
        assertThat(count("report_publication")).isZero();
        assertThat(count("notify_outbox")).isZero();
        assertThat(runs.findById(fresh.runId()).orElseThrow().state())
                .as("新 run 不受污染").isEqualTo(RcaRunState.QUEUED);
    }

    // ------------------------------------------------------------------ 种子

    private record Seed(UUID incidentId, UUID runId) {
    }

    private Seed seedIncidentWithRun(String tag, int generation) {
        UUID incidentId = UUID.randomUUID();
        UUID runId = UUID.randomUUID();
        String hash = Digest.sha256Of("it-" + tag + "-" + incidentId).value();
        controlJdbc.sql("""
                INSERT INTO incident(id, incident_key, status, generation, episode_started_at,
                    first_seen_at, last_event_at, created_at, updated_at)
                VALUES (:id, :key, 'FIRING', :gen, now(), now(), now(), now(), now())
                """).param("id", incidentId)
                .param("key", "alertname=HighErrorRate|service=" + tag + "-" + incidentId)
                .param("gen", generation).update();
        controlJdbc.sql("""
                INSERT INTO rca_run(id, incident_id, generation, trigger_kind, state,
                    investigation_hash, created_at, updated_at)
                VALUES (:id, :inc, :gen, 'INITIAL', 'QUEUED', :hash, now(), now())
                """).param("id", runId).param("inc", incidentId).param("gen", generation)
                .param("hash", hash).update();
        return new Seed(incidentId, runId);
    }

    private UUID insertTask(UUID runId, String key) {
        UUID taskId = UUID.randomUUID();
        controlJdbc.sql("""
                INSERT INTO rca_task(id, run_id, task_key, state, priority,
                    available_at, ready_since, deadline_at, created_at, updated_at)
                VALUES (:id, :run, :key, 'READY', 100, now(), now(), now(), now(), now())
                """).param("id", taskId).param("run", runId).param("key", key).update();
        return taskId;
    }

    private void supersede(UUID runId, int generation) {
        runs.update(new RcaRun(runId, runs.findById(runId).orElseThrow().incidentId(), generation,
                RunTrigger.INITIAL, RcaRunState.SUPERSEDED,
                Digest.sha256Of("supersede-" + runId), Instant.now(), Instant.now(),
                Instant.now(), Instant.now(), null));
    }

    private static RcaTaskExecutor.AttemptArtifact artifact() {
        return new RcaTaskExecutor.AttemptArtifact(1, ValidationStatus.STRUCTURE_VALIDATED,
                List.of(), "{\"schema_version\":\"1\"}", "raw", null, List.of(),
                null, null, "deepseek-v3", null, null, null, true, null);
    }
}
