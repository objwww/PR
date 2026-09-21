package com.objwww.pr.control.infrastructure.persistence;

import com.objwww.pr.control.eval.domain.repository.EvalCaseDriftSink;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.util.Objects;
import java.util.UUID;

/**
 * {@link EvalCaseDriftSink} 的 Postgres 实现（ME-T12/D08，V164）。
 * insert-only + on conflict (case_result_id, grader_version) do nothing：
 * 同轨迹同 grader 重算幂等，历史记录不被重评覆盖（沿 PostgresEvalCaseCollabSink
 * 惯例；jsonb 列以 ::jsonb 文本落库）。
 */
public class PostgresEvalCaseDriftSink implements EvalCaseDriftSink {

    /** 包内可见供 SQL 契约测试锁定形状（insert-only/幂等纪律） */
    static final String SQL = """
            insert into eval_case_drift (
                id, case_result_id, eval_run_id, scenario_id, round_no,
                grader_version, summary_digest, consumption, checks, metrics,
                failure_labels, deferred)
            values (:id, :caseResultId, :evalRunId, :scenarioId, :roundNo,
                :graderVersion, :summaryDigest, :consumption::jsonb, :checks::jsonb,
                :metrics::jsonb, :failureLabels::jsonb, :deferred::jsonb)
            on conflict (case_result_id, grader_version) do nothing
            """;

    private final JdbcClient jdbc;

    public PostgresEvalCaseDriftSink(JdbcClient jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
    }

    @Override
    public void insert(UUID caseResultId, UUID evalRunId, String scenarioId, int roundNo,
                       String graderVersion, String summaryDigest, String consumptionJson,
                       String checksJson, String metricsJson, String failureLabelsJson,
                       String deferredJson) {
        jdbc.sql(SQL)
                .param("id", UUID.randomUUID())
                .param("caseResultId", caseResultId)
                .param("evalRunId", evalRunId)
                .param("scenarioId", scenarioId)
                .param("roundNo", roundNo)
                .param("graderVersion", graderVersion)
                .param("summaryDigest", summaryDigest)
                .param("consumption", consumptionJson)
                .param("checks", checksJson)
                .param("metrics", metricsJson)
                .param("failureLabels", failureLabelsJson)
                .param("deferred", deferredJson)
                .update();
    }
}
