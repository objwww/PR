package com.objwww.pr.control.alert.interfaces;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 复盘草稿（业界路线v2第5项，Rootly/incident.io Postmortem 模式）：
 * 已解决事故的复盘材料自动汇编——影响摘要/时间线/调查断言/调查次数/模型费用，
 * 全部真源实时聚合，零自由发挥；人只需要补"根因与改进项"两段。
 * 纯只读。
 */
@RestController
@RequestMapping("/api/v1/postmortems")
public class PostmortemController {

    private final JdbcClient jdbc;

    public PostmortemController(ObjectProvider<JdbcClient> jdbc) {
        this.jdbc = jdbc.getIfAvailable();
    }

    /** 已解决事故清单（复盘候选） */
    @GetMapping
    public Map<String, Object> list() {
        Map<String, Object> body = new LinkedHashMap<>();
        if (jdbc == null) {
            body.put("status", "UNAVAILABLE");
            body.put("reason", "DB_FACE_NOT_ASSEMBLED");
            return body;
        }
        List<Map<String, Object>> items = jdbc.sql("""
                select id, alertname, coalesce(service, '') as service,
                       episode_started_at, resolved_at, received_count
                  from incident where status = 'RESOLVED'
                 order by resolved_at desc nulls last limit 50
                """)
                .query((rs, i) -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("incidentId", rs.getObject("id", java.util.UUID.class).toString());
                    m.put("alertname", rs.getString("alertname"));
                    m.put("service", rs.getString("service"));
                    m.put("episodeStartedAt", rs.getTimestamp("episode_started_at").toInstant().toString());
                    m.put("resolvedAt", rs.getTimestamp("resolved_at") == null
                            ? null : rs.getTimestamp("resolved_at").toInstant().toString());
                    m.put("receivedCount", rs.getLong("received_count"));
                    return m;
                })
                .list();
        body.put("status", "OK");
        body.put("items", items);
        body.put("count", items.size());
        return body;
    }

    /** 单事故复盘草稿：影响 + 时间线 + 断言 + 调查统计 + 模型费用（全真源） */
    @GetMapping("/{incidentId}")
    public Map<String, Object> detail(@PathVariable java.util.UUID incidentId) {
        Map<String, Object> body = new LinkedHashMap<>();
        if (jdbc == null) {
            body.put("status", "UNAVAILABLE");
            body.put("reason", "DB_FACE_NOT_ASSEMBLED");
            return body;
        }
        List<Map<String, Object>> impact = jdbc.sql("""
                select alertname, coalesce(service, '') as service, status,
                       episode_started_at, resolved_at, received_count
                  from incident where id = :id
                """).param("id", incidentId)
                .query((rs, i) -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("alertname", rs.getString("alertname"));
                    m.put("service", rs.getString("service"));
                    m.put("status", rs.getString("status"));
                    m.put("episodeStartedAt", rs.getTimestamp("episode_started_at").toInstant().toString());
                    m.put("resolvedAt", rs.getTimestamp("resolved_at") == null
                            ? null : rs.getTimestamp("resolved_at").toInstant().toString());
                    m.put("receivedCount", rs.getLong("received_count"));
                    return m;
                }).list();
        if (impact.isEmpty()) {
            return Map.of("status", "REJECTED", "reason", "NOT_FOUND");
        }
        body.put("impact", impact.get(0));
        List<Map<String, Object>> timeline = jdbc.sql("""
                select status, starts_at, ends_at from alert_event
                 where incident_id = :id order by starts_at asc limit 50
                """).param("id", incidentId)
                .query((rs, i) -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("status", rs.getString("status"));
                    m.put("startsAt", rs.getTimestamp("starts_at").toInstant().toString());
                    m.put("endsAt", rs.getTimestamp("ends_at") == null
                            ? null : rs.getTimestamp("ends_at").toInstant().toString());
                    return m;
                }).list();
        body.put("timeline", timeline);
        List<Map<String, Object>> claims = jdbc.sql("""
                select coalesce(c.kind, 'ASSERTION') as kind, c.status, c.reason
                  from rca_claim c join rca_run r on r.id = c.run_id
                 where r.incident_id = :id order by c.claim_key
                """).param("id", incidentId)
                .query((rs, i) -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("kind", rs.getString("kind"));
                    m.put("status", rs.getString("status"));
                    m.put("reason", rs.getString("reason"));
                    return m;
                }).list();
        body.put("claims", claims);
        List<Map<String, Object>> runs = jdbc.sql("""
                select 'runs=' || count(*) || '：成功 ' || count(*) filter (where state = 'SUCCEEDED')
                       || '，失败 ' || count(*) filter (where state in ('FAILED','EXPIRED')) as summary,
                       coalesce(round(sum(cost_micros) / 1000000.0, 4)::text || ' ' || coalesce(max(currency), ''), '')
                       as cost from rca_model_call
                """).param("id", incidentId)
                .query((rs, i) -> Map.<String, Object>of(
                        "summary", (Object) rs.getString("summary"),
                        "cost", (Object) rs.getString("cost"))).list();
        body.put("investigation", runs.isEmpty() ? Map.of("summary", "无调查记录", "cost", "") : runs.get(0));
        body.put("status", "OK");
        return body;
    }
}
