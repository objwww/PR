package com.objwww.pr.control.alert.interfaces;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * ITSM 工单草稿（业界路线v2第6项，Rootly/Jira 出单模式的第一阶段）：
 * 从真源汇编工单字段（标题/描述=影响+时间线+断言+费用/优先级=严重度映射）落库 DRAFT。
 * 真推送需外部系统配置（app.itsm.base-url）——未配置阶段草稿可导出粘贴进工单系统，
 * 不提供假"已提交"状态。纯真源。
 */
@RestController
@RequestMapping("/api/v1/itsm")
public class ItsmTicketController {

    private final JdbcClient jdbc;

    public ItsmTicketController(ObjectProvider<JdbcClient> jdbc) {
        this.jdbc = jdbc.getIfAvailable();
    }

    /** 生成工单草稿（DRAFT 状态；真推送待外部配置，属后续增量） */
    @PostMapping("/incidents/{incidentId}/draft")
    public Map<String, Object> draft(@PathVariable UUID incidentId,
            @RequestParam(name = "createdBy", defaultValue = "operator") String createdBy) {
        if (jdbc == null) {
            return Map.of("status", "UNAVAILABLE", "reason", "DB_FACE_NOT_ASSEMBLED");
        }
        List<Map<String, Object>> impact = jdbc.sql("""
                select coalesce(alertname, '—') as alertname, coalesce(service, '—') as service,
                       severity, received_count, episode_started_at
                  from incident where id = :id
                """).param("id", incidentId)
                .query((rs, i) -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("alertname", rs.getString("alertname"));
                    m.put("service", rs.getString("service"));
                    m.put("severity", rs.getString("severity"));
                    m.put("receivedCount", rs.getLong("received_count"));
                    m.put("episodeStartedAt", rs.getTimestamp("episode_started_at").toInstant().toString());
                    return m;
                }).list();
        if (impact.isEmpty()) {
            return Map.of("status", "REJECTED", "reason", "NOT_FOUND");
        }
        Map<String, Object> im = impact.get(0);
        List<String> claims = jdbc.sql("""
                select coalesce(c.reason, '') from rca_claim c
                  join rca_run r on r.id = c.run_id
                 where r.incident_id = :id limit 5
                """).param("id", incidentId).query((rs, i) -> rs.getString(1)).list();
        List<String> cost = jdbc.sql("""
                select coalesce('模型费用 ' || round(sum(cost_micros) / 1000000.0, 4) || ' '
                       || coalesce(max(currency), ''), '模型费用：无可计价调用')
                  from rca_model_call m join rca_run r on r.id = m.run_id
                 where r.incident_id = :id
                """).param("id", incidentId).query((rs, i) -> rs.getString(1)).list();
        String title = "[告警] " + im.get("alertname") + " @ " + im.get("service");
        String description = "影响：服务 " + im.get("service") + "，累计接收 " + im.get("receivedCount")
                + " 次（自 " + String.valueOf(im.get("episodeStartedAt")).replace('T', ' ').substring(0, 16)
                + "）。\n调查假设：" + (claims.isEmpty() ? "无结构化断言" : String.join("；", claims))
                + "\n" + cost.get(0) + "。\n（本工单由系统按真源自动汇编，人可修订）";
        String priority = "critical".equals(String.valueOf(im.get("severity"))) ? "P0"
                : "high".equals(String.valueOf(im.get("severity"))) ? "P1" : "P2";
        UUID ticketId = UUID.randomUUID();
        Instant now = Instant.now();
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("title", title);
        payload.put("description", description);
        payload.put("priority", priority);
        payload.put("incident_id", incidentId.toString());
        jdbc.sql("""
                insert into itsm_ticket (id, incident_id, system_name, title, description,
                    priority, state, created_by, created_at)
                values (:id, :incidentId, 'DRAFT_EXPORT', :title, :description, :priority,
                    'DRAFT', :createdBy, :at)
                """)
                .param("id", ticketId)
                .param("incidentId", incidentId)
                .param("title", title)
                .param("description", description)
                .param("priority", priority)
                .param("createdBy", createdBy)
                .param("at", Timestamp.from(now))
                .update();
        Map<String, Object> body = new LinkedHashMap<>(payload);
        body.put("status", "OK");
        body.put("ticket_id", ticketId.toString());
        body.put("state", "DRAFT");
        return body;
    }

    /** 草稿列表（复盘页/工单页消费） */
    @GetMapping("/tickets")
    public Map<String, Object> tickets() {
        if (jdbc == null) {
            return Map.of("status", "UNAVAILABLE", "reason", "DB_FACE_NOT_ASSEMBLED");
        }
        List<Map<String, Object>> items = jdbc.sql("""
                select t.id, t.title, t.priority, t.state, t.created_by, t.created_at,
                       coalesce(i.alertname, '') as alertname
                  from itsm_ticket t left join incident i on i.id = t.incident_id
                 order by t.created_at desc limit 50
                """)
                .query((rs, i) -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("id", rs.getObject("id", UUID.class).toString());
                    m.put("title", rs.getString("title"));
                    m.put("priority", rs.getString("priority"));
                    m.put("state", rs.getString("state"));
                    m.put("createdBy", rs.getString("created_by"));
                    m.put("createdAt", rs.getTimestamp("created_at").toInstant().toString());
                    m.put("alertname", rs.getString("alertname"));
                    return m;
                })
                .list();
        return Map.of("status", "OK", "items", items, "count", items.size());
    }
}
