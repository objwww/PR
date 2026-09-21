package com.objwww.pr.control.it;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.objwww.pr.control.alert.domain.agent.RcaModelCallLedger;
import com.objwww.pr.control.alert.domain.agent.RcaModelOutputCapture;
import com.objwww.pr.control.alert.domain.agent.RcaModelOutputReadPort;
import com.objwww.pr.control.alert.domain.identity.InvestigationInputs;
import com.objwww.pr.control.alert.domain.model.Incident;
import com.objwww.pr.control.alert.domain.model.IncidentStatus;
import com.objwww.pr.control.alert.domain.model.RcaEngine;
import com.objwww.pr.control.alert.domain.model.RcaRun;
import com.objwww.pr.control.alert.domain.model.RcaRunRouting;
import com.objwww.pr.control.alert.domain.model.RcaRunState;
import com.objwww.pr.control.alert.domain.model.RunTrigger;
import com.objwww.pr.control.infrastructure.persistence.PostgresIncidentRepository;
import com.objwww.pr.control.infrastructure.persistence.PostgresRcaModelOutputCapture;
import com.objwww.pr.control.infrastructure.persistence.PostgresRcaModelOutputRead;
import com.objwww.pr.control.infrastructure.persistence.PostgresRcaRunRepository;
import com.objwww.pr.control.infrastructure.persistence.PostgresRunConfigEpochRepository;
import com.objwww.pr.shared.Digest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.postgresql.util.PSQLException;
import org.springframework.dao.DataIntegrityViolationException;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 输出捕获（真 PG，V167 rca_model_output）：三档 CHECK 两向钉（FULL 必有文/
 * DIGEST_ONLY 必无文）、append-only 授权面（control_app 无 update/delete、
 * publisher_app 零授）、run 级读面 join 取齐（无捕获行侧 null 如实）——
 * PostgresRcaModelInputIT（V90）逐条对称。静态面由
 * EnMigrationContractTest.v167 锁（Docker 不可用时本地绿）。
 */
class PostgresRcaModelOutputIT extends PostgresITBase {

    private static final String OUTPUT = "{\"verdict\":\"根因=连接池耗尽\"}";

    private org.springframework.jdbc.core.simple.JdbcClient jdbc;
    private PostgresIncidentRepository incidents;
    private PostgresRcaRunRepository rcaRuns;
    private RcaModelCallLedger ledger;
    private RcaModelOutputCapture fullCapture;
    private RcaModelOutputCapture digestOnlyCapture;
    private RcaModelOutputReadPort reads;

    @BeforeEach
    void setUpRepositories() {
        jdbc = org.springframework.jdbc.core.simple.JdbcClient.create(controlDataSource());
        incidents = new PostgresIncidentRepository(jdbc);
        rcaRuns = new PostgresRcaRunRepository(jdbc,
                new PostgresRunConfigEpochRepository(jdbc));
        ledger = new com.objwww.pr.control.infrastructure.persistence
                .PostgresRcaModelCallLedger(jdbc, new ObjectMapper(), controlTx);
        fullCapture = new PostgresRcaModelOutputCapture(jdbc,
                RcaModelOutputCapture.Level.FULL);
        digestOnlyCapture = new PostgresRcaModelOutputCapture(jdbc,
                RcaModelOutputCapture.Level.DIGEST_ONLY);
        reads = new PostgresRcaModelOutputRead(jdbc);
    }

    @Test
    @DisplayName("FULL 档：输出原文落行，读面按 action_seq 取回且 digest=原始响应摘要")
    void fullLevelCaptureReadableByRun() {
        UUID modelCallId = mintModelCall("full-output");
        fullCapture.capture(RcaModelOutputCapture.buildRow(modelCallId,
                RcaModelOutputCapture.Level.FULL, OUTPUT));

        List<RcaModelOutputReadPort.StepRow> rows = reads.byRun(lastRunId, 10);
        assertThat(rows).hasSize(1);
        RcaModelOutputReadPort.StepRow row = rows.get(0);
        assertThat(row.modelCallId()).isEqualTo(modelCallId);
        assertThat(row.roleId()).isEqualTo("primary");
        assertThat(row.output().level()).isEqualTo("FULL");
        assertThat(row.output().text()).isEqualTo(OUTPUT);
        assertThat(row.output().digest()).isEqualTo(Digest.sha256Of(OUTPUT).value());
        assertThat(row.input()).as("本用例未落输入捕获 → 该侧 null 如实").isNull();
    }

    @Test
    @DisplayName("DIGEST_ONLY 档：零原文 CHECK 通过；读面 text 为 null + digest 在场")
    void digestOnlyPassesCheckWithNullText() {
        UUID modelCallId = mintModelCall("digest-only-output");
        digestOnlyCapture.capture(RcaModelOutputCapture.buildRow(modelCallId,
                RcaModelOutputCapture.Level.DIGEST_ONLY, OUTPUT));

        RcaModelOutputReadPort.StepRow row = reads.byRun(lastRunId, 10).get(0);
        assertThat(row.output().level()).isEqualTo("DIGEST_ONLY");
        assertThat(row.output().text()).isNull();
        assertThat(row.output().digest()).isEqualTo(Digest.sha256Of(OUTPUT).value());
    }

    @Test
    @DisplayName("CHECK 两向钉（裸 SQL 绕过域工厂）：FULL 落空文 / DIGEST_ONLY 落原文 → ck 拒绝")
    void checkConstraintRejectsLevelTextContradictions() {
        UUID fullVoid = mintModelCall("full-void");
        assertThatThrownBy(() -> jdbc.sql("""
                        insert into rca_model_output (id, model_call_id, capture_level,
                            output_text, output_digest, created_at)
                        values (:id, :mc, 'FULL', null, :dg, now())
                        """)
                .param("id", UUID.randomUUID()).param("mc", fullVoid)
                .param("dg", Digest.sha256Of("o1").value()).update())
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("ck_rca_model_output_capture");

        UUID digestWithText = mintModelCall("digest-text");
        assertThatThrownBy(() -> jdbc.sql("""
                        insert into rca_model_output (id, model_call_id, capture_level,
                            output_text, output_digest, created_at)
                        values (:id, :mc, 'DIGEST_ONLY', '泄漏原文', :dg, now())
                        """)
                .param("id", UUID.randomUUID()).param("mc", digestWithText)
                .param("dg", Digest.sha256Of("o2").value()).update())
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("ck_rca_model_output_capture");
    }

    @Test
    @DisplayName("append-only 授权面：control_app 无 update/delete；publisher_app 零授")
    void appendOnlyGrantsDenyUpdateDeleteAndCrossRoleRead() {
        UUID modelCallId = mintModelCall("grants");
        fullCapture.capture(RcaModelOutputCapture.buildRow(modelCallId,
                RcaModelOutputCapture.Level.FULL, OUTPUT));

        // control_app：只有 select,insert —— update/delete 拒绝（42501）
        assertThatThrownBy(() -> jdbc.sql(
                        "update rca_model_output set output_text = '篡改' where id = :id")
                .param("id", modelCallId).update())
                .isInstanceOfSatisfying(org.springframework.dao.DataAccessException.class,
                        e -> assertThat(sqlStateOf(e)).isEqualTo("42501"));
        assertThatThrownBy(() -> jdbc.sql(
                        "delete from rca_model_output where id = :id")
                .param("id", modelCallId).update())
                .isInstanceOfSatisfying(org.springframework.dao.DataAccessException.class,
                        e -> assertThat(sqlStateOf(e)).isEqualTo("42501"));

        // publisher_app：全零 —— select 拒绝（42501）
        assertThatThrownBy(() -> publisherJdbc.sql(
                        "select output_digest from rca_model_output")
                .query((rs, i) -> rs.getString(1)).list())
                .isInstanceOfSatisfying(org.springframework.dao.DataAccessException.class,
                        e -> assertThat(sqlStateOf(e)).isEqualTo("42501"));
    }

    // ------------------------------------------------------------------ 夹具

    /** 当前用例铸行的 run（读面 byRun 的锚） */
    private UUID lastRunId;

    /** 40xxx 系异常剥 PSQLException 的 SQLState（42501 = insufficient_privilege） */
    private static String sqlStateOf(Exception e) {
        Throwable t = e;
        while (t != null) {
            if (t instanceof PSQLException psql) {
                return psql.getSQLState();
            }
            t = t.getCause();
        }
        return null;
    }

    /** 开一行 PENDING 账（捕获行 FK 的账本侧锚），返回 model_call id */
    private UUID mintModelCall(String tag) {
        RcaRun run = mintRoutedRun(tag);
        lastRunId = run.id();
        UUID[] taskAttempt = mintTaskAttempt(run.id());
        UUID id = UUID.randomUUID();
        ledger.open(new RcaModelCallLedger.OpenRow(id, run.id(), taskAttempt[0],
                taskAttempt[1], 0, 1, 0, "primary", "1",
                Digest.sha256Of("role").hex(), Digest.sha256Of("prompt-" + tag).value(),
                null, null, null, null, 0));
        return id;
    }

    private RcaRun mintRoutedRun(String tag) {
        Incident incident = insertIncident(tag);
        UUID id = UUID.randomUUID();
        Instant now = Instant.now();
        RcaRun run = new RcaRun(id, incident.id(), 0, RunTrigger.INITIAL,
                RcaRunState.QUEUED, Digest.sha256Of("run-" + tag),
                now.minus(Duration.ofMinutes(4)), now, null, null, null);
        rcaRuns.insertRouted(run, new RcaRunRouting(RcaEngine.NATIVE,
                Digest.sha256Of(tag + "-bundle"), incident.incidentKey(), 37,
                "IT_OUTPUT"), InvestigationInputs.freezeAt(incident, now));
        return run;
    }

    private Incident insertIncident(String tag) {
        UUID id = UUID.randomUUID();
        Instant now = Instant.now();
        Incident incident = new Incident(id, "alertname=HighErrorRate|service=" + tag,
                IncidentStatus.FIRING, 0, now.minus(Duration.ofMinutes(5)),
                now.minus(Duration.ofMinutes(5)), null, null, null, 0, 0, 0, null,
                now.minus(Duration.ofMinutes(5)), now.minus(Duration.ofMinutes(5)), now, now);
        incidents.insert(incident);
        return incident;
    }

    /** 真种 rca_task + rca_attempt（rca_model_call 双 FK 的实在面）；返回 [taskId, attemptId] */
    private UUID[] mintTaskAttempt(UUID runId) {
        UUID taskId = UUID.randomUUID();
        UUID attemptId = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO rca_task (id, run_id, task_key, state,
                    available_at, ready_since, deadline_at, created_at, updated_at)
                VALUES (:id, :run, :key, 'DONE', now(), now(), now(), now(), now())
                """)
                .param("id", taskId).param("run", runId)
                .param("key", "OUTPUT_IT_" + taskId.toString().substring(0, 8))
                .update();
        jdbc.sql("""
                INSERT INTO rca_attempt (id, task_id, attempt_no, lease_epoch, worker_id,
                    status, started_at, finished_at)
                VALUES (:id, :task, 1, 0, 'output-it', 'SUCCEEDED', now(), now())
                """)
                .param("id", attemptId).param("task", taskId)
                .update();
        return new UUID[]{taskId, attemptId};
    }
}
