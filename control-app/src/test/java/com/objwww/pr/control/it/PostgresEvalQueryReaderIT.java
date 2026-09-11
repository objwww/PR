package com.objwww.pr.control.it;

import com.objwww.pr.control.alert.domain.model.TypedRootCause;
import com.objwww.pr.control.eval.domain.EvalCaseResult;
import com.objwww.pr.control.eval.domain.EvalRun;
import com.objwww.pr.control.eval.domain.EvalRunMetadata;
import com.objwww.pr.control.eval.domain.ScenarioMetrics;
import com.objwww.pr.control.eval.domain.repository.EvalQueryReader;
import com.objwww.pr.control.eval.domain.repository.EvalQueryReader.EvalCasePage;
import com.objwww.pr.control.eval.domain.repository.EvalQueryReader.EvalRunRow;
import com.objwww.pr.control.infrastructure.persistence.PostgresEvalQueryReader;
import com.objwww.pr.control.infrastructure.persistence.PostgresEvalRunRepository;
import com.objwww.pr.shared.Digest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * EV-03 验收（真 PG）：V80 评测投影契约——runs 投影的名称/模式/计数/阶段分面字段
 * 来源真实性与 null 语义、eval_phase_event 最新事件投影、lastProgressAt 与
 * stageEnteredAt 是不同时间戳（EU11/EU12 读面）、caseExecutionId 稳定身份与
 * rcaRunId/scoredReportId 关联链（§5.1/RV02）、eval_app 写面/control_app 只读面
 * 授权矩阵。
 *
 * <p>写面一律走 {@code evalJdbc}（eval_app 真实角色，M3-15）；读面走
 * {@link PostgresEvalQueryReader}（control_app，V45/V80 只授 SELECT）。
 * created_at 拨时间归 admin 测试动作（拨时钟不是业务写入）。
 */
class PostgresEvalQueryReaderIT extends PostgresITBase {

    private PostgresEvalRunRepository evalRuns;
    private PostgresEvalQueryReader reader;

    @BeforeEach
    void setUpRepositories() {
        evalRuns = new PostgresEvalRunRepository(evalJdbc);
        reader = new PostgresEvalQueryReader(controlJdbc);
    }

    private EvalRunMetadata metadata() {
        return new EvalRunMetadata(1, "eval-ds-1", Digest.sha256Of("registry"), 1,
                "deepseek-v3", "am3-rca-v2", Digest.sha256Of("prompt"),
                Digest.sha256Of("tools"), new BigDecimal("0.7"), new BigDecimal("0.9"),
                null, null, null,
                "litellm/internal-v1#key-eval", Digest.sha256Of("rules"),
                "scenario-driver-v1", "grader-test-v1");
    }

    private EvalRun runningRun() {
        return EvalRun.running(UUID.randomUUID(), metadata(), Instant.now());
    }

    private EvalCaseResult absentCase(UUID evalRunId, String scenarioId, int round) {
        return new EvalCaseResult(UUID.randomUUID(), evalRunId, scenarioId, round,
                "state-machine-selected-v1", null, null, null,
                ScenarioMetrics.ScoringVerdict.TIMEOUT_OR_ABSENT, false,
                new TypedRootCause("payment", "BUSINESS_ERROR_RATE", "PAYMENT_CHARGE_FAILURE"),
                null, List.of("checkout"), null, 0, 0, 1, null, false, null);
    }

    private void insertPhaseEvent(UUID evalRunId, String phase, Instant enteredAt) {
        evalJdbc.sql("""
                INSERT INTO eval_phase_event(id, eval_run_id, phase, entered_at, worker_id)
                VALUES (:id, :run, :phase, :at, 'it-worker')
                """).param("id", UUID.randomUUID())
                .param("run", evalRunId)
                .param("phase", phase)
                .param("at", Timestamp.from(enteredAt)).update();
    }

    // ------------------------------------------------------------------ EU12 读面：无采集 = 未知

    @Test
    @DisplayName("RUNNING 且无阶段事件/无案例：phase/lastProgress/计数全 null 或 0，不拿残留当在飞")
    void runningRunWithoutEventsProjectsHonestNulls() {
        EvalRun run = runningRun();
        evalRuns.insertRunning(run);

        EvalRunRow row = reader.findRun(run.id()).orElseThrow();

        assertThat(row.state()).isEqualTo("RUNNING");
        assertThat(row.phase()).isNull();
        assertThat(row.phaseEnteredAt()).isNull();
        assertThat(row.lastProgressAt()).isNull();
        assertThat(row.caseCount()).isZero();
        assertThat(row.totalScenarios()).isNull();
        assertThat(row.decidableCount()).isNull();
        assertThat(row.coverage()).isNull();
        assertThat(row.displayName()).isNull();
        assertThat(row.mode()).isNull();
    }

    // ------------------------------------------------------------------ EU11 读面：阶段/进展/进入时刻分离

    @Test
    @DisplayName("阶段取最新事件；lastProgressAt = 最新案例落档时刻，与 stageEnteredAt 是不同时间戳")
    void phaseAndProgressAreDistinctRealTimestamps() {
        EvalRun run = runningRun();
        evalRuns.insertRunning(run);
        Instant t1 = Instant.parse("2026-09-10T01:00:00Z");
        Instant t2 = Instant.parse("2026-09-10T01:05:00Z");
        Instant caseAt = Instant.parse("2026-09-10T01:02:03Z");
        // 乱序落事件：投影必须按 entered_at 取最新，不按插入序
        insertPhaseEvent(run.id(), "INJECTING", t1);
        insertPhaseEvent(run.id(), "AWAITING_RCA", t2);
        EvalCaseResult c = absentCase(run.id(), "S1", 1);
        evalRuns.insertCaseResult(c);
        adminJdbc.sql("UPDATE eval_case_result SET created_at = :at WHERE id = :id")
                .param("at", Timestamp.from(caseAt)).param("id", c.id()).update();

        EvalRunRow row = reader.findRun(run.id()).orElseThrow();

        assertThat(row.phase()).isEqualTo("AWAITING_RCA");
        assertThat(row.phaseEnteredAt()).isEqualTo(t2);
        assertThat(row.lastProgressAt()).isEqualTo(caseAt);
        assertThat(row.lastProgressAt()).isNotEqualTo(row.phaseEnteredAt());
        assertThat(row.caseCount()).isEqualTo(1);
    }

    // ------------------------------------------------------------------ EU16/EU22 读面：计数只来自 eval 域真实列

    @Test
    @DisplayName("终态 run：分子分母来自终态回填列；caseCount 只计 eval_case_result 行")
    void terminalRunCountsAndCaseCountFromPg() {
        EvalRun run = runningRun();
        evalRuns.insertRunning(run);
        evalRuns.insertCaseResult(absentCase(run.id(), "S1", 1));
        evalRuns.insertCaseResult(absentCase(run.id(), "S1", 2));
        assertThat(evalRuns.finalizeOnce(EvalRun.terminal(run.id(), metadata(),
                EvalRun.EvalRunState.SUCCEEDED, Instant.now(), Instant.now(),
                new ScenarioMetrics.Snapshot(10, 8, 6, 2, 0, 0,
                        0.8, 0.75, 0.6, 0.2),
                new EvalRun.SymptomCounts(8, 1, 2),
                Digest.sha256Of("baseline-report")))).isTrue();

        EvalRunRow row = reader.findRun(run.id()).orElseThrow();

        assertThat(row.state()).isEqualTo("SUCCEEDED");
        assertThat(row.totalScenarios()).isEqualTo(10);
        assertThat(row.decidableCount()).isEqualTo(8);
        assertThat(row.hitCount()).isEqualTo(6);
        assertThat(row.unresolvedCount()).isEqualTo(2);
        assertThat(row.caseCount()).isEqualTo(2);
        assertThat(row.lastProgressAt()).isNotNull();
        // 列表与详情同口径
        EvalQueryReader.EvalRunPage page = reader.listRuns(null, null, 50);
        assertThat(page.items()).extracting(EvalRunRow::runId).contains(run.id());
        EvalRunRow listed = page.items().stream()
                .filter(r -> r.runId().equals(run.id())).findFirst().orElseThrow();
        assertThat(listed.caseCount()).isEqualTo(2);
        assertThat(listed.totalScenarios()).isEqualTo(10);
    }

    // ------------------------------------------------------------------ §5.1/RV02：案例执行身份与关联链

    @Test
    @DisplayName("cases 投影携带 caseExecutionId（稳定 uuid）与 rcaRunId/scoredReportId；缺席案例关联链 null")
    void casesExposeStableExecutionIdentityAndLinks() {
        EvalRun run = runningRun();
        evalRuns.insertRunning(run);
        // DECIDABLE 需要 rca 链 FK：incident → rca_run → task → attempt → report
        UUID incidentId = UUID.randomUUID();
        UUID rcaRunId = UUID.randomUUID();
        UUID taskId = UUID.randomUUID();
        UUID attemptId = UUID.randomUUID();
        UUID reportId = UUID.randomUUID();
        controlJdbc.sql("""
                INSERT INTO incident(id, incident_key, status, generation, episode_started_at,
                    first_seen_at, last_event_at, created_at, updated_at)
                VALUES (:id, :key, 'FIRING', 0, now(), now(), now(), now(), now())
                """).param("id", incidentId)
                .param("key", "alertname=HighErrorRate|service=it-" + incidentId).update();
        controlJdbc.sql("""
                INSERT INTO rca_run(id, incident_id, generation, trigger_kind, state,
                    investigation_hash, created_at, updated_at, started_at, finished_at)
                VALUES (:id, :inc, 0, 'INITIAL', 'SUCCEEDED', :hash, now(), now(), now(), now())
                """).param("id", rcaRunId).param("inc", incidentId)
                .param("hash", Digest.sha256Of("it-" + rcaRunId).value()).update();
        controlJdbc.sql("""
                INSERT INTO rca_task(id, run_id, task_key, state, priority,
                    available_at, ready_since, deadline_at, created_at, updated_at)
                VALUES (:id, :run, 'HOLMES_INVESTIGATE', 'READY', 100,
                    now(), now(), now(), now(), now())
                """).param("id", taskId).param("run", rcaRunId).update();
        controlJdbc.sql("""
                INSERT INTO rca_attempt(id, task_id, attempt_no, lease_epoch, worker_id, status, started_at)
                VALUES (:id, :task, 1, 0, 'it-worker', 'SUCCEEDED', now())
                """).param("id", attemptId).param("task", taskId).update();
        controlJdbc.sql("""
                INSERT INTO rca_report(id, run_id, attempt_id, schema_version, validation_status,
                    package_json, raw_text, usage_missing, created_at)
                VALUES (:id, :run, :attempt, 2, 'STRUCTURE_VALIDATED',
                        CAST('{"schema_version":2}' AS jsonb), 'raw', true, now())
                """).param("id", reportId).param("run", rcaRunId)
                .param("attempt", attemptId).update();

        EvalCaseResult decidable = new EvalCaseResult(UUID.randomUUID(), run.id(), "S1", 1,
                "state-machine-selected-v1", rcaRunId, attemptId, reportId,
                ScenarioMetrics.ScoringVerdict.DECIDABLE, true,
                new TypedRootCause("order-arena", "IDEMPOTENCY_BYPASS",
                        "DUPLICATE_CREATE_SAME_INTENT"),
                new TypedRootCause("order-arena", "IDEMPOTENCY_BYPASS",
                        "DUPLICATE_CREATE_SAME_INTENT"),
                List.of("ArenaDuplicateOrders"), List.of("ArenaDuplicateOrders"),
                1, 0, 0, 42_000L, false, null);
        evalRuns.insertCaseResult(decidable);
        EvalCaseResult absent = absentCase(run.id(), "S2", 1);
        evalRuns.insertCaseResult(absent);

        EvalCasePage page = reader.listCases(run.id(), null, null, null, 50);

        assertThat(page.items()).hasSize(2);
        var first = page.items().get(0);
        assertThat(first.caseExecutionId()).isEqualTo(decidable.id());
        assertThat(first.rcaRunId()).isEqualTo(rcaRunId);
        assertThat(first.scoredReportId()).isEqualTo(reportId);
        var second = page.items().get(1);
        assertThat(second.caseExecutionId()).isEqualTo(absent.id());
        assertThat(second.rcaRunId()).isNull();
        assertThat(second.scoredReportId()).isNull();
    }

    // ------------------------------------------------------------------ EU09 契约准备：名称/模式列（写面归 EV-04）

    @Test
    @DisplayName("eval_app 可回填 display_name/mode 并被投影；非法模式被 CHECK 拒绝")
    void displayNameAndModeWritableByEvalAppAndProjected() {
        EvalRun run = runningRun();
        evalRuns.insertRunning(run);

        evalJdbc.sql("UPDATE eval_run SET display_name = :name, mode = :mode WHERE id = :id")
                .param("name", "rca100 回归基线")
                .param("mode", "E")
                .param("id", run.id()).update();

        EvalRunRow row = reader.findRun(run.id()).orElseThrow();
        assertThat(row.displayName()).isEqualTo("rca100 回归基线");
        assertThat(row.mode()).isEqualTo("E");

        assertThatThrownBy(() -> evalJdbc.sql(
                        "UPDATE eval_run SET mode = 'X' WHERE id = :id")
                        .param("id", run.id()).update())
                .hasMessageContaining("ck_eval_run_mode");
    }

    // ------------------------------------------------------------------ 授权矩阵（V10 同律）

    @Test
    @DisplayName("publisher_app 对 eval_phase_event 零权限；control_app 只读（SELECT 通、INSERT 拒）")
    void phaseEventGrantMatrix() {
        EvalRun run = runningRun();
        evalRuns.insertRunning(run);
        insertPhaseEvent(run.id(), "PREPARING", Instant.now());

        assertThatThrownBy(() -> publisherJdbc.sql(
                        "SELECT count(*) FROM eval_phase_event").query(Long.class).single())
                .hasStackTraceContaining("permission denied");
        assertThatThrownBy(() -> controlJdbc.sql("""
                        INSERT INTO eval_phase_event(id, eval_run_id, phase, entered_at)
                        VALUES (:id, :run, 'SCORING', now())
                        """).param("id", UUID.randomUUID()).param("run", run.id()).update())
                .hasStackTraceContaining("permission denied");
    }
}
