package com.objwww.pr.control.it;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.objwww.pr.control.alert.application.AlertClock;
import com.objwww.pr.control.alert.application.agent.DelegationReceiptService;
import com.objwww.pr.control.alert.domain.agent.DelegationDecision;
import com.objwww.pr.control.alert.domain.agent.DelegationReceipt;
import com.objwww.pr.control.alert.domain.evidence.EvidenceEnvelope;
import com.objwww.pr.control.alert.domain.model.Incident;
import com.objwww.pr.control.alert.domain.model.IncidentStatus;
import com.objwww.pr.control.alert.domain.model.RcaRun;
import com.objwww.pr.control.alert.domain.model.RcaRunState;
import com.objwww.pr.control.alert.domain.model.RcaTask;
import com.objwww.pr.control.alert.domain.model.RcaTaskState;
import com.objwww.pr.control.alert.domain.model.RunTrigger;
import com.objwww.pr.control.alert.domain.repository.DelegationDecisionRepository;
import com.objwww.pr.control.alert.domain.repository.DelegationReceiptRepository;
import com.objwww.pr.control.alert.domain.repository.IncidentRepository;
import com.objwww.pr.control.alert.domain.repository.RcaRunRepository;
import com.objwww.pr.control.alert.domain.repository.RcaTaskRepository;
import com.objwww.pr.control.alert.domain.repository.TaskExecutionBindingRepository;
import com.objwww.pr.control.infrastructure.persistence.PostgresDelegationDecisionRepository;
import com.objwww.pr.control.infrastructure.persistence.PostgresDelegationReceiptRepository;
import com.objwww.pr.control.infrastructure.persistence.PostgresEvidenceRepository;
import com.objwww.pr.control.infrastructure.persistence.PostgresIncidentRepository;
import com.objwww.pr.control.infrastructure.persistence.PostgresRcaRunRepository;
import com.objwww.pr.control.infrastructure.persistence.PostgresRcaTaskRepository;
import com.objwww.pr.shared.Digest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * RV04/T20~T21（审查方案 §四，真 PG）：回执准入并发与线性化——
 * <ul>
 *   <li>T20：两连接同 messageId 并发提交 → 恰一 ACCEPTED、双方读到同一实际行、
 *       零 25P02/零假回执（ON CONFLICT 原子幂等 + run 行锁线性化）；</li>
 *   <li>T21：取消与回执准入两种提交序——取消先提交 → LATE 审计不合入；
 *       准入先提交 → ACCEPTED 保留、随后取消照常（回执不算迟到），零死锁。</li>
 * </ul>
 */
class PostgresDelegationReceiptConcurrencyIT extends PostgresITBase {

    private JdbcClient jdbc;
    private RcaRunRepository runs;
    private IncidentRepository incidents;
    private RcaTaskRepository tasks;
    private DelegationDecisionRepository decisions;
    private DelegationReceiptRepository receipts;
    private PostgresEvidenceRepository evidence;
    private DelegationReceiptService service;

    @BeforeEach
    void setUp() {
        jdbc = JdbcClient.create(controlDataSource());
        runs = new PostgresRcaRunRepository(jdbc);
        incidents = new PostgresIncidentRepository(jdbc);
        tasks = new PostgresRcaTaskRepository(jdbc);
        decisions = new PostgresDelegationDecisionRepository(jdbc);
        receipts = new PostgresDelegationReceiptRepository(jdbc, new ObjectMapper());
        evidence = new PostgresEvidenceRepository(jdbc, controlTx, new ObjectMapper());
        service = new DelegationReceiptService(receipts, runs, tasks, decisions,
                evidence, controlTx, () -> Instant.now(), new ObjectMapper());
    }

    private UUID seedScene(String tag) {
        Instant now = Instant.now();
        UUID incidentId = UUID.randomUUID();
        incidents.insert(new Incident(incidentId, "alertname=RV04|service=" + tag,
                IncidentStatus.FIRING, 0, now.minus(Duration.ofMinutes(5)),
                now.minus(Duration.ofMinutes(5)), null, null, null, 0, 0, 0, null,
                now.minus(Duration.ofMinutes(5)), now.minus(Duration.ofMinutes(5)),
                now, now));
        UUID runId = UUID.randomUUID();
        runs.insert(new RcaRun(runId, incidentId, 0, RunTrigger.INITIAL,
                RcaRunState.RUNNING, Digest.sha256Of("rv04-" + tag),
                now.minus(Duration.ofMinutes(4)), now, now, null, null));
        UUID primaryTaskId = UUID.randomUUID();
        tasks.insert(new RcaTask(primaryTaskId, runId, RcaTask.PRIMARY_INVESTIGATE,
                RcaTaskState.RUNNING, 5, now, now, Instant.MAX, null, null, 0, 0, 2,
                now, now, 0));
        UUID childTaskId = UUID.randomUUID();
        tasks.insert(new RcaTask(childTaskId, runId, "DELEGATE-g-logs",
                RcaTaskState.RUNNING, 5, now, now, Instant.MAX, null, null, 0, 1, 2,
                now, now, 1));
        decisions.insert(new DelegationDecision(UUID.randomUUID(), runId, primaryTaskId,
                1, 0, "g-logs", "logs", "1", "查错误日志",
                DelegationDecision.Status.APPROVED, null, childTaskId, now));
        // 真实证据行（T22 校验面真库化）：digest=sha256(payload)，读路径 verify 比对
        UUID evidenceId = UUID.randomUUID();
        String payload = "{\"k\":\"v\"}";
        jdbc.sql("""
                INSERT INTO rca_evidence (id, run_id, task_id, evidence_type,
                    schema_version, observed_generation, source, scope, payload,
                    payload_digest, created_at)
                VALUES (:id, :runId, NULL, 'logs.aggregate', 'am4-evidence.v1', 0,
                    'loki', '{}', :payload, :digest, now())
                """)
                .param("id", evidenceId)
                .param("runId", runId)
                .param("payload", payload)
                .param("digest", Digest.sha256Of(payload).value())
                .update();
        return runId;
    }

    private DelegationReceiptService.Submission submission(UUID runId, UUID decisionId,
            UUID childTaskId, UUID messageId, UUID evidenceId) {
        return new DelegationReceiptService.Submission(messageId, runId, decisionId,
                childTaskId, 1, DelegationReceipt.ChildStatus.SUCCEEDED,
                List.of("错误率饱和，窗口内 ERROR 计数一致"),
                List.of(evidenceId.toString()), List.of(),
                List.of("确定性角色无业务结论能力"));
    }

    @Test
    @DisplayName("RV04/T20：两连接同 messageId 并发提交 → 恰一 ACCEPTED、同读实际行、零 25P02 零假回执")
    void concurrentSameMessageIdAdmitsExactlyOnce() throws Exception {
        UUID runId = seedScene("t20");
        // 取裁决行/子任务/证据 id（seed 内生成，经台账读回）
        UUID decisionId = decisions.findByRunAndPrimaryTask(runId, primaryOf(runId))
                .get(0).id();
        UUID childTaskId = decisions.findByRunAndPrimaryTask(runId, primaryOf(runId))
                .get(0).childTaskId();
        UUID evidenceId = jdbc.sql("SELECT id FROM rca_evidence WHERE run_id = :run")
                .param("run", runId).query((rs, n) -> UUID.fromString(rs.getString("id")))
                .single();
        UUID messageId = UUID.randomUUID();
        CyclicBarrier barrier = new CyclicBarrier(2);
        AtomicReference<Object> firstOutcome = new AtomicReference<>();
        AtomicReference<Object> secondOutcome = new AtomicReference<>();
        AtomicReference<Throwable> firstFailure = new AtomicReference<>();
        AtomicReference<Throwable> secondFailure = new AtomicReference<>();
        Thread a = Thread.ofPlatform().name("rv04-a").unstarted(() -> {
            try {
                barrier.await(10, TimeUnit.SECONDS);
                firstOutcome.set(service.submit(
                        submission(runId, decisionId, childTaskId, messageId, evidenceId)));
            } catch (Throwable e) {
                firstFailure.set(e);
            }
        });
        Thread b = Thread.ofPlatform().name("rv04-b").unstarted(() -> {
            try {
                barrier.await(10, TimeUnit.SECONDS);
                secondOutcome.set(service.submit(
                        submission(runId, decisionId, childTaskId, messageId, evidenceId)));
            } catch (Throwable e) {
                secondFailure.set(e);
            }
        });
        a.start();
        b.start();
        a.join(30_000);
        b.join(30_000);
        assertThat(firstFailure.get()).as("A 线程零异常（无 25P02）").isNull();
        assertThat(secondFailure.get()).as("B 线程零异常（无 25P02）").isNull();

        DelegationReceiptService.Verdict v1 = (DelegationReceiptService.Verdict) firstOutcome.get();
        DelegationReceiptService.Verdict v2 = (DelegationReceiptService.Verdict) secondOutcome.get();
        // 口径：duplicate=true 的裁决返回的就是既有 ACCEPTED 行（admission 也是
        // ACCEPTED），恰一性必须按「非重复的 ACCEPTED」数，不能按 admission 数
        // （195 首跑实测：按 admission 数出 2=重复裁决携带同一胜者行，产品正确）
        long freshAccepted = List.of(v1, v2).stream()
                .filter(v -> !v.duplicate()
                        && v.receipt().admission() == DelegationReceipt.Admission.ACCEPTED)
                .count();
        assertThat(freshAccepted).as("恰一非重复 ACCEPTED（并发幂等）").isEqualTo(1);
        long duplicates = List.of(v1, v2).stream().filter(DelegationReceiptService.Verdict::duplicate).count();
        assertThat(duplicates).as("恰一重复面（duplicate=true）").isEqualTo(1);
        assertThat(v1.receipt().id()).as("双方读到同一实际胜者行").isEqualTo(v2.receipt().id());
        assertThat(receipts.findByMessageId(messageId).orElseThrow().admission())
                .isEqualTo(DelegationReceipt.Admission.ACCEPTED);
    }

    @Test
    @DisplayName("RV04/T21：取消与回执两种提交序——取消先=LATE 审计；准入先=ACCEPTED 保留，零死锁")
    void cancelVsReceiptTwoOrderingsLinearized() {
        // 序 1：取消先提交 → 回执 LATE 审计（不合入、不冒充新现场）
        UUID cancelledRun = seedScene("t21-cancelled");
        UUID decision1 = decisions.findByRunAndPrimaryTask(cancelledRun, primaryOf(cancelledRun))
                .get(0).id();
        UUID child1 = decisions.findByRunAndPrimaryTask(cancelledRun, primaryOf(cancelledRun))
                .get(0).childTaskId();
        UUID evidence1 = jdbc.sql("SELECT id FROM rca_evidence WHERE run_id = :run")
                .param("run", cancelledRun).query((rs, n) -> UUID.fromString(rs.getString("id")))
                .single();
        controlTx.executeWithoutResult(status -> {
            RcaRun run = runs.findById(cancelledRun).orElseThrow();
            runs.update(new RcaRun(run.id(), run.incidentId(), run.generation(),
                    run.trigger(), RcaRunState.CANCELLED, run.investigationHash(),
                    run.createdAt(), Instant.now(), run.startedAt(), Instant.now(), "rv04",
                    run.purpose(), run.purposeSource(), run.completionKind()));
        });
        UUID lateMessage = UUID.randomUUID();
        var lateVerdict = service.submit(
                submission(cancelledRun, decision1, child1, lateMessage, evidence1));
        assertThat(lateVerdict.receipt().admission())
                .as("取消先提交 → 回执 LATE 审计（T21 序 1）")
                .isEqualTo(DelegationReceipt.Admission.LATE);

        // 序 2：准入先提交 → ACCEPTED 保留；随后取消照常成功（回执不算迟到）
        UUID admittedRun = seedScene("t21-admitted");
        UUID decision2 = decisions.findByRunAndPrimaryTask(admittedRun, primaryOf(admittedRun))
                .get(0).id();
        UUID child2 = decisions.findByRunAndPrimaryTask(admittedRun, primaryOf(admittedRun))
                .get(0).childTaskId();
        UUID evidence2 = jdbc.sql("SELECT id FROM rca_evidence WHERE run_id = :run")
                .param("run", admittedRun).query((rs, n) -> UUID.fromString(rs.getString("id")))
                .single();
        UUID acceptedMessage = UUID.randomUUID();
        var okVerdict = service.submit(
                submission(admittedRun, decision2, child2, acceptedMessage, evidence2));
        assertThat(okVerdict.receipt().admission())
                .isEqualTo(DelegationReceipt.Admission.ACCEPTED);
        controlTx.executeWithoutResult(status -> {
            RcaRun run = runs.findById(admittedRun).orElseThrow();
            runs.update(new RcaRun(run.id(), run.incidentId(), run.generation(),
                    run.trigger(), RcaRunState.CANCELLED, run.investigationHash(),
                    run.createdAt(), Instant.now(), run.startedAt(), Instant.now(), "rv04",
                    run.purpose(), run.purposeSource(), run.completionKind()));
        });
        assertThat(runs.findById(admittedRun).orElseThrow().state())
                .as("准入先提交不阻塞取消（T21 序 2，零死锁）")
                .isEqualTo(RcaRunState.CANCELLED);
        assertThat(receipts.findByMessageId(acceptedMessage)
                .orElseThrow().admission()).isEqualTo(DelegationReceipt.Admission.ACCEPTED);
    }

    private UUID primaryOf(UUID runId) {
        return jdbc.sql("""
                SELECT id FROM rca_task WHERE run_id = :run
                  AND task_key = 'PRIMARY_INVESTIGATE'
                """).param("run", runId)
                .query((rs, n) -> UUID.fromString(rs.getString("id"))).single();
    }
}
