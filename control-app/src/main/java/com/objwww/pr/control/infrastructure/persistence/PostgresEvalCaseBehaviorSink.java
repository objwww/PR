package com.objwww.pr.control.infrastructure.persistence;

import com.objwww.pr.control.eval.domain.repository.EvalCaseBehaviorSink;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.util.Objects;
import java.util.UUID;

/**
 * {@link EvalCaseBehaviorSink} 的 Postgres 实现（ME-T04/D04，V160）。
 * insert-only + on conflict (case_result_id, grader_version) do nothing：
 * 同轨迹同 grader 重算幂等，历史记录不被重评覆盖（沿 PostgresEvalCaseSixPartsSink
 * 惯例；jsonb 列以 ::jsonb 文本落库）。
 */
public class PostgresEvalCaseBehaviorSink implements EvalCaseBehaviorSink {

    /** 包内可见供 SQL 契约测试锁定形状（insert-only/幂等纪律） */
    static final String SQL = """
            insert into eval_case_behavior (
                id, case_result_id, eval_run_id, scenario_id, round_no,
                grader_version, trace_digest, coverage, checks, metrics,
                failure_labels, evidence_refs)
            values (:id, :caseResultId, :evalRunId, :scenarioId, :roundNo,
                :graderVersion, :traceDigest, :coverage::jsonb, :checks::jsonb,
                :metrics::jsonb, :failureLabels::jsonb, :evidenceRefs::jsonb)
            on conflict (case_result_id, grader_version) do nothing
            """;

    private final JdbcClient jdbc;

    public PostgresEvalCaseBehaviorSink(JdbcClient jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
    }

    @Override
    public void insert(UUID caseResultId, UUID evalRunId, String scenarioId, int roundNo,
                       String graderVersion, String traceDigest,
                       String coverageJson, String checksJson, String metricsJson,
                       String failureLabelsJson, String evidenceRefsJson) {
        jdbc.sql(SQL)
                .param("id", UUID.randomUUID())
                .param("caseResultId", caseResultId)
                .param("evalRunId", evalRunId)
                .param("scenarioId", scenarioId)
                .param("roundNo", roundNo)
                .param("graderVersion", graderVersion)
                .param("traceDigest", traceDigest)
                .param("coverage", coverageJson)
                .param("checks", checksJson)
                .param("metrics", metricsJson)
                .param("failureLabels", failureLabelsJson)
                .param("evidenceRefs", evidenceRefsJson)
                .update();
    }
}
