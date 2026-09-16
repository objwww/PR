package com.objwww.pr.control.alert.interfaces;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 调查路由可视化（前端产品化补缺：回答"有告警为什么没有 RCA"）：
 * AI 自动调查走 canary 粘性桶位路由——告警键哈希桶 vs 当前放量百分比（percent
 * 由发布管线 bundle 激活驱动），决策全账本落账（canary_route_decision）。
 * 本面为该账本的只读可视化三段：
 * <ul>
 *   <li>latest——每告警键最新放量与决策（percent/decision/时间）；</li>
 *   <li>recent——最近 20 条决策流水；</li>
 *   <li>waiting——FIRING 且 WAITING_CAPABILITY 的事件清单（为什么还没调查）。</li>
 * </ul>
 * 写面说明（README 同律）：放量百分比由发布管线（版本中心 bundle 激活）驱动，
 * 告警域不设放量开关，本页纯只读；BUCKETED_HOLMES=桶位在放量内但 NATIVE 执行面
 * 未就绪降级 HOLMES（M6-07 起不铸 run），页面如实分态展示。
 */
@RestController
@RequestMapping("/api/v1/routing")
public class RoutingOverviewController {

    private final JdbcClient jdbc;

    public RoutingOverviewController(ObjectProvider<JdbcClient> jdbc) {
        this.jdbc = jdbc.getIfAvailable();
    }

    @GetMapping("/overview")
    public Map<String, Object> overview() {
        Map<String, Object> body = new LinkedHashMap<>();
        if (jdbc == null) {
            body.put("status", "UNAVAILABLE");
            body.put("reason", "DB_FACE_NOT_ASSEMBLED");
            return body;
        }
        List<Map<String, Object>> latest = jdbc.sql("""
                select distinct on (stickiness_key)
                       stickiness_key as key,
                       replace(split_part(split_part(stickiness_key, '|', 2), ':', 1), 'service=', '') as service,
                       percent, decision, created_at
                  from canary_route_decision
                 order by stickiness_key, created_at desc
                """)
                .query((rs, i) -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("key", rs.getString("key"));
                    m.put("service", rs.getString("service"));
                    m.put("percent", rs.getInt("percent"));
                    m.put("decision", rs.getString("decision"));
                    m.put("at", rs.getTimestamp("created_at").toInstant().toString());
                    return m;
                }).list();
        List<Map<String, Object>> recent = jdbc.sql("""
                select created_at, stickiness_key as key, canary_bucket, percent, decision
                  from canary_route_decision
                 order by created_at desc limit 20
                """)
                .query((rs, i) -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("at", rs.getTimestamp("created_at").toInstant().toString());
                    m.put("key", rs.getString("key"));
                    m.put("bucket", rs.getInt("canary_bucket"));
                    m.put("percent", rs.getInt("percent"));
                    m.put("decision", rs.getString("decision"));
                    return m;
                }).list();
        List<Map<String, Object>> waiting = jdbc.sql("""
                select i.id, substring(i.incident_key from 'alertname=([^|]+)') as alertname,
                       substring(i.incident_key from 'service=([^|]+)') as service,
                       i.first_seen_at, i.received_count, i.waiting_reason
                  from incident i
                 where i.status = 'FIRING' and i.waiting_reason is not null
                 order by i.first_seen_at desc limit 20
                """)
                .query((rs, i) -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("incidentId", rs.getObject("id", java.util.UUID.class).toString());
                    m.put("alertname", rs.getString("alertname"));
                    m.put("service", rs.getString("service"));
                    m.put("firstSeenAt", rs.getTimestamp("first_seen_at") == null
                            ? null : rs.getTimestamp("first_seen_at").toInstant().toString());
                    m.put("receivedCount", rs.getLong("received_count"));
                    m.put("waitingReason", rs.getString("waiting_reason"));
                    return m;
                }).list();
        body.put("status", "OK");
        body.put("latest", latest);
        body.put("recent", recent);
        body.put("waiting", waiting);
        body.put("counts", Map.of(
                "latest", latest.size(), "recent", recent.size(), "waiting", waiting.size()));
        return body;
    }
}
