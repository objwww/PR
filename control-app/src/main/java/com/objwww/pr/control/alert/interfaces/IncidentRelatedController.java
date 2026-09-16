package com.objwww.pr.control.alert.interfaces;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 告警详情关联面（前端产品化 3.3 补齐，方案 §3.3 Wave2 两缺口；全 GET 零写面）：
 * <ul>
 *   <li>GET /api/v1/incidents/{id}/related——关联告警：同 incident_key 历史 episode
 *       （同键重开/复发史）+ 同服务近 24h 并发告警（PagerDuty Related/Moogsoft 聚类同律）；
 *       服务键缺失时 sameService 如实空列表，不放宽为全量；</li>
 *   <li>GET /api/v1/incidents/{id}/timeline-merged——合并时间轴：告警事件
 *       （alert_event）+ 同服务变更（change_event，episode 窗口 ±1h）+
 *       演练（drill_job：关联本事故或同窗），按时间归一排序——变更/演练与告警
 *       同轴是根因关联的第一线索（incident.io Links/Grafana Annotations 同律）。
 *       调查事件不入本轴：已由「调查」页签与 Trace 页签承载，避免重复噪声。</li>
 * </ul>
 * 纯只读聚合，零迁移——全部既有表 control_app 既有 SELECT 授权。
 */
@RestController
@RequestMapping("/api/v1/incidents")
public class IncidentRelatedController {

    private final JdbcClient jdbc;

    public IncidentRelatedController(ObjectProvider<JdbcClient> jdbc) {
        this.jdbc = jdbc.getIfAvailable();
    }

    @GetMapping("/{incidentId}/related")
    public Map<String, Object> related(@PathVariable UUID incidentId) {
        Map<String, Object> body = new LinkedHashMap<>();
        if (jdbc == null) {
            body.put("status", "UNAVAILABLE");
            body.put("reason", "DB_FACE_NOT_ASSEMBLED");
            return body;
        }
        List<Map<String, Object>> sameKey = jdbc.sql("""
                select id, status, episode_started_at, resolved_at, received_count
                  from incident
                 where incident_key = (select incident_key from incident where id = :id)
                   and id <> :id
                 order by episode_started_at desc limit 20
                """).param("id", incidentId)
                .query((rs, i) -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("incidentId", rs.getObject("id", UUID.class).toString());
                    m.put("status", rs.getString("status"));
                    m.put("episodeStartedAt", rs.getTimestamp("episode_started_at").toInstant().toString());
                    m.put("resolvedAt", rs.getTimestamp("resolved_at") == null
                            ? null : rs.getTimestamp("resolved_at").toInstant().toString());
                    m.put("receivedCount", rs.getLong("received_count"));
                    return m;
                }).list();
        // 同服务并发：服务键提取为空（异常 incident_key）时如实空列表，不放宽为全量
        List<Map<String, Object>> sameService = jdbc.sql("""
                select id, coalesce(substring(incident_key from 'alertname=([^|]+)'), '—') as alertname,
                       status, first_seen_at, resolved_at
                  from incident
                 where id <> :id
                   and coalesce(substring(incident_key from 'service=([^|]+)'), '') <>
                       ''
                   and coalesce(substring(incident_key from 'service=([^|]+)'), '') =
                       (select coalesce(substring(incident_key from 'service=([^|]+)'), '')
                          from incident where id = :id)
                   and first_seen_at >= now() - interval '24 hours'
                 order by first_seen_at desc limit 20
                """).param("id", incidentId)
                .query((rs, i) -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("incidentId", rs.getObject("id", UUID.class).toString());
                    m.put("alertname", rs.getString("alertname"));
                    m.put("status", rs.getString("status"));
                    m.put("firstSeenAt", rs.getTimestamp("first_seen_at") == null
                            ? null : rs.getTimestamp("first_seen_at").toInstant().toString());
                    m.put("resolvedAt", rs.getTimestamp("resolved_at") == null
                            ? null : rs.getTimestamp("resolved_at").toInstant().toString());
                    return m;
                }).list();
        body.put("status", "OK");
        body.put("sameKey", sameKey);
        body.put("sameService24h", sameService);
        return body;
    }

    @GetMapping("/{incidentId}/timeline-merged")
    public Map<String, Object> timelineMerged(@PathVariable UUID incidentId) {
        Map<String, Object> body = new LinkedHashMap<>();
        if (jdbc == null) {
            body.put("status", "UNAVAILABLE");
            body.put("reason", "DB_FACE_NOT_ASSEMBLED");
            return body;
        }
        List<Map<String, Object>> ep = jdbc.sql(
                "select coalesce(substring(incident_key from 'service=([^|]+)'), '') as service,"
                + " episode_started_at, resolved_at from incident where id = :id")
                .param("id", incidentId)
                .query((rs, i) -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("service", rs.getString("service"));
                    m.put("ep", rs.getTimestamp("episode_started_at").toInstant());
                    m.put("res", rs.getTimestamp("resolved_at") == null
                            ? Instant.now() : rs.getTimestamp("resolved_at").toInstant());
                    return m;
                }).list();
        if (ep.isEmpty()) {
            return Map.of("status", "REJECTED", "reason", "NOT_FOUND");
        }
        String service = (String) ep.get(0).get("service");
        Instant windowStart = ((Instant) ep.get(0).get("ep")).minusSeconds(3600);
        Instant windowEnd = ((Instant) ep.get(0).get("res")).plusSeconds(3600);

        List<Map<String, Object>> items = new ArrayList<>();
        jdbc.sql("""
                select status, starts_at, ends_at from alert_event
                 where incident_id = :id and starts_at is not null order by starts_at limit 100
                """).param("id", incidentId)
                .query((rs, i) -> {
                    boolean firing = "firing".equals(rs.getString("status"));
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("kind", firing ? "ALERT_FIRING" : "ALERT_RESOLVED");
                    m.put("at", rs.getTimestamp("starts_at").toInstant());
                    m.put("title", firing ? "告警触发" : "告警恢复");
                    return m;
                }).list().forEach(items::add);
        // 同服务变更：仅 episode 窗口 ±1h 内（全量变更是噪声，窗口内才是可关联线索）
        if (!service.isBlank()) {
            jdbc.sql("""
                    select action, source, actor, status, started_at from change_event
                     where service = :service and started_at >= :from and started_at <= :to
                     order by started_at desc limit 20
                    """)
                    .param("service", service)
                    .param("from", Timestamp.from(windowStart))
                    .param("to", Timestamp.from(windowEnd))
                    .query((rs, i) -> {
                        Map<String, Object> m = new LinkedHashMap<>();
                        m.put("kind", "CHANGE");
                        m.put("at", rs.getTimestamp("started_at").toInstant());
                        m.put("title", "变更：服务 " + service + " " + rs.getString("action")
                                + "（" + rs.getString("source") + "，状态 " + rs.getString("status")
                                + "，操作 " + (rs.getString("actor") == null ? "—" : rs.getString("actor")) + "）");
                        return m;
                    }).list().forEach(items::add);
        }
        jdbc.sql("""
                select scenario_name, state, created_at from drill_job
                 where related_incident_id = :id
                    or (created_at >= :from and created_at <= :to)
                 order by created_at desc limit 20
                """)
                .param("id", incidentId)
                .param("from", Timestamp.from(windowStart))
                .param("to", Timestamp.from(windowEnd))
                .query((rs, i) -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("kind", "DRILL");
                    m.put("at", rs.getTimestamp("created_at").toInstant());
                    m.put("title", "演练：" + rs.getString("scenario_name") + "（" + rs.getString("state") + "）");
                    return m;
                }).list().forEach(items::add);
        items.sort((a, b) -> ((Instant) a.get("at")).compareTo((Instant) b.get("at")));
        body.put("status", "OK");
        body.put("items", items);
        body.put("count", items.size());
        body.put("window", Map.of("from", windowStart.toString(), "to", windowEnd.toString()));
        return body;
    }
}
