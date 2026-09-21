package com.objwww.pr.control.infrastructure.persistence;

import com.objwww.pr.control.eval.domain.repository.EvalCaseLoopSink;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.util.Objects;
import java.util.UUID;

/**
 * {@link EvalCaseLoopSink} 的 Postgres 实现（ME-T12/D05，V162）。
 * insert-only + on conflict (case_result_id, grader_version) do nothing：
 * 同轨迹同 grader 重算幂等，历史记录不被重评覆盖（沿 PostgresEvalCaseBehaviorSink
 * 惯例；jsonb 列以 ::jsonb 文本落库）。
 */
public class PostgresEvalCaseLoopSink implements EvalCaseLoopSink {

    /** 包内可见供 SQL 契约测试锁定形状（insert-only/幂等纪律） */
    static final String SQL = """
            insert into eval_case_loop (
                id, case_result_id, eval_run_id, scenario_id, round_no,
                grader_version, stop_reason, detection_event_index,
                first_no_progress_event_index, post_stop_new_actions,
                physical_calls_from_onset, tokens_from_onset, seconds_from_onset,
                checks, metrics, failure_labels)
            values (:id, :caseResultId, :evalRunId, :scenarioId, :roundNo,
                :graderVersion, :stopReason, :detectionEventIndex,
                :firstNoProgressEventIndex, :postStopNewActions,
                :physicalCallsFromOnset, :tokensFromOnset, :secondsFromOnset,
                :checks::jsonb, :metrics::jsonb, :failureLabels::jsonb)
            on conflict (case_result_id, grader_version) do nothing
            """;

    private final JdbcClient jdbc;

    public PostgresEvalCaseLoopSink(JdbcClient jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
    }

    @Override
    public void insert(UUID caseResultId, UUID evalRunId, String scenarioId, int roundNo,
                       String graderVersion, String stopReason,
                       Integer detectionEventIndex, Integer firstNoProgressEventIndex,
                       int postStopNewActions, long physicalCallsFromOnset,
                       Long tokensFromOnset, Long secondsFromOnset,
                       String checksJson, String metricsJson, String failureLabelsJson) {
        jdbc.sql(SQL)
                .param("id", UUID.randomUUID())
                .param("caseResultId", caseResultId)
                .param("evalRunId", evalRunId)
                .param("scenarioId", scenarioId)
                .param("roundNo", roundNo)
                .param("graderVersion", graderVersion)
                .param("stopReason", stopReason)
                .param("detectionEventIndex", detectionEventIndex)
                .param("firstNoProgressEventIndex", firstNoProgressEventIndex)
                .param("postStopNewActions", postStopNewActions)
                .param("physicalCallsFromOnset", physicalCallsFromOnset)
                .param("tokensFromOnset", tokensFromOnset)
                .param("secondsFromOnset", secondsFromOnset)
                .param("checks", checksJson)
                .param("metrics", metricsJson)
                .param("failureLabels", failureLabelsJson)
                .update();
    }
}
