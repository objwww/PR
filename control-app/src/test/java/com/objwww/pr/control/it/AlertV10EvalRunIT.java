package com.objwww.pr.control.it;

import com.objwww.pr.control.alert.domain.model.TypedRootCause;
import com.objwww.pr.control.eval.domain.EvalCaseResult;
import com.objwww.pr.control.eval.domain.EvalRun;
import com.objwww.pr.control.eval.domain.EvalRunMetadata;
import com.objwww.pr.control.eval.domain.ScenarioMetrics;
import com.objwww.pr.control.infrastructure.persistence.PostgresEvalRunRepository;
import com.objwww.pr.shared.Digest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * M3-14 验收（真 PG）：V10 评测持久化——同 EvalRun 不可覆盖（主键禁重生 + 终态 CAS
 * 一次回填 + case 唯一键禁覆盖）、同 case 两轮两条独立记录、可复现元数据读回一致、
 * 迁移约束面（状态域/生命周期/判定形态）与授权矩阵（eval_app 写面 / 生产角色零权限）。
 *
 * <p>仓储一律走 {@code evalJdbc}（eval_app 真实角色）——eval-runner 的生产身份
 * （M3-15）；eval 两表对 control_app 全量 revoke，用 control 角色即失败。
 */
class AlertV10EvalRunIT extends PostgresITBase {

    private PostgresEvalRunRepository evalRuns;

    @BeforeEach
    void setUpRepositories() {
        evalRuns = new PostgresEvalRunRepository(evalJdbc);
    }

    private record Chain(UUID runId, UUID attemptId, UUID reportId) {
    }

    /** incident → run → task → attempt → STRUCTURE_VALIDATED 报告（DECIDABLE 评分对象的 FK 面） */
    private Chain seedChain() {
        UUID incidentId = UUID.randomUUID();
        UUID runId = UUID.randomUUID();
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
                    investigation_hash, created_at, updated_at)
                VALUES (:id, :inc, 0, 'INITIAL', 'SUCCEEDED', :hash, now(), now())
                """).param("id", runId).param("inc", incidentId)
                .param("hash", Digest.sha256Of("it-" + runId).value()).update();
        controlJdbc.sql("""
                INSERT INTO rca_task(id, run_id, task_key, state, priority,
                    available_at, ready_since, deadline_at, created_at, updated_at)
                VALUES (:id, :run, 'HOLMES_INVESTIGATE', 'READY', 100,
                    now(), now(), now(), now(), now())
                """).param("id", taskId).param("run", runId).update();
        controlJdbc.sql("""
                INSERT INTO rca_attempt(id, task_id, attempt_no, lease_epoch, worker_id, status, started_at)
                VALUES (:id, :task, 1, 0, 'it-worker', 'SUCCEEDED', now())
                """).param("id", attemptId).param("task", taskId).update();
        controlJdbc.sql("""
                INSERT INTO rca_report(id, run_id, attempt_id, schema_version, validation_status,
                    package_json, raw_text, usage_missing, created_at)
                VALUES (:id, :run, :attempt, 2, 'STRUCTURE_VALIDATED',
                        CAST('{"schema_version":2}' AS jsonb), 'raw', true, now())
                """).param("id", reportId).param("run", runId)
                .param("attempt", attemptId).update();
        return new Chain(runId, attemptId, reportId);
    }

    /** 十项元数据样本（含可空采样参数：temperature/top_p 有值、种子/上限为 null） */
    private EvalRunMetadata metadata() {
        return new EvalRunMetadata(1, "eval-ds-1", Digest.sha256Of("registry"), 1,
                "deepseek-v3", "am3-rca-v2", Digest.sha256Of("prompt"),
                Digest.sha256Of("tools"), new BigDecimal("0.7"), new BigDecimal("0.9"),
                null, null, null,
                "litellm/internal-v1#key-eval", Digest.sha256Of("rules"),
                "scenario-driver-v1");
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

    // ------------------------------------------------------------------ 仓储行为

    @Test
    @DisplayName("同 EvalRun 禁重生：同 id 二次 insertRunning 抛出（不可覆盖第一层）")
    void sameRunInsertRejected() {
        EvalRun run = runningRun();
        evalRuns.insertRunning(run);

        assertThatThrownBy(() -> evalRuns.insertRunning(run))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("禁止重生");
        assertThat(evalRuns.findById(run.id())).isPresent();
    }

    @Test
    @DisplayName("同 case 两轮两条独立记录；同轮重评被唯一键拒绝（返回 false 不覆盖）")
    void twoRoundsAreTwoRowsAndNoOverwrite() {
        EvalRun run = runningRun();
        evalRuns.insertRunning(run);

        assertThat(evalRuns.insertCaseResult(absentCase(run.id(), "S1", 1))).isTrue();
        assertThat(evalRuns.insertCaseResult(absentCase(run.id(), "S1", 1))).isFalse();
        assertThat(evalRuns.insertCaseResult(absentCase(run.id(), "S1", 2))).isTrue();

        List<EvalCaseResult> cases = evalRuns.findCasesByRunId(run.id());
        assertThat(cases).hasSize(2);
        assertThat(cases).extracting(EvalCaseResult::roundNo).containsExactly(1, 2);
        assertThat(cases).allSatisfy(c ->
                assertThat(c.verdict()).isEqualTo(ScenarioMetrics.ScoringVerdict.TIMEOUT_OR_ABSENT));
    }

    @Test
    @DisplayName("DECIDABLE 案例读回一致：根因三元组/症状码/TP-FP-FN/时延/静默罚逐项还原")
    void decidableCaseRoundTrip() {
        EvalRun run = runningRun();
        evalRuns.insertRunning(run);
        Chain chain = seedChain();

        EvalCaseResult decidable = new EvalCaseResult(UUID.randomUUID(), run.id(), "S3", 1,
                "state-machine-selected-v1", chain.runId(), chain.attemptId(), chain.reportId(),
                ScenarioMetrics.ScoringVerdict.DECIDABLE, true,
                new TypedRootCause("order-arena", "IDEMPOTENCY_BYPASS",
                        "DUPLICATE_CREATE_SAME_INTENT"),
                new TypedRootCause("order-arena", "IDEMPOTENCY_BYPASS",
                        "DUPLICATE_CREATE_SAME_INTENT"),
                List.of("ArenaDuplicateOrders"), List.of("ArenaDuplicateOrders"),
                1, 0, 0, 42_000L, false, null);
        assertThat(evalRuns.insertCaseResult(decidable)).isTrue();

        EvalCaseResult stored = evalRuns.findCasesByRunId(run.id()).getFirst();
        assertThat(stored.scoredAttemptId()).isEqualTo(chain.attemptId());
        assertThat(stored.scoredReportId()).isEqualTo(chain.reportId());
        assertThat(stored.rootCauseHit()).isTrue();
        assertThat(stored.expectedRootCause()).isEqualTo(decidable.expectedRootCause());
        assertThat(stored.actualRootCause()).isEqualTo(decidable.actualRootCause());
        assertThat(stored.expectedSymptomCodes()).containsExactly("ArenaDuplicateOrders");
        assertThat(stored.actualSymptomCodes()).containsExactly("ArenaDuplicateOrders");
        assertThat(stored.tpCount()).isEqualTo(1);
        assertThat(stored.fpCount()).isZero();
        assertThat(stored.fnCount()).isZero();
        assertThat(stored.latencyMs()).isEqualTo(42_000L);
        assertThat(stored.silencePenalty()).isFalse();
    }

    @Test
    @DisplayName("终态 CAS：SUCCEEDED 一次回填全套分子分母 + TP/FP/FN；二次终态化 0 行拒绝")
    void finalizeOnceThenRejects() {
        EvalRun run = runningRun();
        evalRuns.insertRunning(run);
        Chain chain = seedChain();
        evalRuns.insertCaseResult(absentCase(run.id(), "S1", 1));
        evalRuns.insertCaseResult(absentCase(run.id(), "S1", 2));

        EvalRun.SymptomCounts counts = new EvalRun.SymptomCounts(8, 1, 2);
        Digest reportDigest = Digest.sha256Of("baseline-report");
        assertThat(evalRuns.finalizeOnce(EvalRun.terminal(run.id(), metadata(),
                EvalRun.EvalRunState.SUCCEEDED, Instant.now(), Instant.now(),
                new ScenarioMetrics.Snapshot(10, 8, 6, 2, 0, 0,
                        0.8, 0.75, 0.6, 0.2),
                counts, reportDigest))).isTrue();

        EvalRun stored = evalRuns.findById(run.id()).orElseThrow();
        assertThat(stored.state()).isEqualTo(EvalRun.EvalRunState.SUCCEEDED);
        assertThat(stored.finishedAt()).isNotNull();
        assertThat(stored.summary().total()).isEqualTo(10);
        assertThat(stored.summary().decidable()).isEqualTo(8);
        assertThat(stored.summary().hits()).isEqualTo(6);
        assertThat(stored.summary().unresolved()).isEqualTo(2);
        assertThat(stored.summary().coverage()).isEqualTo(0.8);
        assertThat(stored.summary().conditionalAccuracy()).isEqualTo(0.75);
        assertThat(stored.summary().endToEndHitRate()).isEqualTo(0.6);
        assertThat(stored.summary().unresolvedRate()).isEqualTo(0.2);
        assertThat(stored.symptomCounts().truePositives()).isEqualTo(8);
        assertThat(stored.symptomCounts().falsePositives()).isEqualTo(1);
        assertThat(stored.symptomCounts().falseNegatives()).isEqualTo(2);
        assertThat(stored.baselineReportDigest()).isEqualTo(reportDigest);

        // CAS：终态行不可再改（重放/双 runner 收尾被拒）
        assertThat(evalRuns.finalizeOnce(EvalRun.terminal(run.id(), metadata(),
                EvalRun.EvalRunState.FAILED, Instant.now(), Instant.now(),
                null, null, null))).isFalse();

        // FAILED 允许无指标（中途夭折形态）
        EvalRun aborted = runningRun();
        evalRuns.insertRunning(aborted);
        assertThat(evalRuns.finalizeOnce(EvalRun.terminal(aborted.id(), metadata(),
                EvalRun.EvalRunState.FAILED, Instant.now(), Instant.now(),
                null, null, null))).isTrue();
    }

    // ------------------------------------------------------------------ 迁移契约与授权

    @Test
    @DisplayName("V10 契约：两表在场 + 十项元数据列齐；状态域/生命周期/判定形态约束生效")
    void v10ContractTablesColumnsAndChecks() {
        List<String> tables = adminJdbc.sql("""
                SELECT tablename FROM pg_tables WHERE schemaname = 'public' AND tablename IN (
                    'eval_run','eval_case_result')
                """).query(String.class).list();
        assertThat(tables).hasSize(2);

        List<String> runColumns = adminJdbc.sql("""
                SELECT column_name FROM information_schema.columns
                WHERE table_name = 'eval_run' AND column_name IN (
                    'dataset_version','config_digest','model','prompt_version','prompt_digest',
                    'tool_registry_digest','schema_version','temperature','top_p','max_tokens',
                    'requested_seed','effective_seed','provider_fingerprint','alert_rule_digest',
                    'scenario_driver_version','registry_digest','lexicon_version',
                    'tp_count','fp_count','fn_count','coverage','conditional_accuracy',
                    'end_to_end_hit_rate','unresolved_rate','baseline_report_digest')
                """).query(String.class).list();
        assertThat(runColumns).hasSize(25);

        EvalRun run = runningRun();
        evalRuns.insertRunning(run);

        // 状态域链外值被拒
        assertThat(chainContains(() -> adminJdbc.sql("""
                        INSERT INTO eval_run(id, schema_version, dataset_version, registry_digest,
                            lexicon_version, model, prompt_version, prompt_digest,
                            tool_registry_digest, provider_fingerprint, alert_rule_digest,
                            scenario_driver_version, config_digest, state, started_at)
                        VALUES (:id, 1, 'd', 'r', 1, 'm', 'p', 'pd', 'td', 'pf', 'ar', 'sd',
                                'cd', 'FLYING', now())
                        """).param("id", UUID.randomUUID()).update(),
                "ck_eval_run_state")).isTrue();

        // RUNNING 不许带 finished_at；SUCCEEDED 缺指标被生命周期约束拒
        assertThat(chainContains(() -> adminJdbc.sql(
                        "UPDATE eval_run SET finished_at = now() WHERE id = :id")
                .param("id", run.id()).update(),
                "ck_eval_run_lifecycle")).isTrue();
        assertThat(chainContains(() -> adminJdbc.sql("""
                        INSERT INTO eval_run(id, schema_version, dataset_version, registry_digest,
                            lexicon_version, model, prompt_version, prompt_digest,
                            tool_registry_digest, provider_fingerprint, alert_rule_digest,
                            scenario_driver_version, config_digest, state, started_at, finished_at)
                        VALUES (:id, 1, 'd', 'r', 1, 'm', 'p', 'pd', 'td', 'pf', 'ar', 'sd',
                                'cd', 'SUCCEEDED', now(), now())
                        """).param("id", UUID.randomUUID()).update(),
                "ck_eval_run_lifecycle")).isTrue();

        // 判定形态：DECIDABLE 缺报告被拒；UNRESOLVED 带 hit 被拒；缺席案例合法
        assertThat(chainContains(() -> adminJdbc.sql("""
                        INSERT INTO eval_case_result(id, eval_run_id, scenario_id, round_no,
                            selection_policy_version, verdict, root_cause_hit,
                            expected_root_cause, expected_symptom_codes)
                        VALUES (:id, :run, 'S1', 9, 'v1', 'DECIDABLE', false,
                                '{}'::jsonb, '["checkout"]'::jsonb)
                        """).param("id", UUID.randomUUID()).param("run", run.id()).update(),
                "ck_eval_case_verdict_shape")).isTrue();
        assertThat(chainContains(() -> adminJdbc.sql("""
                        INSERT INTO eval_case_result(id, eval_run_id, scenario_id, round_no,
                            selection_policy_version, verdict, root_cause_hit,
                            expected_root_cause, expected_symptom_codes)
                        VALUES (:id, :run, 'S1', 9, 'v1', 'UNRESOLVED', true,
                                '{}'::jsonb, '["checkout"]'::jsonb)
                        """).param("id", UUID.randomUUID()).param("run", run.id()).update(),
                "ck_eval_case_hit_only_decidable")).isTrue();
        assertThat(evalRuns.insertCaseResult(absentCase(run.id(), "S1", 9))).isTrue();
    }

    @Test
    @DisplayName("授权矩阵：eval_app 写面全通；eval_case_result 无 UPDATE/DELETE；生产角色零权限")
    void grantsFollowLeastPrivilegeOnRealRoles() {
        EvalRun run = runningRun();
        evalRuns.insertRunning(run);

        // eval_app：终态列级 UPDATE 放行（finalizeOnce 路径已被上方用例覆盖），DELETE 拒
        assertThat(chainContains(() -> evalJdbc.sql(
                        "DELETE FROM eval_run WHERE false").update(),
                "permission denied")).isTrue();
        // eval_case_result：insert-only——UPDATE/DELETE 权限面直接拒绝（评分记录落库即冻结）
        assertThat(chainContains(() -> evalJdbc.sql(
                        "UPDATE eval_case_result SET verdict = verdict WHERE false").update(),
                "permission denied")).isTrue();
        assertThat(chainContains(() -> evalJdbc.sql(
                        "DELETE FROM eval_case_result WHERE false").update(),
                "permission denied")).isTrue();

        // 生产面角色全部零权限（M3-15 评测/生产隔离）
        assertThat(chainContains(() -> controlJdbc.sql(
                        "SELECT count(*) FROM eval_run").query(Long.class).single(),
                "permission denied")).isTrue();
        assertThat(chainContains(() -> notifyJdbc.sql(
                        "SELECT count(*) FROM eval_case_result").query(Long.class).single(),
                "permission denied")).isTrue();
        assertThat(chainContains(() -> publisherJdbc.sql(
                        "SELECT count(*) FROM eval_run").query(Long.class).single(),
                "permission denied")).isTrue();
    }

    /** 断言辅助：执行应抛异常，且整条 cause 链文本包含预期片段 */
    private boolean chainContains(Runnable action, String fragment) {
        try {
            action.run();
        } catch (RuntimeException e) {
            StringBuilder chain = new StringBuilder();
            for (Throwable c = e; c != null; c = c.getCause()) {
                chain.append(c.getMessage()).append('\n');
            }
            return chain.toString().contains(fragment);
        }
        return false;
    }
}
