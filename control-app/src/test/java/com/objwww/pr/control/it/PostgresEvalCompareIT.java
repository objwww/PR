package com.objwww.pr.control.it;

import com.objwww.pr.control.eval.domain.EvalCaseResult;
import com.objwww.pr.control.eval.domain.EvalRun;
import com.objwww.pr.control.eval.domain.EvalRunMetadata;
import com.objwww.pr.control.eval.domain.ScenarioMetrics;
import com.objwww.pr.control.alert.domain.model.TypedRootCause;
import com.objwww.pr.control.eval.domain.model.EvalComparisonRecord;
import com.objwww.pr.control.eval.domain.repository.EvalQueryReader.CompareCaseRow;
import com.objwww.pr.control.eval.domain.repository.EvalQueryReader.CompareRunMeta;
import com.objwww.pr.control.infrastructure.persistence.PostgresEvalComparisonRepository;
import com.objwww.pr.control.infrastructure.persistence.PostgresEvalQueryReader;
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
 * EV-07 验收（真 PG）：对比元数据投影（十项可复现元数据直读）、对比案例投影
 * （case_version 身份列精确键横向解析——无匹配/歧义/HOLDOUT RLS 不可见三路 null）、
 * eval_comparison 落档 insert-only（同 id 二次插入违约）与同对最新落档投影、
 * runs 投影的 comparisonGateOutcome 子查询（qualityVerdict 分面源）、授权矩阵
 * （eval_app/publisher_app 对 eval_comparison 零权限；control_app 零 update/delete）。
 *
 * <p>本机无 Docker → Testcontainers 整类跳过（NOT_RUN）；真 PG 环境跑 Flyway 全迁移。
 */
class PostgresEvalCompareIT extends PostgresITBase {

    private PostgresEvalRunRepository evalRuns;
    private PostgresEvalQueryReader reader;
    private PostgresEvalComparisonRepository comparisons;

    @BeforeEach
    void setUpRepositories() {
        evalRuns = new PostgresEvalRunRepository(evalJdbc);
        reader = new PostgresEvalQueryReader(controlJdbc);
        comparisons = new PostgresEvalComparisonRepository(controlJdbc);
    }

    private EvalRunMetadata metadata() {
        return new EvalRunMetadata(1, "rca100-v1.1", Digest.sha256Of("registry"), 1,
                "deepseek-v3", "am3-rca-v2", Digest.sha256Of("prompt"),
                Digest.sha256Of("tools"), new BigDecimal("0.7"), new BigDecimal("0.9"),
                null, null, null,
                "litellm/internal-v1#key-eval", Digest.sha256Of("rules"),
                "scenario-driver-v1");
    }

    private EvalRun runningRun() {
        return EvalRun.running(UUID.randomUUID(), metadata(), Instant.now());
    }

    /** incident → rca_run → task → attempt → report 链（control_app 写面；DECIDABLE 案例的
     *  ck_eval_case_verdict_shape 依赖） */
    private record RcaChain(UUID rcaRunId, UUID attemptId, UUID reportId) {
    }

    private RcaChain insertRcaChain() {
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
                INSERT INTO rca_attempt(id, task_id, attempt_no, lease_epoch, worker_id,
                    status, started_at)
                VALUES (:id, :task, 1, 0, 'it-worker', 'SUCCEEDED', now())
                """).param("id", attemptId).param("task", taskId).update();
        controlJdbc.sql("""
                INSERT INTO rca_report(id, run_id, attempt_id, schema_version,
                    validation_status, package_json, raw_text, model, usage_missing, created_at)
                VALUES (:id, :run, :attempt, 2, 'STRUCTURE_VALIDATED',
                        CAST('{"schema_version":2,"summary":"s","impact":"i",'
                            || '"remediation":"r","evidence":[],"references":[],"claims":[]}'
                            AS jsonb), 'raw', 'holmes-1.0', true, now())
                """).param("id", reportId).param("run", rcaRunId)
                .param("attempt", attemptId).update();
        return new RcaChain(rcaRunId, attemptId, reportId);
    }

    /** hit=true → DECIDABLE + 真 rca 链（判定形态与评分对象一致，V10 CHECK 同律）；
     *  hit=false → TIMEOUT_OR_ABSENT 无链 */
    private EvalCaseResult caseOf(UUID evalRunId, String scenario, int round,
                                  boolean hit) {
        RcaChain chain = hit ? insertRcaChain() : null;
        return new EvalCaseResult(UUID.randomUUID(), evalRunId, scenario, round,
                "state-machine-selected-v1",
                chain == null ? null : chain.rcaRunId(),
                chain == null ? null : chain.attemptId(),
                chain == null ? null : chain.reportId(),
                hit ? ScenarioMetrics.ScoringVerdict.DECIDABLE
                        : ScenarioMetrics.ScoringVerdict.TIMEOUT_OR_ABSENT,
                hit,
                new TypedRootCause("redis", "OOM", "eviction"), null,
                List.of("RedisDown"), null, 0, 0, 1, null, false, null);
    }

    private UUID insertDataset(String name, String version) {
        UUID id = UUID.randomUUID();
        evalJdbc.sql("""
                INSERT INTO dataset_version(id, source, name, version, source_uri, license,
                    access_class, content_digest, adapter_version, imported_at,
                    source_class, partition_class, scenario_family_digest)
                VALUES (:id, 'rca100', :name, :version, 's3://x', 'MIT', 'internal',
                    :digest, 'adapter-v1', now(), 'PUBLIC_BENCHMARK', 'VALIDATION', :fam)
                """).param("id", id).param("name", name).param("version", version)
                .param("digest", Digest.sha256Of("ds-" + id).value())
                .param("fam", Digest.sha256Of("fam-" + id).value()).update();
        return id;
    }

    private void insertCaseVersion(UUID datasetId, String caseKey, String family) {
        evalJdbc.sql("""
                INSERT INTO case_version(id, dataset_version_id, case_key, scenario_family_id,
                    valid_from, content_digest, payload, partition_class)
                VALUES (:id, :ds, :key, :family, now(), :digest,
                    CAST('{"caseKey":"x"}' AS jsonb), 'VALIDATION')
                """).param("id", UUID.randomUUID()).param("ds", datasetId)
                .param("key", caseKey).param("family", family)
                .param("digest", Digest.sha256Of("case-" + caseKey + datasetId).value())
                .update();
    }

    // ------------------------------------------------------------------ 投影正确性

    @Test
    @DisplayName("对比元数据：十项可复现元数据直读；未知 id → empty")
    void compareMetaProjectsReproducibilityColumns() {
        EvalRun run = runningRun();
        evalRuns.insertRunning(run);

        CompareRunMeta meta = reader.findCompareMeta(run.id()).orElseThrow();

        assertThat(meta.datasetVersion()).isEqualTo("rca100-v1.1");
        assertThat(meta.registryDigest()).isEqualTo(Digest.sha256Of("registry").value());
        assertThat(meta.alertRuleDigest()).isEqualTo(Digest.sha256Of("rules").value());
        assertThat(meta.lexiconVersion()).isEqualTo(1);
        assertThat(meta.scenarioDriverVersion()).isEqualTo("scenario-driver-v1");
        assertThat(meta.model()).isEqualTo("deepseek-v3");
        assertThat(meta.state()).isEqualTo("RUNNING");
        assertThat(reader.findCompareMeta(UUID.randomUUID())).isEmpty();
    }

    @Test
    @DisplayName("对比案例投影：身份列精确键解析（命中/无匹配/歧义/HOLDOUT 四路）")
    void compareCasesProjectIdentityColumns() {
        UUID ds = insertDataset("rca100", "rca100-v1.1");
        insertCaseVersion(ds, "S1", "redis-oom");
        EvalRun run = runningRun();
        evalRuns.insertRunning(run);
        // S1 = 精确键命中；S2 = 无匹配（case_version 无此行）→ 身份列 null
        EvalCaseResult hit = caseOf(run.id(), "S1", 1, true);
        EvalCaseResult unmatched = caseOf(run.id(), "S2", 1, false);
        evalRuns.insertCaseResult(hit);
        evalRuns.insertCaseResult(unmatched);

        List<CompareCaseRow> rows = reader.listCasesForCompare(run.id(), 100);

        assertThat(rows).hasSize(2);
        CompareCaseRow s1 = rows.get(0);
        assertThat(s1.scenarioId()).isEqualTo("S1");
        assertThat(s1.contentDigest()).isNotNull();
        assertThat(s1.scenarioFamilyId()).isEqualTo("redis-oom");
        CompareCaseRow s2 = rows.get(1);
        assertThat(s2.contentDigest()).isNull();
        assertThat(s2.scenarioFamilyId()).isNull();

        // HOLDOUT 行 RLS 不可见 → 身份列 null（同版本同键但 HOLDOUT 分区，
        // control_app 策略面与 eval_app 同构 partition_class <> 'HOLDOUT'）
        UUID holdoutDs = insertDataset("rca100-holdout", "rca100-v1.1");
        evalJdbc.sql("""
                INSERT INTO case_version(id, dataset_version_id, case_key, scenario_family_id,
                    valid_from, content_digest, payload, partition_class)
                VALUES (:id, :ds, 'S2', 'secret', now(), :digest,
                    CAST('{"caseKey":"x"}' AS jsonb), 'HOLDOUT')
                """).param("id", UUID.randomUUID()).param("ds", holdoutDs)
                .param("digest", Digest.sha256Of("h").value()).update();
        assertThat(reader.listCasesForCompare(run.id(), 100).get(1).contentDigest()).isNull();

        // 歧义：同 (version, case_key) 两行 → 身份列 null（不取"最新"冒充）
        UUID dup = insertDataset("rca100-dup", "rca100-v1.1");
        insertCaseVersion(dup, "S1", "redis-oom-dup");
        assertThat(reader.listCasesForCompare(run.id(), 100).get(0).contentDigest()).isNull();
    }

    // ------------------------------------------------------------------ 落档与最新落档投影

    private EvalComparisonRecord recordOf(UUID baseline, UUID candidate, String outcome,
                                          List<String> reasons) {
        return new EvalComparisonRecord(UUID.randomUUID(), baseline, candidate, true,
                "[]", 10, 0, 0, 0, 10, null, outcome, reasons, "eval-compare-gate-v1",
                "it-actor", Instant.now());
    }

    @Test
    @DisplayName("eval_comparison：insert-only（同 id 二次插入违约）+ 同对最新落档投影")
    void comparisonInsertOnlyAndLatestByPair() {
        EvalRun baseline = runningRun();
        EvalRun candidate = runningRun();
        evalRuns.insertRunning(baseline);
        evalRuns.insertRunning(candidate);

        EvalComparisonRecord first = recordOf(baseline.id(), candidate.id(), "PASS", List.of());
        comparisons.insert(first);
        assertThatThrownBy(() -> comparisons.insert(first))
                .hasMessageContaining("eval_comparison");

        EvalComparisonRecord second = recordOf(baseline.id(), candidate.id(), "FAIL",
                List.of("REGRESSION_RATE_EXCEEDED"));
        comparisons.insert(second);

        // 同对生效面 = 最新落档；反向对/未知对 → empty
        assertThat(comparisons.findLatestByPair(baseline.id(), candidate.id()).orElseThrow()
                .id()).isEqualTo(second.id());
        assertThat(comparisons.findLatestByPair(candidate.id(), baseline.id())).isEmpty();
        assertThat(comparisons.findLatestByPair(baseline.id(), UUID.randomUUID())).isEmpty();
    }

    @Test
    @DisplayName("runs 投影：comparisonGateOutcome = 本 run 作为候选的最新落档门结论")
    void runProjectionCarriesLatestComparisonGateOutcome() {
        EvalRun baseline = runningRun();
        EvalRun candidate = runningRun();
        evalRuns.insertRunning(baseline);
        evalRuns.insertRunning(candidate);

        // 无落档 → null
        assertThat(reader.findRun(candidate.id()).orElseThrow().comparisonGateOutcome())
                .isNull();

        comparisons.insert(recordOf(baseline.id(), candidate.id(), "PASS", List.of()));
        assertThat(reader.findRun(candidate.id()).orElseThrow().comparisonGateOutcome())
                .isEqualTo("PASS");
        // baseline 侧不受影响（生效面只认 candidate_run_id）
        assertThat(reader.findRun(baseline.id()).orElseThrow().comparisonGateOutcome())
                .isNull();

        comparisons.insert(recordOf(baseline.id(), candidate.id(), "FAIL",
                List.of("REGRESSION_RATE_EXCEEDED")));
        assertThat(reader.findRun(candidate.id()).orElseThrow().comparisonGateOutcome())
                .isEqualTo("FAIL");
    }

    // ------------------------------------------------------------------ 授权矩阵

    @Test
    @DisplayName("授权矩阵：eval_app/publisher_app 对 eval_comparison 零权限；control_app 零 update/delete")
    void grantMatrixKeepsComparisonFaceMinimal() {
        EvalRun baseline = runningRun();
        EvalRun candidate = runningRun();
        evalRuns.insertRunning(baseline);
        evalRuns.insertRunning(candidate);
        comparisons.insert(recordOf(baseline.id(), candidate.id(), "PASS", List.of()));

        assertThatThrownBy(() -> evalJdbc.sql("SELECT count(*) FROM eval_comparison")
                .query(Long.class).single()).isInstanceOf(Exception.class);
        assertThatThrownBy(() -> publisherJdbc.sql("SELECT count(*) FROM eval_comparison")
                .query(Long.class).single()).isInstanceOf(Exception.class);
        assertThatThrownBy(() -> controlJdbc.sql(
                        "UPDATE eval_comparison SET gate_outcome = 'FAIL'")
                .update()).isInstanceOf(Exception.class);
        assertThatThrownBy(() -> controlJdbc.sql("DELETE FROM eval_comparison")
                .update()).isInstanceOf(Exception.class);
    }
}
