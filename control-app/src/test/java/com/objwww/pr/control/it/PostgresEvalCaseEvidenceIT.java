package com.objwww.pr.control.it;

import com.objwww.pr.control.alert.domain.model.TypedRootCause;
import com.objwww.pr.control.eval.domain.EvalCaseResult;
import com.objwww.pr.control.eval.domain.EvalRun;
import com.objwww.pr.control.eval.domain.EvalRunMetadata;
import com.objwww.pr.control.eval.domain.ScenarioMetrics;
import com.objwww.pr.control.eval.domain.repository.EvalQueryReader.CaseEvidenceRefRow;
import com.objwww.pr.control.eval.domain.repository.EvalQueryReader.CaseIdentityRow;
import com.objwww.pr.control.eval.domain.repository.EvalQueryReader.CaseLogEvidenceRow;
import com.objwww.pr.control.eval.domain.repository.EvalQueryReader.EvalCaseDetailRow;
import com.objwww.pr.control.eval.domain.repository.EvalQueryReader.EvidenceMetaRow;
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
 * EV-05 验收（真 PG）：案例详情投影（双键定位 + 关联链直读 + package 原文上抛）、
 * 场景身份精确键解析（含 HOLDOUT RLS 不可见与归属歧义两路 empty）、Run 证据引用
 * 扁平行的 run 范围解析（跨 run 引用不解析）、证据元数据白名单双约束、
 * logs.query 冻结证据投影、授权矩阵（eval_app 对 rca_evidence 零权限、
 * publisher_app 对 eval_case_result 零权限）。
 *
 * <p>本机无 Docker → Testcontainers 整类跳过（NOT_RUN）；真 PG 环境跑Flyway 全迁移。
 */
class PostgresEvalCaseEvidenceIT extends PostgresITBase {

    private PostgresEvalRunRepository evalRuns;
    private PostgresEvalQueryReader reader;

    @BeforeEach
    void setUpRepositories() {
        evalRuns = new PostgresEvalRunRepository(evalJdbc);
        reader = new PostgresEvalQueryReader(controlJdbc);
    }

    private EvalRunMetadata metadata() {
        return new EvalRunMetadata(1, "rca100-v1.1", Digest.sha256Of("registry"), 1,
                "deepseek-v3", "am3-rca-v2", Digest.sha256Of("prompt"),
                Digest.sha256Of("tools"), new BigDecimal("0.7"), new BigDecimal("0.9"),
                null, null, null,
                "litellm/internal-v1#key-eval", Digest.sha256Of("rules"),
                "scenario-driver-v1", "grader-test-v1");
    }

    private EvalRun runningRun() {
        return EvalRun.running(UUID.randomUUID(), metadata(), Instant.now());
    }

    /** incident → rca_run → task → attempt → report 链（control_app 写面；返回各 id） */
    private record RcaChain(UUID incidentId, UUID rcaRunId, UUID taskId, UUID attemptId,
                            UUID reportId) {
    }

    private RcaChain insertRcaChain(String packageJson) {
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
                        CAST(:pkg AS jsonb), 'raw', 'holmes-1.0', true, now())
                """).param("id", reportId).param("run", rcaRunId)
                .param("attempt", attemptId).param("pkg", packageJson).update();
        return new RcaChain(incidentId, rcaRunId, taskId, attemptId, reportId);
    }

    private EvalCaseResult decidableCase(UUID evalRunId, String scenario, int round,
                                         RcaChain chain) {
        return new EvalCaseResult(UUID.randomUUID(), evalRunId, scenario, round,
                "state-machine-selected-v1", chain.rcaRunId(), chain.attemptId(),
                chain.reportId(), ScenarioMetrics.ScoringVerdict.DECIDABLE, true,
                new TypedRootCause("redis", "OOM", "eviction"),
                new TypedRootCause("redis", "OOM", "eviction"),
                List.of("RedisDown"), List.of("RedisDown"),
                1, 0, 0, 42_000L, false, null);
    }

    private static String v2Package(String claims) {
        return "{\"schema_version\":2,\"summary\":\"s\",\"impact\":\"i\",\"remediation\":\"r\","
                + "\"root_cause\":{\"component\":\"redis\",\"fault_type\":\"OOM\","
                + "\"reason_code\":\"eviction\"},\"evidence\":[\"e\"],\"references\":[],"
                + "\"claims\":[" + claims + "]}";
    }

    private static String claim(String type, String status, String... refs) {
        StringBuilder refJson = new StringBuilder();
        for (String ref : refs) {
            if (refJson.length() > 0) {
                refJson.append(',');
            }
            refJson.append('"').append(ref).append('"');
        }
        return "{\"claim_type\":\"" + type + "\",\"status\":\"" + status + "\","
                + "\"component\":\"redis\",\"fault_type\":\"OOM\","
                + "\"symptom_codes\":[],\"evidence_refs\":[" + refJson + "]}";
    }

    private void insertEvidence(UUID evidenceId, UUID rcaRunId, String type, String payload) {
        controlJdbc.sql("""
                INSERT INTO rca_evidence(id, run_id, evidence_type, schema_version,
                    observed_generation, source, scope, payload, payload_digest)
                VALUES (:id, :run, :type, 'v1', 0, 'logs', :scope, :payload, :digest)
                """).param("id", evidenceId).param("run", rcaRunId).param("type", type)
                .param("scope", "{\"time_range\":\"w\"}")
                .param("payload", payload)
                .param("digest", Digest.sha256Of(payload).value()).update();
    }

    // ------------------------------------------------------------------ 案例详情投影

    @Test
    @DisplayName("案例详情：双键定位 + 关联链/包原文直读；错 runId 或未知 case → empty")
    void caseDetailProjectsChainAndHonorsDoubleKey() {
        EvalRun run = runningRun();
        evalRuns.insertRunning(run);
        RcaChain chain = insertRcaChain(v2Package(claim("causal", "TRUE")));
        EvalCaseResult c = decidableCase(run.id(), "infra/redis-oom", 1, chain);
        evalRuns.insertCaseResult(c);

        EvalCaseDetailRow row = reader.findCaseDetail(run.id(), c.id()).orElseThrow();

        assertThat(row.caseExecutionId()).isEqualTo(c.id());
        assertThat(row.runId()).isEqualTo(run.id());
        assertThat(row.datasetVersion()).isEqualTo("rca100-v1.1");
        assertThat(row.scenarioId()).isEqualTo("infra/redis-oom");
        assertThat(row.verdict()).isEqualTo("DECIDABLE");
        assertThat(row.rcaRunId()).isEqualTo(chain.rcaRunId());
        assertThat(row.rcaRunState()).isEqualTo("SUCCEEDED");
        assertThat(row.incidentId()).isEqualTo(chain.incidentId());
        assertThat(row.rcaStartedAt()).isNotNull();
        assertThat(row.scoredAttemptId()).isEqualTo(chain.attemptId());
        assertThat(row.scoredReportId()).isEqualTo(chain.reportId());
        assertThat(row.reportValidationStatus()).isEqualTo("STRUCTURE_VALIDATED");
        assertThat(row.reportSchemaVersion()).isEqualTo(2);
        assertThat(row.reportModel()).isEqualTo("holmes-1.0");
        assertThat(row.packageJson()).contains("\"claims\"");
        // 双键定位：别的 run 直读不到本案例
        assertThat(reader.findCaseDetail(UUID.randomUUID(), c.id())).isEmpty();
        assertThat(reader.findCaseDetail(run.id(), UUID.randomUUID())).isEmpty();
    }

    @Test
    @DisplayName("关联链断裂（TIMEOUT_OR_ABSENT 无 rca 链）→ 详情行链路字段全 null")
    void caseDetailWithBrokenChainProjectsNulls() {
        EvalRun run = runningRun();
        evalRuns.insertRunning(run);
        EvalCaseResult absent = new EvalCaseResult(UUID.randomUUID(), run.id(), "S2", 1,
                "state-machine-selected-v1", null, null, null,
                ScenarioMetrics.ScoringVerdict.TIMEOUT_OR_ABSENT, false,
                new TypedRootCause("db", "LOCK", "deadlock"), null,
                List.of("DbLock"), null, 0, 0, 1, null, false, null);
        evalRuns.insertCaseResult(absent);

        EvalCaseDetailRow row = reader.findCaseDetail(run.id(), absent.id()).orElseThrow();

        assertThat(row.rcaRunId()).isNull();
        assertThat(row.rcaRunState()).isNull();
        assertThat(row.incidentId()).isNull();
        assertThat(row.scoredReportId()).isNull();
        assertThat(row.reportValidationStatus()).isNull();
        assertThat(row.packageJson()).isNull();
        assertThat(row.actualRootCauseJson()).isNull();
    }

    // ------------------------------------------------------------------ 场景身份解析

    private UUID insertDataset(String name, String version, String partition,
                               String sourceClass) {
        UUID id = UUID.randomUUID();
        evalJdbc.sql("""
                INSERT INTO dataset_version(id, source, name, version, source_uri, license,
                    access_class, content_digest, adapter_version, imported_at,
                    source_class, partition_class, scenario_family_digest)
                VALUES (:id, 'rca100', :name, :version, 's3://x', 'MIT', 'internal',
                    :digest, 'adapter-v1', now(), :sourceClass, :partition, :famDigest)
                """).param("id", id).param("name", name).param("version", version)
                .param("digest", Digest.sha256Of("ds-" + id).value())
                .param("sourceClass", sourceClass).param("partition", partition)
                .param("famDigest", Digest.sha256Of("fam-" + id).value()).update();
        return id;
    }

    private void insertCaseVersion(UUID datasetId, String partition, String caseKey,
                                   String family) {
        evalJdbc.sql("""
                INSERT INTO case_version(id, dataset_version_id, case_key, scenario_family_id,
                    valid_from, content_digest, payload, partition_class)
                VALUES (:id, :ds, :key, :family, now(), :digest,
                    CAST('{"caseKey":"x"}' AS jsonb), :partition)
                """).param("id", UUID.randomUUID()).param("ds", datasetId)
                .param("key", caseKey).param("family", family)
                .param("digest", Digest.sha256Of("case-" + caseKey + datasetId).value())
                .param("partition", partition).update();
    }

    @Test
    @DisplayName("场景身份：精确键命中返回身份/分区；HOLDOUT 行 RLS 不可见 → empty；多命中歧义 → empty")
    void caseIdentityExactKeyRlsAndAmbiguity() {
        UUID ds = insertDataset("rca100", "rca100-v1.1", "VALIDATION", "PUBLIC_BENCHMARK");
        insertCaseVersion(ds, "VALIDATION", "infra/redis-oom", "redis-oom");

        CaseIdentityRow hit = reader.findCaseIdentity("rca100-v1.1", "infra/redis-oom")
                .orElseThrow();
        assertThat(hit.caseKey()).isEqualTo("infra/redis-oom");
        assertThat(hit.scenarioFamilyId()).isEqualTo("redis-oom");
        assertThat(hit.partitionClass()).isEqualTo("VALIDATION");
        assertThat(hit.datasetName()).isEqualTo("rca100");

        // 无匹配键 → empty
        assertThat(reader.findCaseIdentity("rca100-v1.1", "no/such")).isEmpty();
        assertThat(reader.findCaseIdentity("other-version", "infra/redis-oom")).isEmpty();

        // HOLDOUT（PRIVATE 源方可落，V21 触发器）：eval_app 被 RLS 挡 → admin 落行，
        // control_app 读面不可见 → empty（GT 封存不因投影开口）
        UUID holdoutDs = insertDataset("private-set", "rca100-v2", "HOLDOUT", "PRIVATE");
        adminJdbc.sql("""
                INSERT INTO case_version(id, dataset_version_id, case_key, scenario_family_id,
                    valid_from, content_digest, payload, partition_class)
                VALUES (:id, :ds, 'infra/redis-oom', 'redis-oom-h', now(), :digest,
                    CAST('{"caseKey":"h"}' AS jsonb), 'HOLDOUT')
                """).param("id", UUID.randomUUID()).param("ds", holdoutDs)
                .param("digest", Digest.sha256Of("case-holdout").value()).update();
        assertThat(reader.findCaseIdentity("rca100-v2", "infra/redis-oom")).isEmpty();

        // 归属歧义：两个不同 name 的 dataset 同 version 且都含该 case_key → empty（不取"最新"）
        UUID dup = insertDataset("rca100-mirror", "rca100-v1.1", "VALIDATION",
                "PUBLIC_BENCHMARK");
        insertCaseVersion(dup, "VALIDATION", "infra/redis-oom", "redis-oom");
        assertThat(reader.findCaseIdentity("rca100-v1.1", "infra/redis-oom")).isEmpty();
    }

    // ------------------------------------------------------------------ Run 证据引用扁平行

    @Test
    @DisplayName("证据引用扁平行：本 run 证据解析出类型；跨 run/非 UUID 引用不解析；无报告案例出哨兵行")
    void evidenceRefRowsResolveOnlyWithinCaseRun() {
        EvalRun run = runningRun();
        evalRuns.insertRunning(run);
        UUID evInRun = UUID.randomUUID();
        RcaChain chain = insertRcaChain(v2Package(
                claim("causal", "TRUE", evInRun.toString(), "loki:query:x")
                        + "," + claim("counter", "FALSE", UUID.randomUUID().toString())));
        insertEvidence(evInRun, chain.rcaRunId(), "logs.query", "{\"status\":\"success\"}");
        EvalCaseResult c = decidableCase(run.id(), "S1", 1, chain);
        evalRuns.insertCaseResult(c);
        // 无报告案例（TIMEOUT_OR_ABSENT）
        EvalCaseResult absent = new EvalCaseResult(UUID.randomUUID(), run.id(), "S2", 1,
                "state-machine-selected-v1", null, null, null,
                ScenarioMetrics.ScoringVerdict.TIMEOUT_OR_ABSENT, false,
                new TypedRootCause("db", "LOCK", "deadlock"), null,
                List.of("DbLock"), null, 0, 0, 1, null, false, null);
        evalRuns.insertCaseResult(absent);

        List<CaseEvidenceRefRow> rows = reader.listCaseEvidenceRefs(run.id());

        assertThat(rows).hasSize(4); // 3 refs + 1 哨兵行
        List<CaseEvidenceRefRow> caseRows = rows.stream()
                .filter(r -> r.caseExecutionId().equals(c.id())).toList();
        assertThat(caseRows).hasSize(3);
        CaseEvidenceRefRow resolved = caseRows.stream()
                .filter(r -> evInRun.toString().equals(r.ref())).findFirst().orElseThrow();
        assertThat(resolved.evidenceId()).isEqualTo(evInRun);
        assertThat(resolved.evidenceType()).isEqualTo("logs.query");
        assertThat(resolved.claimStatus()).isEqualTo("TRUE");
        // 非 UUID 引用与不在本 run 的 UUID 引用 → 不解析
        assertThat(caseRows.stream().filter(r -> "loki:query:x".equals(r.ref()))
                .findFirst().orElseThrow().evidenceId()).isNull();
        assertThat(caseRows.stream().filter(r -> "counter".equals(r.claimType()))
                .findFirst().orElseThrow().evidenceId()).isNull();
        // 哨兵行：无报告案例一行全空
        List<CaseEvidenceRefRow> sentinel = rows.stream()
                .filter(r -> r.caseExecutionId().equals(absent.id())).toList();
        assertThat(sentinel).hasSize(1);
        assertThat(sentinel.get(0).claimStatus()).isNull();
        assertThat(sentinel.get(0).scoredReportId()).isNull();
    }

    @Test
    @DisplayName("证据元数据：rcaRunId+id 白名单双约束——跨 run id 不返回（§3.4 跨对象禁读）")
    void evidenceMetaEnforcesRunScope() {
        RcaChain chainA = insertRcaChain(v2Package(claim("a", "TRUE")));
        RcaChain chainB = insertRcaChain(v2Package(claim("b", "TRUE")));
        UUID evA = UUID.randomUUID();
        UUID evB = UUID.randomUUID();
        insertEvidence(evA, chainA.rcaRunId(), "logs.query", "{\"a\":1}");
        insertEvidence(evB, chainB.rcaRunId(), "metrics.query_range", "{\"b\":1}");

        List<EvidenceMetaRow> metas = reader.listEvidenceMeta(chainA.rcaRunId(),
                List.of(evA, evB));

        assertThat(metas).hasSize(1);
        assertThat(metas.get(0).evidenceId()).isEqualTo(evA);
        assertThat(metas.get(0).evidenceType()).isEqualTo("logs.query");
        assertThat(metas.get(0).payloadDigest())
                .isEqualTo(Digest.sha256Of("{\"a\":1}").value());
        assertThat(reader.listEvidenceMeta(chainA.rcaRunId(), List.of())).isEmpty();
    }

    // ------------------------------------------------------------------ 受限日志证据投影

    @Test
    @DisplayName("logs.query 冻结证据投影：只取该类型、按 round/created_at 序、payload 原文上抛")
    void caseLogEvidenceProjectsFrozenPayloads() {
        EvalRun run = runningRun();
        evalRuns.insertRunning(run);
        RcaChain chain = insertRcaChain(v2Package(claim("causal", "TRUE")));
        EvalCaseResult c = decidableCase(run.id(), "S1", 1, chain);
        evalRuns.insertCaseResult(c);
        String logPayload = "{\"status\":\"success\",\"data\":{\"result\":["
                + "{\"ts\":\"2026-09-09T10:00:00Z\",\"service\":\"checkout\","
                + "\"line\":\"ERROR redis OOM\"}],\"truncated\":false}}";
        insertEvidence(UUID.randomUUID(), chain.rcaRunId(), "logs.query", logPayload);
        insertEvidence(UUID.randomUUID(), chain.rcaRunId(), "metrics.query_range",
                "{\"status\":\"success\"}");

        List<CaseLogEvidenceRow> rows = reader.listCaseLogEvidence(run.id(), "S1");

        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).caseExecutionId()).isEqualTo(c.id());
        assertThat(rows.get(0).rcaRunId()).isEqualTo(chain.rcaRunId());
        assertThat(rows.get(0).payloadJson()).isEqualTo(logPayload);
        assertThat(rows.get(0).scopeJson()).isEqualTo("{\"time_range\":\"w\"}");
        // 别的 scenario / 别的 run → 空
        assertThat(reader.listCaseLogEvidence(run.id(), "S-other")).isEmpty();
        assertThat(reader.listCaseLogEvidence(UUID.randomUUID(), "S1")).isEmpty();
    }

    // ------------------------------------------------------------------ 授权矩阵

    @Test
    @DisplayName("eval_app 对 rca_evidence 零权限；publisher_app 对 eval_case_result 零权限（V10/V16 同律）")
    void grantMatrixKeepsReadFaceMinimal() {
        assertThatThrownBy(() -> evalJdbc.sql("SELECT count(*) FROM rca_evidence")
                .query(Long.class).single())
                .hasStackTraceContaining("permission denied");
        assertThatThrownBy(() -> publisherJdbc.sql("SELECT count(*) FROM eval_case_result")
                .query(Long.class).single())
                .hasStackTraceContaining("permission denied");
        // control_app 只读面：SELECT 通、INSERT eval_run 拒（V45 只授 SELECT）
        controlJdbc.sql("SELECT count(*) FROM eval_case_result").query(Long.class).single();
        assertThatThrownBy(() -> controlJdbc.sql("""
                INSERT INTO eval_run(id, schema_version, dataset_version, registry_digest,
                    lexicon_version, model, prompt_version, prompt_digest,
                    tool_registry_digest, state, started_at)
                VALUES (:id, 1, 'ds', :rd, 1, 'm', 'p', :pd, :td, 'RUNNING', now())
                """).param("id", UUID.randomUUID())
                .param("rd", Digest.sha256Of("x").value())
                .param("pd", Digest.sha256Of("y").value())
                .param("td", Digest.sha256Of("z").value()).update())
                .hasStackTraceContaining("permission denied");
    }
}
