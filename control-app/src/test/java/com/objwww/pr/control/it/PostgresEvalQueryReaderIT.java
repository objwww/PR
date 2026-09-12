package com.objwww.pr.control.it;

import com.objwww.pr.control.alert.domain.identity.InvestigationInputs;
import com.objwww.pr.control.alert.domain.agent.RcaModelCallLedger;
import com.objwww.pr.control.alert.domain.model.Incident;
import com.objwww.pr.control.alert.domain.model.IncidentStatus;
import com.objwww.pr.control.alert.domain.model.RcaEngine;
import com.objwww.pr.control.alert.domain.model.RcaRun;
import com.objwww.pr.control.alert.domain.model.RcaRunRouting;
import com.objwww.pr.control.alert.domain.model.RcaRunState;
import com.objwww.pr.control.alert.domain.model.RunTrigger;
import com.objwww.pr.control.alert.domain.model.TypedRootCause;
import com.objwww.pr.control.domain.ai.ModelCallLedgerEntry;
import com.objwww.pr.control.eval.domain.EvalCaseResult;
import com.objwww.pr.control.eval.domain.EvalRun;
import com.objwww.pr.control.eval.domain.EvalRunMetadata;
import com.objwww.pr.control.eval.domain.ScenarioMetrics;
import com.objwww.pr.control.eval.domain.repository.EvalQueryReader;
import com.objwww.pr.control.eval.domain.repository.EvalQueryReader.EvalCasePage;
import com.objwww.pr.control.eval.domain.repository.EvalQueryReader.EvalRunRow;
import com.objwww.pr.control.infrastructure.persistence.PostgresEvalQueryReader;
import com.objwww.pr.control.infrastructure.persistence.PostgresEvalRunRepository;
import com.objwww.pr.control.infrastructure.persistence.PostgresIncidentRepository;
import com.objwww.pr.control.infrastructure.persistence.PostgresRcaRunRepository;
import com.objwww.pr.control.infrastructure.persistence.PostgresRunConfigEpochRepository;
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
    private PostgresIncidentRepository incidents;
    private PostgresRcaRunRepository rcaRuns;

    @BeforeEach
    void setUpRepositories() {
        evalRuns = new PostgresEvalRunRepository(evalJdbc);
        reader = new PostgresEvalQueryReader(controlJdbc);
        incidents = new PostgresIncidentRepository(controlJdbc);
        rcaRuns = new PostgresRcaRunRepository(controlJdbc,
                new PostgresRunConfigEpochRepository(controlJdbc));
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

    // ------------------------------------------------------------------ R6/EV-06 usage 投影

    @Test
    @DisplayName("R6/EU17：usage 投影只读 rca_run 身份链的已结算行；V5 域干扰行零污染")
    void usageProjectionReadsRcaChainOnlyAndIgnoresPrLedger() {
        // ① V5 域干扰行（PR review 链 + model_call_ledger）——同库同类调用，读面必须不见
        com.objwww.pr.control.infrastructure.persistence.PostgresModelCallLedgerRepository
                prLedger =
                new com.objwww.pr.control.infrastructure.persistence
                        .PostgresModelCallLedgerRepository(
                        new org.springframework.jdbc.core.JdbcTemplate(controlDataSource()));
        RepairSeed prSeed = seedRepairScope("usage-proj");
        UUID prStepId = UUID.randomUUID();
        UUID prWorkItemId = UUID.randomUUID();
        UUID prAttemptId = UUID.randomUUID();
        adminJdbc.sql("""
                INSERT INTO run_step(id,review_run_id,step_key,operation_id,step_type,state,
                    ordinal,timeout_seconds,created_at,updated_at)
                VALUES (:id,:run,'step-usage-proj',:op,'REVIEW','READY',1,600,now(),now())
                """).param("id", prStepId).param("run", prSeed.runId())
                .param("op", UUID.randomUUID()).update();
        adminJdbc.sql("""
                INSERT INTO work_item(id,review_run_id,step_id,work_type,state,available_at,
                    max_attempts,created_at,updated_at)
                VALUES (:id,:run,:step,'REVIEW','READY',now(),3,now(),now())
                """).param("id", prWorkItemId).param("run", prSeed.runId())
                .param("step", prStepId).update();
        adminJdbc.sql("""
                INSERT INTO step_attempt(id,step_id,work_item_id,attempt_no,lease_epoch,
                    worker_id,status,started_at)
                VALUES (:id,:step,:wi,1,1,'it-worker','STARTED',now())
                """).param("id", prAttemptId).param("step", prStepId)
                .param("wi", prWorkItemId).update();
        prLedger.insertStarted(ModelCallLedgerEntry.builder()
                .id(UUID.randomUUID()).invocationId(UUID.randomUUID()).callSeq(1)
                .reviewRunId(prSeed.runId()).runStepId(prStepId).attemptId(prAttemptId)
                .leaseEpoch(1).routeId("pr-route").routeRole("PRIMARY")
                .endpointScope("it-endpoint").quotaScope("it-quota")
                .requestedModel("pr-model").build());

        // ② eval run + 案例绑定 RCA run（身份链显式映射）
        EvalRun evalRun = runningRun();
        evalRuns.insertRunning(evalRun);

        // ③ RCA 链：incident → routed run → task/attempt → 三笔结算调用
        UUID rcaRunId = mintRcaRunWithCalls("usage-proj");

        evalRuns.insertCaseResult(new EvalCaseResult(UUID.randomUUID(), evalRun.id(),
                "S1", 1, "final-validated-report-v1", rcaRunId, null, null,
                ScenarioMetrics.ScoringVerdict.TIMEOUT_OR_ABSENT, false,
                new TypedRootCause("payment", "BUSINESS_ERROR_RATE", "PAYMENT_CHARGE_FAILURE"),
                null,
                List.of("checkout"), List.of("checkout"), 1, 1, 0, 5L, false, null));

        // ④ 投影：只含 rca_model_call 三行；V5 行零出现
        List<EvalQueryReader.UsageCallRow> rows = reader.listUsageCalls(evalRun.id());
        assertThat(rows).hasSize(3);
        assertThat(rows).allSatisfy(r -> assertThat(r.rcaRunId()).isEqualTo(rcaRunId));
        assertThat(rows).allSatisfy(r -> assertThat(r.evalRunId()).isEqualTo(evalRun.id()));
        assertThat(rows).extracting(EvalQueryReader.UsageCallRow::roleId)
                .containsOnly("primary");

        // jsonb 键面回归（R6 同族第二处，2026-09-12 195 首跑实证）：rca_model_call.usage
        // 是 V48 jsonb 列——裸列名 prompt_tokens 真 PG 直接 BadSqlGrammar，且该异常经
        // /error 错误派发被 anyRequest().denyAll() 翻成 403，把 500 真相埋进权限烟雾。
        assertThat(rows).extracting(EvalQueryReader.UsageCallRow::state)
                .containsExactly("SUCCESS", "SUCCESS", "FAILED");
        var priced = rows.stream().filter(r -> r.costMicros() != null).findFirst().orElseThrow();
        assertThat(priced.promptTokens()).isEqualTo(100);
        assertThat(priced.completionTokens()).isEqualTo(20);
        assertThat(priced.totalTokens()).isEqualTo(120);
        assertThat(priced.costMicros()).isEqualTo(1500L);
        assertThat(priced.pricingVersion()).isEqualTo("pv-it");
        assertThat(priced.currency()).isEqualTo("CNY");
        var unpriced = rows.stream().filter(r -> "unpriced".equals(r.pricingVersion()))
                .findFirst().orElseThrow();
        assertThat(unpriced.promptTokens()).isEqualTo(30);
        assertThat(unpriced.completionTokens()).isEqualTo(5);
        assertThat(unpriced.costMicros()).isNull();
        assertThat(unpriced.currency()).isNull();
        var failedRow = rows.stream().filter(r -> "FAILED".equals(r.state())).findFirst().orElseThrow();
        assertThat(failedRow.promptTokens()).as("FAILED 行 usage 恒空").isNull();
        assertThat(failedRow.costMicros()).isNull();
        assertThat(failedRow.usageMissing()).isFalse();

        // ⑤ 批量面同口径（列表接线走 IN 查询，禁 N+1）
        List<EvalQueryReader.UsageCallRow> batched =
                reader.listUsageCallsForRuns(List.of(evalRun.id(), UUID.randomUUID()));
        assertThat(batched).hasSize(3);
        assertThat(batched).allSatisfy(r -> assertThat(r.evalRunId()).isEqualTo(evalRun.id()));
        // 空集直返（不拼 IN ()）
        assertThat(reader.listUsageCallsForRuns(List.of())).isEmpty();
    }

    /** UsageReaderIT 同形：incident → routed run → task/attempt → 三笔结算调用
     * （1 priced SUCCESS + 1 unpriced SUCCESS + 1 FAILED），返回 rca_run id。 */
    private UUID mintRcaRunWithCalls(String tag) {
        UUID incidentId = UUID.randomUUID();
        incidents.insert(new Incident(incidentId,
                "alertname=HighErrorRate|service=" + tag, IncidentStatus.FIRING, 0,
                Instant.now(), Instant.now(), null, null, null, 0, 0, 0, null,
                Instant.now(), Instant.now(), Instant.now(), Instant.now()));
        UUID rcaRunId = UUID.randomUUID();
        Instant now = Instant.now();
        rcaRuns.insertRouted(new RcaRun(rcaRunId, incidentId, 0, RunTrigger.INITIAL,
                        RcaRunState.QUEUED, Digest.sha256Of("run-" + tag),
                        now.minusSeconds(240), now, null, null, null),
                new RcaRunRouting(RcaEngine.NATIVE, Digest.sha256Of(tag + "-bundle"),
                        "alertname=HighErrorRate|service=" + tag, 37, "IT_USAGE_PROJ"),
                InvestigationInputs.freezeAt(incidents.findById(incidentId).orElseThrow(), now));
        UUID taskId = UUID.randomUUID();
        UUID attemptId = UUID.randomUUID();
        controlJdbc.sql("""
                INSERT INTO rca_task (id, run_id, task_key, state,
                    available_at, ready_since, deadline_at, created_at, updated_at)
                VALUES (:id, :run, :key, 'DONE', now(), now(), now(), now(), now())
                """).param("id", taskId).param("run", rcaRunId)
                .param("key", "USAGE_PROJ_" + taskId.toString().substring(0, 8)).update();
        controlJdbc.sql("""
                INSERT INTO rca_attempt (id, task_id, attempt_no, lease_epoch, worker_id,
                    status, started_at, finished_at)
                VALUES (:id, :task, 1, 0, 'usage-proj-it', 'SUCCEEDED', now(), now())
                """).param("id", attemptId).param("task", taskId).update();

        RcaModelCallLedger ledger = new com.objwww.pr.control.infrastructure.persistence
                .PostgresRcaModelCallLedger(controlJdbc, new com.fasterxml.jackson.databind
                        .ObjectMapper(), controlTx);
        // ① priced SUCCESS
        UUID priced = UUID.randomUUID();
        ledger.open(call(priced, rcaRunId, taskId, attemptId, 0));
        ledger.succeed(priced, new RcaModelCallLedger.UsageOutcome(100, 20, 120, false,
                1500L, "pv-it", "CNY", "req-1", "route-a", "demo-model", 42, null));
        // ② unpriced SUCCESS（R4：有 usage 无价目显式态）
        UUID unpriced = UUID.randomUUID();
        ledger.open(call(unpriced, rcaRunId, taskId, attemptId, 1));
        ledger.succeed(unpriced, new RcaModelCallLedger.UsageOutcome(30, 5, 35, false,
                null, "unpriced", null, "req-2", "route-a", "demo-model", 30, null));
        // ③ FAILED（无 usage 无 cost）
        UUID failed = UUID.randomUUID();
        ledger.open(call(failed, rcaRunId, taskId, attemptId, 2));
        ledger.fail(failed, "UPSTREAM_5XX");
        return rcaRunId;
    }

    private static RcaModelCallLedger.OpenRow call(UUID id, UUID runId, UUID taskId,
            UUID attemptId, long actionSeq) {
        return new RcaModelCallLedger.OpenRow(id, runId, taskId, attemptId, actionSeq, 1,
                0, "primary", "1", Digest.sha256Of("role").hex(),
                Digest.sha256Of("prompt-" + actionSeq).hex(), null, null, null, null, 0);
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
