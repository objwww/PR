package com.objwww.pr.control.infrastructure.persistence;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.objwww.pr.control.alert.domain.model.TypedRootCause;
import com.objwww.pr.control.eval.domain.EvalCaseResult;
import com.objwww.pr.control.eval.domain.EvalRun;
import com.objwww.pr.control.eval.domain.EvalRunMetadata;
import com.objwww.pr.control.eval.domain.ScenarioMetrics;
import com.objwww.pr.control.eval.domain.repository.EvalRunRepository;
import com.objwww.pr.shared.Digest;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * eval_run / eval_case_result 的 Postgres 实现（V10；M3-14）。
 *
 * <p>不可覆盖语义三层：insertRunning 主键冲突上抛；finalizeOnce CAS（RUNNING 行计数
 * 限定）；insertCaseResult UNIQUE 冲突返回 false。评分记录无 UPDATE 路径——类内不存在
 * 任何 UPDATE eval_case_result 语句。
 */
public class PostgresEvalRunRepository implements EvalRunRepository {

    private static final String INSERT_RUNNING_SQL = """
            INSERT INTO eval_run (
                id, schema_version, dataset_version, registry_digest, lexicon_version,
                model, prompt_version, prompt_digest, tool_registry_digest,
                temperature, top_p, max_tokens, requested_seed, effective_seed,
                provider_fingerprint, alert_rule_digest, scenario_driver_version,
                grader_version, config_digest, state, started_at
            ) VALUES (
                :id, :schemaVersion, :datasetVersion, :registryDigest, :lexiconVersion,
                :model, :promptVersion, :promptDigest, :toolRegistryDigest,
                :temperature, :topP, :maxTokens, :requestedSeed, :effectiveSeed,
                :providerFingerprint, :alertRuleDigest, :scenarioDriverVersion,
                :graderVersion, :configDigest, 'RUNNING', :startedAt
            )
            """;

    private static final String FINALIZE_SQL = """
            UPDATE eval_run SET
                state = :state,
                finished_at = :finishedAt,
                total_scenarios = :totalScenarios,
                decidable_count = :decidableCount,
                hit_count = :hitCount,
                unresolved_count = :unresolvedCount,
                structure_rejected_count = :structureRejectedCount,
                timeout_or_absent_count = :timeoutOrAbsentCount,
                tp_count = :tpCount,
                fp_count = :fpCount,
                fn_count = :fnCount,
                coverage = :coverage,
                conditional_accuracy = :conditionalAccuracy,
                end_to_end_hit_rate = :endToEndHitRate,
                unresolved_rate = :unresolvedRate,
                baseline_report_digest = :baselineReportDigest,
                terminal_reason = :terminalReason
            WHERE id = :id AND state = 'RUNNING'
            """;

    private static final String INSERT_CASE_SQL = """
            INSERT INTO eval_case_result (
                id, eval_run_id, scenario_id, round_no,
                selection_policy_version, rca_run_id, scored_attempt_id, scored_report_id,
                verdict, root_cause_hit,
                expected_root_cause, actual_root_cause,
                expected_symptom_codes, actual_symptom_codes,
                tp_count, fp_count, fn_count,
                latency_ms, silence_penalty, failure_sample
            ) VALUES (
                :id, :evalRunId, :scenarioId, :roundNo,
                :selectionPolicyVersion, :rcaRunId, :scoredAttemptId, :scoredReportId,
                :verdict, :rootCauseHit,
                CAST(:expectedRootCause AS jsonb), CAST(:actualRootCause AS jsonb),
                CAST(:expectedSymptomCodes AS jsonb), CAST(:actualSymptomCodes AS jsonb),
                :tpCount, :fpCount, :fnCount,
                :latencyMs, :silencePenalty, CAST(:failureSample AS jsonb)
            )
            """;

    private static final ObjectMapper JSON = new ObjectMapper();

    private final JdbcClient jdbc;

    public PostgresEvalRunRepository(JdbcClient jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc);
    }

    @Override
    public void insertRunning(EvalRun running) {
        if (running.state() != EvalRun.EvalRunState.RUNNING) {
            throw new IllegalArgumentException("insertRunning 只接受 RUNNING 行");
        }
        EvalRunMetadata m = running.metadata();
        try {
            jdbc.sql(INSERT_RUNNING_SQL)
                    .param("id", running.id())
                    .param("schemaVersion", m.schemaVersion())
                    .param("datasetVersion", m.datasetVersion())
                    .param("registryDigest", m.registryDigest().value())
                    .param("lexiconVersion", m.lexiconVersion())
                    .param("model", m.model())
                    .param("promptVersion", m.promptVersion())
                    .param("promptDigest", m.promptDigest().value())
                    .param("toolRegistryDigest", m.toolRegistryDigest().value())
                    .param("temperature", m.temperature())
                    .param("topP", m.topP())
                    .param("maxTokens", m.maxTokens())
                    .param("requestedSeed", m.requestedSeed())
                    .param("effectiveSeed", m.effectiveSeed())
                    .param("providerFingerprint", m.providerFingerprint())
                    .param("alertRuleDigest", m.alertRuleDigest().value())
                    .param("scenarioDriverVersion", m.scenarioDriverVersion())
                    .param("graderVersion", m.graderVersion())
                    .param("configDigest", m.configDigest().value())
                    .param("startedAt", ts(running.startedAt()))
                    .update();
        } catch (DuplicateKeyException e) {
            throw new IllegalStateException(
                    "同 EvalRun 禁止重生（不可覆盖语义）: " + running.id(), e);
        }
    }

    @Override
    public boolean finalizeOnce(EvalRun terminal) {
        if (terminal.state() == EvalRun.EvalRunState.RUNNING) {
            throw new IllegalArgumentException("finalizeOnce 不接受 RUNNING");
        }
        ScenarioMetrics.Snapshot s = terminal.summary();
        EvalRun.SymptomCounts c = terminal.symptomCounts();
        boolean succeeded = terminal.state() == EvalRun.EvalRunState.SUCCEEDED;
        if (succeeded && (s == null || c == null)) {
            throw new IllegalArgumentException("SUCCEEDED 终态必带指标快照与症状计数");
        }
        return jdbc.sql(FINALIZE_SQL)
                .param("state", terminal.state().name())
                .param("finishedAt", ts(terminal.finishedAt()))
                .param("totalScenarios", s == null ? null : s.total())
                .param("decidableCount", s == null ? null : s.decidable())
                .param("hitCount", s == null ? null : s.hits())
                .param("unresolvedCount", s == null ? null : s.unresolved())
                .param("structureRejectedCount", s == null ? null : s.structureRejected())
                .param("timeoutOrAbsentCount", s == null ? null : s.timeoutOrAbsent())
                .param("tpCount", c == null ? null : c.truePositives())
                .param("fpCount", c == null ? null : c.falsePositives())
                .param("fnCount", c == null ? null : c.falseNegatives())
                .param("coverage", s == null ? null : s.coverage())
                .param("conditionalAccuracy", s == null ? null : s.conditionalAccuracy())
                .param("endToEndHitRate", s == null ? null : s.endToEndHitRate())
                .param("unresolvedRate", s == null ? null : s.unresolvedRate())
                .param("baselineReportDigest", hash(terminal.baselineReportDigest()))
                .param("terminalReason", terminal.terminalReason())
                .param("id", terminal.id())
                .update() > 0;
    }

    @Override
    public boolean applyLaunchIdentity(UUID runId, String displayName, String mode,
                                       String launchPlanJson) {
        return jdbc.sql("""
                        UPDATE eval_run SET display_name = :displayName, mode = :mode,
                            launch_plan = CAST(:launchPlan AS jsonb)
                        WHERE id = :id
                        """)
                .param("displayName", displayName)
                .param("mode", mode)
                .param("launchPlan", launchPlanJson)
                .param("id", runId)
                .update() > 0;
    }

    @Override
    public boolean updateRecoveryState(UUID runId, String recoveryState) {
        return jdbc.sql("UPDATE eval_run SET recovery_state = :state WHERE id = :id")
                .param("state", recoveryState)
                .param("id", runId)
                .update() > 0;
    }

    @Override
    public boolean insertCaseResult(EvalCaseResult result) {
        try {
            jdbc.sql(INSERT_CASE_SQL)
                    .param("id", result.id())
                    .param("evalRunId", result.evalRunId())
                    .param("scenarioId", result.scenarioId())
                    .param("roundNo", result.roundNo())
                    .param("selectionPolicyVersion", result.selectionPolicyVersion())
                    .param("rcaRunId", result.rcaRunId())
                    .param("scoredAttemptId", result.scoredAttemptId())
                    .param("scoredReportId", result.scoredReportId())
                    .param("verdict", result.verdict().name())
                    .param("rootCauseHit", result.rootCauseHit())
                    .param("expectedRootCause", rootCauseJson(result.expectedRootCause()))
                    .param("actualRootCause", rootCauseJson(result.actualRootCause()))
                    .param("expectedSymptomCodes", listJson(result.expectedSymptomCodes()))
                    .param("actualSymptomCodes", listJson(result.actualSymptomCodes()))
                    .param("tpCount", result.tpCount())
                    .param("fpCount", result.fpCount())
                    .param("fnCount", result.fnCount())
                    .param("latencyMs", result.latencyMs())
                    .param("silencePenalty", result.silencePenalty())
                    .param("failureSample", result.failureSampleJson())
                    .update();
            return true;
        } catch (DuplicateKeyException e) {
            return false;
        }
    }

    @Override
    public Optional<EvalRun> findById(UUID runId) {
        return jdbc.sql("SELECT * FROM eval_run WHERE id = :id")
                .param("id", runId)
                .query(this::mapRun)
                .optional();
    }

    @Override
    public List<EvalCaseResult> findCasesByRunId(UUID runId) {
        return jdbc.sql("SELECT * FROM eval_case_result"
                        + " WHERE eval_run_id = :runId ORDER BY scenario_id, round_no")
                .param("runId", runId)
                .query(this::mapCase)
                .list();
    }

    // ------------------------------------------------------------------ 行映射

    private EvalRun mapRun(ResultSet rs, int rowNum) throws SQLException {
        ScenarioMetrics.Snapshot summary = null;
        long total = rs.getLong("total_scenarios");
        if (!rs.wasNull()) {
            summary = new ScenarioMetrics.Snapshot(
                    (int) total, rs.getInt("decidable_count"), rs.getInt("hit_count"),
                    rs.getInt("unresolved_count"), rs.getInt("structure_rejected_count"),
                    rs.getInt("timeout_or_absent_count"),
                    rs.getDouble("coverage"), rs.getDouble("conditional_accuracy"),
                    rs.getDouble("end_to_end_hit_rate"), rs.getDouble("unresolved_rate"));
        }
        EvalRun.SymptomCounts counts = null;
        long tp = rs.getLong("tp_count");
        if (!rs.wasNull()) {
            counts = new EvalRun.SymptomCounts((int) tp,
                    rs.getInt("fp_count"), rs.getInt("fn_count"));
        }
        Timestamp finishedAt = rs.getTimestamp("finished_at");
        return new EvalRun(
                rs.getObject("id", UUID.class),
                new EvalRunMetadata(
                        rs.getInt("schema_version"),
                        rs.getString("dataset_version"),
                        digest(rs.getString("registry_digest")),
                        rs.getInt("lexicon_version"),
                        rs.getString("model"),
                        rs.getString("prompt_version"),
                        digest(rs.getString("prompt_digest")),
                        digest(rs.getString("tool_registry_digest")),
                        rs.getBigDecimal("temperature"),
                        rs.getBigDecimal("top_p"),
                        (Integer) rs.getObject("max_tokens"),
                        (Long) rs.getObject("requested_seed"),
                        (Long) rs.getObject("effective_seed"),
                        rs.getString("provider_fingerprint"),
                        digest(rs.getString("alert_rule_digest")),
                        rs.getString("scenario_driver_version"),
                        rs.getString("grader_version")),
                EvalRun.EvalRunState.valueOf(rs.getString("state")),
                rs.getTimestamp("started_at").toInstant(),
                finishedAt == null ? null : finishedAt.toInstant(),
                summary,
                counts,
                digest(rs.getString("baseline_report_digest")),
                rs.getString("terminal_reason"));
    }

    private EvalCaseResult mapCase(ResultSet rs, int rowNum) throws SQLException {
        String actualSymptoms = rs.getString("actual_symptom_codes");
        Long latency = rs.getLong("latency_ms");
        boolean latencyNull = rs.wasNull();
        return new EvalCaseResult(
                rs.getObject("id", UUID.class),
                rs.getObject("eval_run_id", UUID.class),
                rs.getString("scenario_id"),
                rs.getInt("round_no"),
                rs.getString("selection_policy_version"),
                rs.getObject("rca_run_id", UUID.class),
                rs.getObject("scored_attempt_id", UUID.class),
                rs.getObject("scored_report_id", UUID.class),
                ScenarioMetrics.ScoringVerdict.valueOf(rs.getString("verdict")),
                rs.getBoolean("root_cause_hit"),
                rootCause(rs.getString("expected_root_cause")),
                rootCause(rs.getString("actual_root_cause")),
                stringList(rs.getString("expected_symptom_codes")),
                actualSymptoms == null ? null : stringList(actualSymptoms),
                rs.getInt("tp_count"),
                rs.getInt("fp_count"),
                rs.getInt("fn_count"),
                latencyNull ? null : latency,
                rs.getBoolean("silence_penalty"),
                rs.getString("failure_sample"));
    }

    // ------------------------------------------------------------------ jsonb 编解码

    private static String rootCauseJson(TypedRootCause cause) {
        if (cause == null) {
            return null;
        }
        return mapJson(Map.of(
                "component", cause.component(),
                "fault_type", cause.faultType(),
                "reason_code", cause.reasonCode()));
    }

    private static TypedRootCause rootCause(String json) {
        if (json == null) {
            return null;
        }
        try {
            Map<?, ?> map = JSON.readValue(json, Map.class);
            return new TypedRootCause(
                    String.valueOf(map.get("component")),
                    String.valueOf(map.get("fault_type")),
                    String.valueOf(map.get("reason_code")));
        } catch (Exception e) {
            throw new IllegalStateException("root_cause jsonb 解析失败: " + e.getMessage(), e);
        }
    }

    private static String listJson(List<String> values) {
        if (values == null || values.isEmpty()) {
            return null;
        }
        try {
            return JSON.writeValueAsString(values);
        } catch (Exception e) {
            throw new IllegalStateException("症状码列表序列化失败", e);
        }
    }

    private static List<String> stringList(String json) {
        if (json == null) {
            return List.of();
        }
        try {
            return JSON.readValue(json, JSON.getTypeFactory()
                    .constructCollectionType(List.class, String.class));
        } catch (Exception e) {
            return List.of();
        }
    }

    private static String mapJson(Map<String, String> map) {
        try {
            return JSON.writeValueAsString(map);
        } catch (Exception e) {
            throw new IllegalStateException("map jsonb 序列化失败", e);
        }
    }

    // ------------------------------------------------------------------ 小工具

    private static Timestamp ts(Instant instant) {
        return instant == null ? null : Timestamp.from(instant);
    }

    private static String hash(Digest digest) {
        return digest == null ? null : digest.value();
    }

    private static Digest digest(String value) {
        return value == null ? null : new Digest(value);
    }
}
