package com.objwww.pr.control.infrastructure.persistence;

import com.objwww.pr.control.eval.domain.repository.EvalQueryReader;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * {@link EvalQueryReader} 的 Postgres 实现（UI-5 只读投影；JdbcClient 手写 SQL，
 * 沿 PostgresIncidentQueryReader 惯例——参数缺席即不拼条件，limit+1 判 hasMore）。
 *
 * <p>jsonb 列（expected/actual_root_cause、failure_sample）以 ::text 原文上抛，
 * 摘要字符串化归应用服务（纯函数可测）；avg/sum 无此面——四率是 double precision
 * 直读，countCases 走 count(*)。
 */
public class PostgresEvalQueryReader implements EvalQueryReader {

    private static final String SELECT_RUN =
            "select id, dataset_version, registry_digest, model, prompt_version,"
                    + " config_digest, state, started_at, finished_at,"
                    + " coverage, conditional_accuracy, end_to_end_hit_rate, unresolved_rate,"
                    + " tp_count, fp_count, fn_count from eval_run";

    private final JdbcClient jdbc;

    public PostgresEvalQueryReader(JdbcClient jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc);
    }

    // ------------------------------------------------------------------ runs

    @Override
    public EvalRunPage listRuns(String state, KeysetCursor cursor, int limit) {
        Map<String, Object> params = new LinkedHashMap<>();
        String where = "";
        if (state != null && !state.isBlank()) {
            where = " where state = :state";
            params.put("state", state);
        }
        if (cursor != null) {
            where = appendAnd(where,
                    "(started_at, id) < (:cursorAt, cast(:cursorId as uuid))");
            params.put("cursorAt", Timestamp.from(cursor.at()));
            params.put("cursorId", cursor.id().toString());
        }
        params.put("lim", limit + 1);
        List<EvalRunRow> rows = new ArrayList<>(jdbc.sql(
                        SELECT_RUN + where + " order by started_at desc, id desc limit :lim")
                .params(params)
                .query(this::mapRun).list());
        boolean hasMore = rows.size() > limit;
        if (hasMore) {
            rows = new ArrayList<>(rows.subList(0, limit));
        }
        return new EvalRunPage(rows, hasMore);
    }

    @Override
    public Optional<EvalRunRow> findRun(UUID runId) {
        return jdbc.sql(SELECT_RUN + " where id = :id")
                .param("id", runId)
                .query(this::mapRun).optional();
    }

    @Override
    public long countCases(UUID runId) {
        return jdbc.sql("select count(*) from eval_case_result where eval_run_id = :runId")
                .param("runId", runId)
                .query(Long.class).single();
    }

    // ------------------------------------------------------------------ cases

    @Override
    public EvalCasePage listCases(UUID runId, String verdict, String afterScenario,
                                  Integer afterRound, int limit) {
        Map<String, Object> params = new LinkedHashMap<>();
        String where = " where eval_run_id = :runId";
        params.put("runId", runId);
        if (verdict != null && !verdict.isBlank()) {
            where += " and verdict = :verdict";
            params.put("verdict", verdict);
        }
        if (afterScenario != null && afterRound != null) {
            where += " and (scenario_id, round_no) > (:afterScenario, :afterRound)";
            params.put("afterScenario", afterScenario);
            params.put("afterRound", afterRound);
        }
        params.put("lim", limit + 1);
        List<EvalCaseRow> rows = new ArrayList<>(jdbc.sql("""
                        select scenario_id, round_no, verdict, root_cause_hit,
                               expected_root_cause::text as expected_json,
                               actual_root_cause::text as actual_json,
                               latency_ms, failure_sample::text as failure_json
                        from eval_case_result
                        """ + where + " order by scenario_id asc, round_no asc limit :lim")
                .params(params)
                .query((rs, i) -> new EvalCaseRow(
                        rs.getString("scenario_id"), rs.getInt("round_no"),
                        rs.getString("verdict"), rs.getBoolean("root_cause_hit"),
                        rs.getString("expected_json"), rs.getString("actual_json"),
                        rs.getObject("latency_ms", Long.class), rs.getString("failure_json")))
                .list());
        boolean hasMore = rows.size() > limit;
        if (hasMore) {
            rows = new ArrayList<>(rows.subList(0, limit));
        }
        return new EvalCasePage(rows, hasMore);
    }

    // ------------------------------------------------------------------ datasets

    @Override
    public List<DatasetRow> listDatasets() {
        // families = 该版本 case_version 去重族键（RLS 面下仅非 HOLDOUT 可见行入桶）
        return jdbc.sql("""
                select d.version, d.source, d.created_at,
                       count(c.id) as case_count,
                       array_agg(distinct c.scenario_family_id)
                           filter (where c.id is not null) as families
                from dataset_version d
                left join case_version c on c.dataset_version_id = d.id
                group by d.id, d.version, d.source, d.created_at
                order by d.created_at desc, d.id desc
                """)
                .query((rs, i) -> {
                    List<String> families = new ArrayList<>();
                    java.sql.Array sqlArray = rs.getArray("families");
                    if (sqlArray != null) {
                        for (Object o : (Object[]) sqlArray.getArray()) {
                            families.add(String.valueOf(o));
                        }
                    }
                    java.util.Collections.sort(families);
                    return new DatasetRow(rs.getString("version"), rs.getString("source"),
                            rs.getLong("case_count"), List.copyOf(families),
                            rs.getTimestamp("created_at").toInstant());
                }).list();
    }

    // ------------------------------------------------------------------ 内部

    private EvalRunRow mapRun(ResultSet rs, int rowNum) throws SQLException {
        return new EvalRunRow(
                rs.getObject("id", UUID.class),
                rs.getString("dataset_version"),
                rs.getString("registry_digest"),
                rs.getString("model"),
                rs.getString("prompt_version"),
                rs.getString("config_digest"),
                rs.getString("state"),
                rs.getTimestamp("started_at").toInstant(),
                ts(rs, "finished_at"),
                rs.getObject("coverage", Double.class),
                rs.getObject("conditional_accuracy", Double.class),
                rs.getObject("end_to_end_hit_rate", Double.class),
                rs.getObject("unresolved_rate", Double.class),
                rs.getObject("tp_count", Integer.class),
                rs.getObject("fp_count", Integer.class),
                rs.getObject("fn_count", Integer.class));
    }

    private static String appendAnd(String where, String clause) {
        return where.isEmpty() ? " where " + clause : where + " and " + clause;
    }

    private static Instant ts(ResultSet rs, String column) throws SQLException {
        Timestamp t = rs.getTimestamp(column);
        return t == null ? null : t.toInstant();
    }
}
