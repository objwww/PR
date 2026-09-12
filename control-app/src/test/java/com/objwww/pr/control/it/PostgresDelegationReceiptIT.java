package com.objwww.pr.control.it;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.objwww.pr.control.alert.application.AlertClock;
import com.objwww.pr.control.alert.application.agent.DelegationReceiptService;
import com.objwww.pr.control.alert.domain.agent.DelegationDecision;
import com.objwww.pr.control.alert.domain.agent.DelegationReceipt;
import com.objwww.pr.control.alert.domain.repository.DelegationDecisionRepository;
import com.objwww.pr.control.alert.domain.repository.DelegationReceiptRepository;
import com.objwww.pr.control.alert.domain.repository.RcaRunRepository;
import com.objwww.pr.control.alert.domain.repository.RcaTaskRepository;
import com.objwww.pr.control.alert.support.AlertInMemoryStores;
import com.objwww.pr.control.infrastructure.persistence.PostgresDelegationDecisionRepository;
import com.objwww.pr.control.infrastructure.persistence.PostgresDelegationReceiptRepository;
import com.objwww.pr.shared.Digest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * MC21~23 回执台账真 PG 数据面（L1；本机无 docker 自动跳过）：V96 形状与 CHECK
 * （OVERSIZED 载荷不落库/字节数上限/词表）、message_id 唯一=幂等准入键、
 * uq 冲突显式抛、run 终态围栏（MC23 迟到仅审计）、control_app 授权面与
 * publisher 零权限。准入裁决语义的封闭用例见 DelegationReceiptServiceTest（L0）。
 */
class PostgresDelegationReceiptIT extends PostgresITBase {

    private static final Instant NOW = Instant.parse("2026-09-12T12:00:00Z");
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private UUID runId;
    private UUID primaryTaskId;
    private UUID childTaskId;
    private UUID decisionId;

    private DelegationReceiptRepository receipts;
    private DelegationReceiptService service;

    @BeforeEach
    void seed() {
        receipts = new PostgresDelegationReceiptRepository(controlJdbc, MAPPER);
        RcaRunRepository runs = new com.objwww.pr.control.infrastructure.persistence
                .PostgresRcaRunRepository(controlJdbc);
        RcaTaskRepository tasks = new com.objwww.pr.control.infrastructure.persistence
                .PostgresRcaTaskRepository(controlJdbc);
        DelegationDecisionRepository decisions =
                new PostgresDelegationDecisionRepository(controlJdbc);

        UUID incidentId = UUID.randomUUID();
        controlJdbc.sql("""
                INSERT INTO incident(id, incident_key, status, generation,
                    episode_started_at, first_seen_at, last_event_at, created_at, updated_at)
                VALUES (:id, :key, 'FIRING', 0, now(), now(), now(), now(), now())
                """).param("id", incidentId)
                .param("key", "alertname=HighErrorRate|service=mc21-" + incidentId)
                .update();
        runId = UUID.randomUUID();
        controlJdbc.sql("""
                INSERT INTO rca_run(id, incident_id, generation, trigger_kind, state,
                    investigation_hash, created_at, updated_at)
                VALUES (:id, :inc, 0, 'INITIAL', 'RUNNING', :hash, now(), now())
                """).param("id", runId).param("inc", incidentId)
                .param("hash", Digest.sha256Of("it-" + runId).value()).update();
        primaryTaskId = UUID.randomUUID();
        childTaskId = UUID.randomUUID();
        controlJdbc.sql("""
                INSERT INTO rca_task(id, run_id, task_key, state, priority,
                    available_at, ready_since, deadline_at, round_id, created_at, updated_at)
                VALUES (:id, :run, 'PRIMARY_INVESTIGATE', 'RUNNING', 5,
                    now(), now(), now(), 0, now(), now())
                """).param("id", primaryTaskId).param("run", runId).update();
        controlJdbc.sql("""
                INSERT INTO rca_task(id, run_id, task_key, state, priority,
                    available_at, ready_since, deadline_at, round_id, created_at, updated_at)
                VALUES (:id, :run, 'DELEGATE-g-logs', 'READY', 5,
                    now(), now(), now(), 1, now(), now())
                """).param("id", childTaskId).param("run", runId).update();
        decisionId = UUID.randomUUID();
        decisions.insert(new DelegationDecision(decisionId, runId, primaryTaskId, 1, 0,
                "g-logs", "logs", "1", "查错误日志",
                DelegationDecision.Status.APPROVED, null, childTaskId, NOW));

        service = new DelegationReceiptService(receipts, runs, tasks, decisions,
                controlTx, AlertClock.system(), MAPPER);
    }

    private DelegationReceiptService.Submission submission() {
        return new DelegationReceiptService.Submission(UUID.randomUUID(), runId,
                decisionId, childTaskId, 1,
                DelegationReceipt.ChildStatus.SUCCEEDED,
                List.of("error_rate=0.98 at gateway"), List.of(), List.of(), List.of());
    }

    @Test
    @DisplayName("MC21：message_id 唯一=幂等准入键——服务面重投恰一行，直插撞唯一键显式抛")
    void messageIdIsIdempotencyKey() {
        UUID messageId = UUID.randomUUID();
        DelegationReceiptService.Submission sub = new DelegationReceiptService.Submission(
                messageId, runId, decisionId, childTaskId, 1,
                DelegationReceipt.ChildStatus.SUCCEEDED,
                List.of("f"), List.of(), List.of(), List.of());

        DelegationReceiptService.Verdict first = service.submit(sub);
        DelegationReceiptService.Verdict again = service.submit(sub);

        assertThat(first.duplicate()).isFalse();
        assertThat(again.duplicate()).isTrue();
        assertThat(again.receipt().id()).isEqualTo(first.receipt().id());
        assertThat(controlJdbc.sql("SELECT count(*) FROM rca_delegation_receipt")
                .query(Long.class).single()).isEqualTo(1L);
        assertThatThrownBy(() -> receipts.insert(new DelegationReceipt(UUID.randomUUID(),
                messageId, runId, primaryTaskId, childTaskId, 1, "g-logs", "logs",
                DelegationReceipt.ChildStatus.SUCCEEDED,
                DelegationReceipt.Admission.ACCEPTED, List.of("f"), List.of(),
                List.of(), List.of(), Digest.sha256Of("x").value(), 8, NOW)))
                .as("uq(message_id) 兜底直插路径（DuplicateKey 语义）")
                .isInstanceOf(DuplicateKeyException.class);
    }

    @Test
    @DisplayName("MC21：OVERSIZED 载荷不落库（ck 强制）+ 字节数上限（ck）")
    void oversizedConstraintsEnforcedByDatabase() {
        service.submit(new DelegationReceiptService.Submission(UUID.randomUUID(), runId,
                decisionId, childTaskId, 1, DelegationReceipt.ChildStatus.SUCCEEDED,
                List.of("X".repeat(70_000)), List.of(), List.of(), List.of()));

        String admission = controlJdbc.sql("""
                SELECT admission FROM rca_delegation_receipt
                 WHERE child_task_id = :child
                """).param("child", childTaskId).query(String.class).single();
        assertThat(admission).isEqualTo("OVERSIZED");
        Integer findingsBytes = controlJdbc.sql("""
                SELECT octet_length(findings::text) FROM rca_delegation_receipt
                 WHERE child_task_id = :child
                """).param("child", childTaskId).query(Integer.class).single();
        assertThat(findingsBytes).as("OVERSIZED 行 findings 强制空（无内存失控）")
                .isEqualTo(2);

        assertThatThrownBy(() -> controlJdbc.sql("""
                        INSERT INTO rca_delegation_receipt(id, message_id, run_id,
                            primary_task_id, child_task_id, round_id, gap_id, role_id,
                            child_status, admission, payload_digest, payload_bytes,
                            received_at)
                        VALUES (:id, :mid, :run, :primary, :child, 1, 'g', 'logs',
                            'SUCCEEDED', 'ACCEPTED', :digest, 999999, now())
                        """).param("id", UUID.randomUUID()).param("mid", UUID.randomUUID())
                .param("run", runId).param("primary", primaryTaskId)
                .param("child", childTaskId).param("digest", Digest.sha256Of("y").value())
                .update()).as("ck_mc21_receipt_payload_size 背书")
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("MC23：run 终态后提交 → LATE 审计行，合并面查不到（迟到不冒充新现场）")
    void lateReceiptAuditedButNeverMerged() {
        adminJdbc.sql(
                        "UPDATE rca_run SET state = 'FAILED', finished_at = now() WHERE id = :id")
                .param("id", runId).update();
        DelegationReceiptService.Submission sub = new DelegationReceiptService.Submission(
                UUID.randomUUID(), runId, decisionId, childTaskId, 1,
                DelegationReceipt.ChildStatus.SUCCEEDED,
                List.of("迟到的 W1 结论"), List.of(), List.of(), List.of());

        DelegationReceiptService.Verdict verdict = service.submit(sub);

        assertThat(verdict.receipt().admission()).isEqualTo(DelegationReceipt.Admission.LATE);
        assertThat(receipts.findAcceptedByRunAndRound(runId, primaryTaskId, 1))
                .as("合并面（当前轮 ACCEPTED）对 LATE 行零命中").isEmpty();
    }

    @Test
    @DisplayName("合并面：仅当前轮 ACCEPTED 行可见；LATE/SHAPE 行不进")
    void mergeFaceReadsAcceptedCurrentRoundOnly() {
        service.submit(submission());
        adminJdbc.sql(
                        "UPDATE rca_run SET state = 'FAILED', finished_at = now() WHERE id = :id")
                .param("id", runId).update();
        service.submit(new DelegationReceiptService.Submission(UUID.randomUUID(), runId,
                decisionId, childTaskId, 1, DelegationReceipt.ChildStatus.SUCCEEDED,
                List.of("late"), List.of(), List.of(), List.of()));

        assertThat(receipts.findAcceptedByRunAndRound(runId, primaryTaskId, 1))
                .hasSize(1);
        assertThat(receipts.findByChildTaskId(childTaskId))
                .as("审计面两行全可见（ACCEPTED+LATE）").hasSize(2);
    }

    @Test
    @DisplayName("授权面：control_app 只增读；publisher_app 零权限（V96 矩阵）")
    void grantsFollowLeastPrivilege() {
        service.submit(submission());

        assertThat(controlJdbc.sql("SELECT count(*) FROM rca_delegation_receipt")
                .query(Long.class).single()).isEqualTo(1L);
        assertThatThrownBy(() -> publisherJdbc.sql(
                        "SELECT count(*) FROM rca_delegation_receipt")
                .query(Long.class).single())
                .as("publisher 对回执台账零读权限")
                .hasStackTraceContaining("permission denied");
        assertThatThrownBy(() -> publisherJdbc.sql("""
                        INSERT INTO rca_delegation_receipt(id, message_id, run_id,
                            primary_task_id, child_task_id, round_id, gap_id, role_id,
                            child_status, admission, payload_digest, payload_bytes,
                            received_at)
                        VALUES (:id, :mid, :run, :primary, :child, 1, 'g', 'logs',
                            'SUCCEEDED', 'ACCEPTED', :digest, 8, now())
                        """).param("id", UUID.randomUUID()).param("mid", UUID.randomUUID())
                .param("run", runId).param("primary", primaryTaskId)
                .param("child", childTaskId).param("digest", Digest.sha256Of("z").value())
                .update()).isInstanceOf(RuntimeException.class);
    }
}
