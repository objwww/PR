package com.objwww.pr.control.infrastructure.persistence;

import com.objwww.pr.control.eval.domain.repository.EvalCaseCollabSink;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.util.Objects;
import java.util.UUID;

/**
 * {@link EvalCaseCollabSink} 的 Postgres 实现（ME-T12/D07，V163）。
 * insert-only + on conflict (case_result_id, grader_version) do nothing：
 * 同轨迹同 grader 重算幂等，历史记录不被重评覆盖（沿 PostgresEvalCaseLoopSink
 * 惯例；jsonb 列以 ::jsonb 文本落库）。
 */
public class PostgresEvalCaseCollabSink implements EvalCaseCollabSink {

    /** 包内可见供 SQL 契约测试锁定形状（insert-only/幂等纪律） */
    static final String SQL = """
            insert into eval_case_collab (
                id, case_result_id, eval_run_id, scenario_id, round_no,
                grader_version, edge_count, admitted_count, token_cost_total,
                checks, metrics, failure_labels,
                suspected_attributions, supported_attributions)
            values (:id, :caseResultId, :evalRunId, :scenarioId, :roundNo,
                :graderVersion, :edgeCount, :admittedCount, :tokenCostTotal,
                :checks::jsonb, :metrics::jsonb, :failureLabels::jsonb,
                :suspectedAttributions::jsonb, :supportedAttributions::jsonb)
            on conflict (case_result_id, grader_version) do nothing
            """;

    private final JdbcClient jdbc;

    public PostgresEvalCaseCollabSink(JdbcClient jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
    }

    @Override
    public void insert(UUID caseResultId, UUID evalRunId, String scenarioId, int roundNo,
                       String graderVersion, Integer edgeCount, Integer admittedCount,
                       Long tokenCostTotal, String checksJson, String metricsJson,
                       String failureLabelsJson, String suspectedAttributionsJson,
                       String supportedAttributionsJson) {
        jdbc.sql(SQL)
                .param("id", UUID.randomUUID())
                .param("caseResultId", caseResultId)
                .param("evalRunId", evalRunId)
                .param("scenarioId", scenarioId)
                .param("roundNo", roundNo)
                .param("graderVersion", graderVersion)
                .param("edgeCount", edgeCount)
                .param("admittedCount", admittedCount)
                .param("tokenCostTotal", tokenCostTotal)
                .param("checks", checksJson)
                .param("metrics", metricsJson)
                .param("failureLabels", failureLabelsJson)
                .param("suspectedAttributions", suspectedAttributionsJson)
                .param("supportedAttributions", supportedAttributionsJson)
                .update();
    }
}
