package com.objwww.pr.control.it;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.objwww.pr.control.alert.domain.agent.RcaModelCallLedger;
import com.objwww.pr.control.alert.domain.agent.RcaModelInputCapture;
import com.objwww.pr.control.alert.domain.agent.RcaModelInputReplayPort;
import com.objwww.pr.control.alert.domain.identity.InvestigationInputs;
import com.objwww.pr.control.alert.domain.model.Incident;
import com.objwww.pr.control.alert.domain.model.IncidentStatus;
import com.objwww.pr.control.alert.domain.model.RcaEngine;
import com.objwww.pr.control.alert.domain.model.RcaRun;
import com.objwww.pr.control.alert.domain.model.RcaRunRouting;
import com.objwww.pr.control.alert.domain.model.RcaRunState;
import com.objwww.pr.control.alert.domain.model.RunTrigger;
import com.objwww.pr.control.infrastructure.persistence.PostgresIncidentRepository;
import com.objwww.pr.control.infrastructure.persistence.PostgresRcaModelInputCapture;
import com.objwww.pr.control.infrastructure.persistence.PostgresRcaModelInputReplay;
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
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * R2 输入捕获（真 PG，V90 rca_model_input）：三档 CHECK 两向钉（FULL 必有文/
 * DIGEST_ONLY 必无文）、append-only 授权面（control_app 无 update/delete、
 * publisher_app 零授）、捕获↔账本 join 回放对账（promptDigest 恒等）。
 * 静态面由 EnMigrationContractTest.v90 锁（Docker 不可用时本地绿）。
 */
class PostgresRcaModelInputIT extends PostgresITBase {

    private static final String PROMPT = "你是主调查 Agent。\n{\"task_envelope\":{}}";

    private org.springframework.jdbc.core.simple.JdbcClient jdbc;    private PostgresIncidentRepository incidents;
    private PostgresRcaRunRepository rcaRuns;
    private RcaModelCallLedger ledger;
    private RcaModelInputCapture fullCapture;
    private RcaModelInputCapture digestOnlyCapture;
    private RcaModelInputReplayPort replay;

    @BeforeEach
    void setUpRepositories() {
        jdbc = org.springframework.jdbc.core.simple.JdbcClient.create(controlDataSource());
        incidents = new PostgresIncidentRepository(jdbc);
        rcaRuns = new PostgresRcaRunRepository(jdbc,
                new PostgresRunConfigEpochRepository(jdbc));
        ledger = new com.objwww.pr.control.infrastructure.persistence
                .PostgresRcaModelCallLedger(jdbc, new ObjectMapper(), controlTx);
        fullCapture = new PostgresRcaModelInputCapture(jdbc,
                RcaModelInputCapture.Level.FULL);
        digestOnlyCapture = new PostgresRcaModelInputCapture(jdbc,
                RcaModelInputCapture.Level.DIGEST_ONLY);
        replay = new PostgresRcaModelInputReplay(jdbc);
    }

    @Test
    @DisplayName("FULL 档：捕获行原文复算摘要 = 捕获摘要 = 账本摘要，回放 join 一次取齐")
    void fullLevelCaptureReplaysWithDigestReconciliation() {
        UUID modelCallId = mintModelCall("full-replay", Digest.sha256Of(PROMPT).value());
        fullCapture.capture(RcaModelInputCapture.buildRow(modelCallId,
                RcaModelInputCapture.Level.FULL, PROMPT, Digest.sha256Of(PROMPT).value()));

        Optional<RcaModelInputReplayPort.ReplayRow> row =
                replay.byModelCallId(modelCallId);
        assertThat(row).isPresent();
        assertThat(row.get().promptText()).isEqualTo(PROMPT);
        assertThat(row.get().captureDigest())
                .isEqualTo(Digest.sha256Of(PROMPT).value())
                .isEqualTo(row.get().ledgerPromptDigest());
        assertThat(row.get().roleId()).isEqualTo("primary");
        assertThat(row.get().roleDigest()).isEqualTo(Digest.sha256Of("role").hex());
    }

    @Test
    @DisplayName("DIGEST_ONLY 档：零原文 CHECK 通过；回放面 text 为 null 如实")
    void digestOnlyPassesCheckWithNullText() {
        UUID modelCallId = mintModelCall("digest-only", Digest.sha256Of(PROMPT).value());
        digestOnlyCapture.capture(RcaModelInputCapture.buildRow(modelCallId,
                RcaModelInputCapture.Level.DIGEST_ONLY, PROMPT,
                Digest.sha256Of(PROMPT).value()));

        Optional<RcaModelInputReplayPort.ReplayRow> row =
                replay.byModelCallId(modelCallId);
        assertThat(row).isPresent();
        assertThat(row.get().captureLevel()).isEqualTo("DIGEST_ONLY");
        assertThat(row.get().promptText()).isNull();
        assertThat(row.get().captureDigest()).isEqualTo(row.get().ledgerPromptDigest());
    }

    @Test
    @DisplayName("CHECK 两向钉（裸 SQL 绕过域工厂）：FULL 落空文 / DIGEST_ONLY 落原文 → ck 拒绝")
    void checkConstraintRejectsLevelTextContradictions() {
        UUID fullVoid = mintModelCall("full-void", Digest.sha256Of("p1").value());
        assertThatThrownBy(() -> jdbc.sql("""
                        insert into rca_model_input (id, model_call_id, capture_level,
                            prompt_text, prompt_digest, created_at)
                        values (:id, :mc, 'FULL', null, :dg, now())
                        """)
                .param("id", UUID.randomUUID()).param("mc", fullVoid)
                .param("dg", Digest.sha256Of("p1").value()).update())
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("ck_rca_model_input_capture");

        UUID digestWithText = mintModelCall("digest-text", Digest.sha256Of("p2").value());
        assertThatThrownBy(() -> jdbc.sql("""
                        insert into rca_model_input (id, model_call_id, capture_level,
                            prompt_text, prompt_digest, created_at)
                        values (:id, :mc, 'DIGEST_ONLY', '泄漏原文', :dg, now())
                        """)
                .param("id", UUID.randomUUID()).param("mc", digestWithText)
                .param("dg", Digest.sha256Of("p2").value()).update())
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("ck_rca_model_input_capture");
    }

    @Test
    @DisplayName("append-only 授权面：control_app 无 update/delete；publisher_app 零授")
    void appendOnlyGrantsDenyUpdateDeleteAndCrossRoleRead() {
        UUID modelCallId = mintModelCall("grants", Digest.sha256Of(PROMPT).value());
        fullCapture.capture(RcaModelInputCapture.buildRow(modelCallId,
                RcaModelInputCapture.Level.FULL, PROMPT, Digest.sha256Of(PROMPT).value()));

        // control_app：只有 select,insert —— update/delete 拒绝（42501）
        assertThatThrownBy(() -> jdbc.sql(
                        "update rca_model_input set prompt_text = '篡改' where id = :id")
                .param("id", modelCallId).update())
                .isInstanceOfSatisfying(org.springframework.dao.DataAccessException.class,
                        e -> assertThat(sqlStateOf(e)).isEqualTo("42501"));
        assertThatThrownBy(() -> jdbc.sql(
                        "delete from rca_model_input where id = :id")
                .param("id", modelCallId).update())
                .isInstanceOfSatisfying(org.springframework.dao.DataAccessException.class,
                        e -> assertThat(sqlStateOf(e)).isEqualTo("42501"));

        // publisher_app：全零 —— select 拒绝（42501）
        assertThatThrownBy(() -> publisherJdbc.sql(
                        "select prompt_digest from rca_model_input")
                .query((rs, i) -> rs.getString(1)).list())
                .isInstanceOfSatisfying(org.springframework.dao.DataAccessException.class,
                        e -> assertThat(sqlStateOf(e)).isEqualTo("42501"));
    }

    // ------------------------------------------------------------------ 夹具

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

    /** 开一行 PENDING 账（回放 join 的账本侧锚），返回 model_call id */
    private UUID mintModelCall(String tag, String promptDigest) {
        RcaRun run = mintRoutedRun(tag);
        UUID[] taskAttempt = mintTaskAttempt(run.id());
        UUID id = UUID.randomUUID();
        ledger.open(new RcaModelCallLedger.OpenRow(id, run.id(), taskAttempt[0],
                taskAttempt[1], 0, 1, 0, "primary", "1",
                Digest.sha256Of("role").hex(), promptDigest,
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
                "IT_INPUT"), InvestigationInputs.freezeAt(incident, now));
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
                .param("key", "INPUT_IT_" + taskId.toString().substring(0, 8))
                .update();
        jdbc.sql("""
                INSERT INTO rca_attempt (id, task_id, attempt_no, lease_epoch, worker_id,
                    status, started_at, finished_at)
                VALUES (:id, :task, 1, 0, 'input-it', 'SUCCEEDED', now(), now())
                """)
                .param("id", attemptId).param("task", taskId)
                .update();
        return new UUID[]{taskId, attemptId};
    }
}
