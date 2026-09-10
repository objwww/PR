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
 * {@link EvalQueryReader} 的 Postgres 实现（UI-5/EV-03 只读投影；JdbcClient 手写 SQL，
 * 沿 PostgresIncidentQueryReader 惯例——参数缺席即不拼条件，limit+1 判 hasMore）。
 *
 * <p>EV-03 扩展（§5.1/§5.3 第一行）全部单查询投影，不 N+1：
 * <ul>
 *   <li>caseCount/lastProgressAt = eval_case_result 相关子查询（count(*)/max(created_at)）；</li>
 *   <li>phase/phaseEnteredAt = eval_phase_event 最新事件 left join lateral
 *       （无事件 → null，如实"阶段未采集"）；</li>
 *   <li>displayName/mode/totalScenarios/decidableCount/hitCount/unresolvedCount
 *       = eval_run 直读列（V80 + V10 终态回填列，未回填如实 null）。</li>
 * </ul>
 *
 * <p>RV08 红线：用量/费用不读 PR 域模型调用账本（其 review_run_id 外键指向 PR
 * review_run，与 eval/RCA 无关）——用量分面等 R7 RCA 调用账本显式接线（EV-06），
 * 本类不出现任何按时间窗/同 UUID 的归属猜测。
 *
 * <p>jsonb 列（expected/actual_root_cause、failure_sample）以 ::text 原文上抛，
 * 摘要字符串化归应用服务（纯函数可测）；avg/sum 无此面——四率是 double precision
 * 直读，caseCount 走 count(*)。
 */
public class PostgresEvalQueryReader implements EvalQueryReader {

    private static final String SELECT_RUN =
            "select r.id, r.dataset_version, r.registry_digest, r.model, r.prompt_version,"
                    + " r.config_digest, r.state, r.started_at, r.finished_at,"
                    + " r.coverage, r.conditional_accuracy, r.end_to_end_hit_rate, r.unresolved_rate,"
                    + " r.tp_count, r.fp_count, r.fn_count,"
                    + " r.display_name, r.mode,"
                    + " r.total_scenarios, r.decidable_count, r.hit_count, r.unresolved_count,"
                    + " (select count(*) from eval_case_result c where c.eval_run_id = r.id)"
                    + "   as case_count,"
                    + " (select max(c.created_at) from eval_case_result c where c.eval_run_id = r.id)"
                    + "   as last_progress_at,"
                    + " ph.phase as phase, ph.entered_at as phase_entered_at"
                    + " from eval_run r"
                    + " left join lateral ("
                    + "   select p.phase, p.entered_at from eval_phase_event p"
                    + "   where p.eval_run_id = r.id"
                    + "   order by p.entered_at desc, p.id desc limit 1"
                    + " ) ph on true";

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
            where = " where r.state = :state";
            params.put("state", state);
        }
        if (cursor != null) {
            where = appendAnd(where,
                    "(r.started_at, r.id) < (:cursorAt, cast(:cursorId as uuid))");
            params.put("cursorAt", Timestamp.from(cursor.at()));
            params.put("cursorId", cursor.id().toString());
        }
        params.put("lim", limit + 1);
        List<EvalRunRow> rows = new ArrayList<>(jdbc.sql(
                        SELECT_RUN + where + " order by r.started_at desc, r.id desc limit :lim")
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
        return jdbc.sql(SELECT_RUN + " where r.id = :id")
                .param("id", runId)
                .query(this::mapRun).optional();
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
                        select id as case_execution_id, scenario_id, round_no, verdict,
                               root_cause_hit,
                               expected_root_cause::text as expected_json,
                               actual_root_cause::text as actual_json,
                               latency_ms, failure_sample::text as failure_json,
                               rca_run_id, scored_report_id
                        from eval_case_result
                        """ + where + " order by scenario_id asc, round_no asc limit :lim")
                .params(params)
                .query((rs, i) -> new EvalCaseRow(
                        rs.getObject("case_execution_id", UUID.class),
                        rs.getString("scenario_id"), rs.getInt("round_no"),
                        rs.getString("verdict"), rs.getBoolean("root_cause_hit"),
                        rs.getString("expected_json"), rs.getString("actual_json"),
                        rs.getObject("latency_ms", Long.class), rs.getString("failure_json"),
                        rs.getObject("rca_run_id", UUID.class),
                        rs.getObject("scored_report_id", UUID.class)))
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
                rs.getObject("fn_count", Integer.class),
                rs.getString("display_name"),
                rs.getString("mode"),
                rs.getObject("total_scenarios", Integer.class),
                rs.getObject("decidable_count", Integer.class),
                rs.getObject("hit_count", Integer.class),
                rs.getObject("unresolved_count", Integer.class),
                rs.getLong("case_count"),
                ts(rs, "last_progress_at"),
                rs.getString("phase"),
                ts(rs, "phase_entered_at"));
    }

    private static String appendAnd(String where, String clause) {
        return where.isEmpty() ? " where " + clause : where + " and " + clause;
    }

    private static Instant ts(ResultSet rs, String column) throws SQLException {
        Timestamp t = rs.getTimestamp(column);
        return t == null ? null : t.toInstant();
    }
}
