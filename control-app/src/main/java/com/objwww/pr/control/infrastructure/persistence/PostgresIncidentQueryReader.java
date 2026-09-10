package com.objwww.pr.control.infrastructure.persistence;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.objwww.pr.control.alert.domain.repository.IncidentQueryReader;
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
 * {@link IncidentQueryReader} 的 Postgres 实现（UI-1 只读投影；JdbcClient 手写 SQL，
 * 沿 PostgresDutyStore 动态 where 惯例——参数缺席即不拼条件，避免 null 参数类型推断）。
 *
 * <p>severity/alertname/service 无 incident 独立列：每行经 LATERAL 取该 incident
 * 最新一条 alert_event（starts_at DESC, recorded_at DESC）的 labels jsonb 提取；
 * labels 无键 → null（PostgreSQL {@code ->>} 天然语义，不造默认值）。
 */
public class PostgresIncidentQueryReader implements IncidentQueryReader {

    /** 最新事件 labels 提取（service 兼容 service/service_name 两键） */
    private static final String LATEST_EVENT_LATERAL = """
            left join lateral (
                select e.labels ->> 'alertname' as alertname,
                       coalesce(e.labels ->> 'service', e.labels ->> 'service_name') as service,
                       e.labels ->> 'severity' as severity
                from alert_event e
                where e.incident_id = i.id
                order by e.starts_at desc, e.recorded_at desc
                limit 1
            ) le on true
            """;

    private static final String SELECT_ROW =
            "select i.id, i.incident_key, i.status, i.episode_started_at, i.last_event_at,"
                    + " i.resolved_at, i.received_count, i.distinct_event_count,"
                    + " i.notification_count, i.current_rca_run_id, i.waiting_reason,"
                    + " i.category, i.category_source,"
                    + " i.category_rule_id, i.category_rule_version, i.category_classified_at,"
                    + " i.override_actor, i.override_reason, i.override_at, i.override_revision,"
                    + " le.alertname, le.service, le.severity,"
                    + " r.state as run_state"
                    + " from incident i " + LATEST_EVENT_LATERAL
                    + " left join rca_run r on r.id = i.current_rca_run_id";

    private final JdbcClient jdbc;
    private final ObjectMapper mapper;

    public PostgresIncidentQueryReader(JdbcClient jdbc, ObjectMapper mapper) {
        this.jdbc = Objects.requireNonNull(jdbc);
        this.mapper = Objects.requireNonNull(mapper);
    }

    // ------------------------------------------------------------------ 列表

    @Override
    public IncidentPage listIncidents(String status, String severity, String service,
                                      String q, String category, KeysetCursor cursor,
                                      int limit) {
        Map<String, Object> filterParams = new LinkedHashMap<>();
        String where = filterWhere(status, severity, service, q, category, filterParams);

        // total = 过滤后全集（不含游标——游标只切页，不切总数）
        long total = jdbc.sql("select count(*) from incident i " + LATEST_EVENT_LATERAL + where)
                .params(filterParams)
                .query(Long.class).single();

        Map<String, Object> pageParams = new LinkedHashMap<>(filterParams);
        String pageWhere = where;
        if (cursor != null) {
            pageWhere = appendAnd(pageWhere,
                    "(i.last_event_at, i.id) < (:cursorAt, cast(:cursorId as uuid))");
            pageParams.put("cursorAt", Timestamp.from(cursor.at()));
            pageParams.put("cursorId", cursor.id().toString());
        }
        pageParams.put("lim", limit + 1);
        List<IncidentRow> rows = new ArrayList<>(jdbc.sql(
                        SELECT_ROW + pageWhere
                                + " order by i.last_event_at desc, i.id desc limit :lim")
                .params(pageParams)
                .query(this::mapRow).list());
        boolean hasMore = rows.size() > limit;
        if (hasMore) {
            rows = new ArrayList<>(rows.subList(0, limit));
        }
        return new IncidentPage(rows, total, hasMore);
    }

    // ------------------------------------------------------------------ 详情

    @Override
    public Optional<IncidentDetail> detail(UUID incidentId) {
        // UX-01：分类详情列随 SELECT_ROW 同行取出（行映射双产物：列表行 + 分类详情）
        record RowWithCategory(IncidentRow row, CategoryDetail categoryDetail) {
        }
        List<RowWithCategory> rows = jdbc.sql(SELECT_ROW + " where i.id = :id")
                .param("id", incidentId)
                .query((rs, i) -> new RowWithCategory(mapRow(rs, i), mapCategoryDetail(rs)))
                .list();
        if (rows.isEmpty()) {
            return Optional.empty();
        }
        IncidentRow row = rows.get(0).row();
        CategoryDetail categoryDetail = rows.get(0).categoryDetail();

        record LatestDocs(Map<String, Object> labels, Map<String, Object> annotations) {
        }
        Map<String, Object> labels = null;
        Map<String, Object> annotations = null;
        List<LatestDocs> latest = jdbc.sql("""
                select labels, annotations from alert_event
                where incident_id = :id
                order by starts_at desc, recorded_at desc
                limit 1
                """)
                .param("id", incidentId)
                .query((rs, i) -> new LatestDocs(jsonMap(rs, "labels"),
                        jsonMap(rs, "annotations")))
                .list();
        if (!latest.isEmpty()) {
            labels = latest.get(0).labels();
            annotations = latest.get(0).annotations();
        }

        List<TimelineEvent> timeline = jdbc.sql("""
                select id, status, starts_at, ends_at, labels, annotations
                from alert_event
                where incident_id = :id
                order by starts_at asc, recorded_at asc, id asc
                """)
                .param("id", incidentId)
                .query((rs, i) -> {
                    Map<String, Object> eventLabels = jsonMap(rs, "labels");
                    Object sev = eventLabels == null ? null : eventLabels.get("severity");
                    return new TimelineEvent(
                            rs.getObject("id", UUID.class), rs.getString("status"),
                            rs.getTimestamp("starts_at").toInstant(), ts(rs, "ends_at"),
                            sev == null ? null : String.valueOf(sev),
                            eventLabels, jsonMap(rs, "annotations"));
                }).list();

        RunBadge run = null;
        if (row.currentRcaRunId() != null) {
            List<RunBadge> badges = jdbc.sql(
                            "select id, state, started_at, finished_at from rca_run where id = :id")
                    .param("id", row.currentRcaRunId())
                    .query((rs, i) -> new RunBadge(rs.getObject("id", UUID.class),
                            rs.getString("state"), ts(rs, "started_at"), ts(rs, "finished_at")))
                    .list();
            run = badges.isEmpty() ? null : badges.get(0);
        }
        return Optional.of(new IncidentDetail(row, labels, annotations, timeline, run,
                categoryDetail));
    }

    // ------------------------------------------------------------------ facet

    @Override
    public Facets facets(String status, String service, String q) {
        Map<String, Long> statusFacet = groupCount(
                "i.status", status, service, q, false);
        Map<String, Long> severityFacet = groupCount(
                "le.severity", status, service, q, true);
        Map<String, Long> serviceFacet = groupCount(
                "le.service", status, service, q, true);
        // UX-01：生效面分桶（生成列恒非 null；计数口径 = 当前 status/service/q 过滤）
        Map<String, Long> categoryFacet = groupCount(
                "i.category", status, service, q, false);
        return new Facets(statusFacet, severityFacet, serviceFacet, categoryFacet);
    }

    /** 单维 GROUP BY 计数（skipNulls=labels 提取值无键的行不进桶） */
    private Map<String, Long> groupCount(String column, String status, String service,
                                         String q, boolean skipNulls) {
        Map<String, Object> params = new LinkedHashMap<>();
        String where = filterWhere(status, null, service, q, params);
        if (skipNulls) {
            where = appendAnd(where, column + " is not null");
        }
        Map<String, Long> out = new LinkedHashMap<>();
        jdbc.sql("select " + column + " as k, count(*) as c from incident i "
                        + LATEST_EVENT_LATERAL + where + " group by " + column + " order by c desc")
                .params(params)
                .query((rs, i) -> {
                    out.put(rs.getString("k"), rs.getLong("c"));
                    return null;
                }).list();
        return out;
    }

    // ------------------------------------------------------------------ 统计条

    @Override
    public IncidentSummary summary(Instant since) {
        Map<String, Long> bySeverity = new LinkedHashMap<>();
        long firingTotal = 0;
        List<long[]> counts = new ArrayList<>();
        jdbc.sql("select le.severity as k, count(*) as c from incident i "
                        + LATEST_EVENT_LATERAL
                        + " where i.status = 'FIRING' group by le.severity")
                .query((rs, i) -> {
                    if (rs.getString("k") != null) {
                        bySeverity.put(rs.getString("k"), rs.getLong("c"));
                    }
                    counts.add(new long[]{rs.getLong("c")});
                    return null;
                }).list();
        for (long[] c : counts) {
            firingTotal += c[0];
        }

        long unassigned = jdbc.sql(
                        "select count(*) from incident"
                                + " where status = 'FIRING' and current_rca_run_id is null")
                .query(Long.class).single();

        Map<String, Long> storm = jdbc.sql(
                        "select count(*) as received, count(distinct fingerprint) as events"
                                + " from alert_event where recorded_at >= :since")
                .param("since", Timestamp.from(since))
                .query((rs, i) -> Map.of(
                        "received", rs.getLong("received"), "events", rs.getLong("events")))
                .single();

        // 无近 24h resolved 行 → avg=null（诚实 null，不回填 0）
        // avg() 返回 numeric——PgResultSet.getObject(Double) 对 numeric 直拒
        // （195 真 PG 实证，UI-1 部署验证），先取 BigDecimal 再转；
        // single() 对 null 行值直抛（requiredSingleResult），诚实 null 必须走 optional()
        Double mttr = jdbc.sql(
                        "select avg(extract(epoch from (resolved_at - episode_started_at)) / 60.0)"
                                + " as mttr from incident where resolved_at >= :since")
                .param("since", Timestamp.from(since))
                .query((rs, i) -> {
                    java.math.BigDecimal v = rs.getBigDecimal("mttr");
                    return v == null ? null : v.doubleValue();
                })
                .optional().orElse(null);

        return new IncidentSummary(firingTotal, bySeverity, unassigned,
                storm.get("received"), storm.get("events"), mttr);
    }

    // ------------------------------------------------------------------ 总览

    @Override
    public AlertOverview overview(Instant now) {
        Timestamp at = Timestamp.from(now);
        Timestamp since = Timestamp.from(now.minus(java.time.Duration.ofHours(24)));
        RunsStats runs = jdbc.sql("""
                select
                  (select count(*) from rca_run
                     where state in ('QUEUED','RUNNING','REPORTING')) as active,
                  (select count(*) from rca_run
                     where state in ('SUCCEEDED','PARTIAL')) as awaiting_review,
                  (select count(*) from rca_run r
                     where r.state in ('QUEUED','RUNNING','REPORTING')
                       and exists (select 1 from rca_task t where t.run_id = r.id
                             and (t.state in ('BLOCKED','RETRY_WAIT')
                                  or (t.state = 'LEASED' and t.deadline_at <> 'infinity'
                                      and t.deadline_at < :now)))) as stuck,
                  (select count(*) from rca_run
                     where state in ('FAILED','EXPIRED')
                       and finished_at >= :since) as failed_24h,
                  (select (extract(epoch from (:now - min(ready_since))))::bigint
                     from rca_task where state = 'READY') as oldest_ready_wait
                """)
                .param("now", at)
                .param("since", since)
                .query((rs, i) -> new RunsStats(rs.getLong("active"),
                        rs.getLong("awaiting_review"), rs.getLong("stuck"),
                        rs.getLong("failed_24h"),
                        rs.getObject("oldest_ready_wait", Long.class)))
                .single();

        long firingIncidents = jdbc.sql("select count(*) from incident where status = 'FIRING'")
                .query(Long.class).single();

        // UTC 整点截断（AT TIME ZONE 往返，免会话 TimeZone 漂移）；缺桶由服务层补 0
        List<TrendBucket> trend = jdbc.sql("""
                select (date_trunc('hour', starts_at at time zone 'UTC') at time zone 'UTC')
                       as bucket_start,
                       count(*) filter (where status = 'firing') as received,
                       count(*) filter (where status = 'resolved') as resolved
                from alert_event
                where starts_at >= :since
                group by 1
                order by 1
                """)
                .param("since", since)
                .query((rs, i) -> new TrendBucket(rs.getTimestamp("bucket_start").toInstant(),
                        rs.getLong("received"), rs.getLong("resolved")))
                .list();

        return new AlertOverview(firingIncidents, runs, trend);
    }

    // ------------------------------------------------------------------ 内部

    /** 列表/facet 共用过滤（参数缺席即不拼条件——沿 PostgresDutyStore 惯例） */
    private static String filterWhere(String status, String severity, String service,
                                      String q, Map<String, Object> params) {
        return filterWhere(status, severity, service, q, null, params);
    }

    /** UX-01：category 生效面等值过滤（生成列 i.category；null=不过滤） */
    private static String filterWhere(String status, String severity, String service,
                                      String q, String category, Map<String, Object> params) {
        List<String> clauses = new ArrayList<>();
        if (status != null && !status.isBlank()) {
            clauses.add("i.status = :status");
            params.put("status", status);
        }
        if (severity != null && !severity.isBlank()) {
            clauses.add("le.severity = :severity");
            params.put("severity", severity);
        }
        if (service != null && !service.isBlank()) {
            clauses.add("le.service = :service");
            params.put("service", service);
        }
        if (q != null && !q.isBlank()) {
            clauses.add("(i.incident_key ilike '%' || :q || '%'"
                    + " or le.alertname ilike '%' || :q || '%')");
            params.put("q", q);
        }
        if (category != null && !category.isBlank()) {
            clauses.add("i.category = :category");
            params.put("category", category);
        }
        return clauses.isEmpty() ? "" : " where " + String.join(" and ", clauses);
    }

    private static String appendAnd(String where, String clause) {
        return where.isEmpty() ? " where " + clause : where + " and " + clause;
    }

    private IncidentRow mapRow(ResultSet rs, int rowNum) throws SQLException {
        return new IncidentRow(
                rs.getObject("id", UUID.class),
                rs.getString("incident_key"),
                rs.getString("alertname"),
                rs.getString("service"),
                rs.getString("severity"),
                rs.getString("status"),
                rs.getTimestamp("episode_started_at").toInstant(),
                rs.getTimestamp("last_event_at").toInstant(),
                ts(rs, "resolved_at"),
                rs.getLong("received_count"),
                rs.getLong("distinct_event_count"),
                rs.getLong("notification_count"),
                rs.getObject("current_rca_run_id", UUID.class),
                rs.getString("run_state"),
                rs.getString("waiting_reason"),
                rs.getString("category"),
                rs.getString("category_source"));
    }

    /** UX-01 分类详情列（SELECT_ROW 已含；无 override 时四列 null 如实返回） */
    private CategoryDetail mapCategoryDetail(ResultSet rs) throws SQLException {
        return new CategoryDetail(
                rs.getString("category_rule_id"),
                rs.getString("category_rule_version"),
                ts(rs, "category_classified_at"),
                rs.getString("override_actor"),
                rs.getString("override_reason"),
                ts(rs, "override_at"),
                rs.getObject("override_revision", Integer.class));
    }

    private Map<String, Object> jsonMap(ResultSet rs, String column) throws SQLException {
        String json = rs.getString(column);
        if (json == null) {
            return null;
        }
        try {
            return mapper.readValue(json, new TypeReference<>() {
            });
        } catch (Exception e) {
            throw new IllegalStateException(column + " jsonb 反序列化失败", e);
        }
    }

    private static Instant ts(ResultSet rs, String column) throws SQLException {
        Timestamp t = rs.getTimestamp(column);
        return t == null ? null : t.toInstant();
    }
}
