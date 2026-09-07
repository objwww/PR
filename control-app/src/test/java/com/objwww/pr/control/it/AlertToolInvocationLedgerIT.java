package com.objwww.pr.control.it;

import com.objwww.pr.control.alert.domain.tool.RcaToolInvocationLedger;
import com.objwww.pr.control.alert.domain.tool.ToolInvocationState;
import com.objwww.pr.control.alert.domain.tool.ToolReasonCode;
import com.objwww.pr.control.infrastructure.persistence.PostgresRcaToolInvocationLedger;
import com.objwww.pr.shared.Digest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * M4-18 真 PG 工具调用账本（V15）：PENDING 先行、终态 CAS 单向（断网→UNKNOWN、
 * 失败→FAILED）、唯一 operation_id、逻辑键重复调用显式拒绝、悬挂 PENDING 可查。
 */
class AlertToolInvocationLedgerIT extends PostgresITBase {

    private PostgresRcaToolInvocationLedger ledger;
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
                    rca_tool_invocation
                RESTART IDENTITY CASCADE
                """).update();
        ledger = new PostgresRcaToolInvocationLedger(controlJdbc, controlTx);
        UUID incident = UUID.randomUUID();
        controlJdbc.sql("""
                INSERT INTO incident(id, incident_key, status, generation, episode_started_at,
                    first_seen_at, last_event_at, created_at, updated_at)
                VALUES (:id, :key, 'FIRING', 0, now(), now(), now(), now(), now())
                """).param("id", incident)
                .param("key", "alertname=HighErrorRate|service=edge-" + incident).update();
        runId = UUID.randomUUID();
        controlJdbc.sql("""
                INSERT INTO rca_run(id, incident_id, generation, trigger_kind, state,
                    investigation_hash, created_at, updated_at)
                VALUES (:id, :inc, 0, 'INITIAL', 'QUEUED', :hash, now(), now())
                """).param("id", runId).param("inc", incident)
                .param("hash", Digest.sha256Of("it-" + runId).value()).update();
    }

    private RcaToolInvocationLedger.InvocationIdentity identity(long callSeq) {
        return new RcaToolInvocationLedger.InvocationIdentity(UUID.randomUUID(), runId,
                UUID.randomUUID(), UUID.randomUUID(), callSeq, "prom.query", "1.0.0",
                Digest.sha256Of("action-" + callSeq).value());
    }

    private String stateOf(UUID operationId) {
        return controlJdbc.sql("SELECT state FROM rca_tool_invocation WHERE id = :id")
                .param("id", operationId).query(String.class).single();
    }

    @Test
    void itL01_open先行悬挂可见_终态succeed收口() {
        RcaToolInvocationLedger.InvocationIdentity id = identity(1);
        ledger.open(id);
        assertThat(stateOf(id.operationId())).isEqualTo("PENDING"); // 进程死后悬挂可查
        assertThat(ledger.succeed(id.operationId())).isTrue();
        assertThat(stateOf(id.operationId())).isEqualTo("SUCCESS");
        String reason = controlJdbc.sql(
                        "SELECT reason_code FROM rca_tool_invocation WHERE id = :id")
                .param("id", id.operationId()).query(String.class).single();
        assertThat(reason).isNull(); // SUCCESS 无原因码（ck_rca_tool_invocation_success_no_reason）
    }

    @Test
    void itL02_fail终态_CAS单回执_终态不可改写() {
        RcaToolInvocationLedger.InvocationIdentity id = identity(2);
        ledger.open(id);
        assertThat(ledger.fail(id.operationId(), ToolInvocationState.FAILED,
                ToolReasonCode.TIMEOUT)).isTrue();
        // 终态不可改写：再 succeed / 再 fail 均 false
        assertThat(ledger.succeed(id.operationId())).isFalse();
        assertThat(ledger.fail(id.operationId(), ToolInvocationState.FAILED,
                ToolReasonCode.TIMEOUT)).isFalse();
        assertThat(stateOf(id.operationId())).isEqualTo("FAILED");
    }

    @Test
    void itL03_断网语义_transportUnknown() {
        RcaToolInvocationLedger.InvocationIdentity id = identity(3);
        ledger.open(id);
        assertThat(ledger.fail(id.operationId(), ToolInvocationState.UNKNOWN,
                ToolReasonCode.TRANSPORT_UNKNOWN)).isTrue();
        assertThat(stateOf(id.operationId())).isEqualTo("UNKNOWN");
    }

    @Test
    void itL04_同一逻辑调用重复落账_键冲突显式拒绝() {
        RcaToolInvocationLedger.InvocationIdentity id = identity(4);
        ledger.open(id);
        // 同 (run,task,attempt,call_seq,tool) 再开一笔 = 重复调用，DB 键拒绝
        assertThatThrownBy(() -> ledger.open(new RcaToolInvocationLedger.InvocationIdentity(
                UUID.randomUUID(), id.runId(), id.taskId(), id.attemptId(), id.callSeq(),
                id.toolName(), id.toolVersion(), id.actionDigest())))
                .isInstanceOf(RuntimeException.class);
        assertThat(count("rca_tool_invocation")).isEqualTo(1);
    }

    @Test
    void itL05_非法原因码_DB_CHECK拒绝() {
        RcaToolInvocationLedger.InvocationIdentity id = identity(5);
        ledger.open(id);
        assertThatThrownBy(() -> adminJdbc.sql(
                        "UPDATE rca_tool_invocation SET state='FAILED', reason_code='MAGIC', "
                                + "settled_at=now() WHERE id=:id")
                .param("id", id.operationId()).update())
                .isInstanceOf(RuntimeException.class);
        assertThat(stateOf(id.operationId())).isEqualTo("PENDING");
    }
}
