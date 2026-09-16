package com.objwww.pr.control.alert.interfaces;

import com.objwww.pr.control.infrastructure.auth.AuthenticatedActor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 复盘草稿（业界路线v2第5项，Rootly/incident.io Postmortem 模式）：
 * 已解决事故的复盘材料自动汇编——影响摘要/时间线/调查断言/调查次数/模型费用，
 * 全部真源实时聚合，零自由发挥；人只需要补"根因与改进项"两段。
 * 3.15 补强：整改项清单（action_items，V136）人工登记/闭环切换——
 * 复盘闭环为派生态（整改项全部 DONE 即闭环），根因仍由人补写，系统不代拟。
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
                select id, coalesce(substring(incident_key from 'alertname=([^|]+)'), '—') as alertname,
                       coalesce(substring(incident_key from 'service=([^|]+)'), '—') as service,
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
                select coalesce(substring(incident_key from 'alertname=([^|]+)'), '—') as alertname,
                       coalesce(substring(incident_key from 'service=([^|]+)'), '—') as service,
                       status, episode_started_at, resolved_at, received_count
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
                select '调用 ' || count(*) || ' 次：成功 ' || count(*) filter (where m.state = 'SUCCEEDED')
                       || '，失败 ' || count(*) filter (where m.state in ('FAILED','EXPIRED')) as summary,
                       coalesce(round(sum(m.cost_micros) / 1000000.0, 4)::text || ' '
                       || coalesce(max(m.currency), ''), '') as cost
                  from rca_model_call m join rca_run r on r.id = m.run_id
                 where r.incident_id = :id
                """).param("id", incidentId)
                .query((rs, i) -> Map.<String, Object>of(
                        "summary", (Object) rs.getString("summary"),
                        "cost", (Object) rs.getString("cost"))).list();
        body.put("investigation", runs.isEmpty() ? Map.of("summary", "无调查记录", "cost", "") : runs.get(0));
        // 最新 RCA 报告（复盘→评测候选提名的锚；无报告如实 null）
        List<String> latestReport = jdbc.sql("""
                select r.id::text from rca_report r
                  join rca_run rr on rr.id = r.run_id
                 where rr.incident_id = :id
                 order by r.created_at desc limit 1
                """).param("id", incidentId)
                .query((rs, i) -> rs.getString(1)).list();
        body.put("latestReportId", latestReport.isEmpty() ? null : latestReport.get(0));
        body.put("status", "OK");
        return body;
    }

    /** 整改项清单（Rootly/incident.io action items）：闭环为派生态——全部 DONE 即"复盘已闭环" */
    @GetMapping("/{incidentId}/action-items")
    public Map<String, Object> actionItems(@PathVariable UUID incidentId) {
        Map<String, Object> body = new LinkedHashMap<>();
        if (jdbc == null) {
            body.put("status", "UNAVAILABLE");
            body.put("reason", "DB_FACE_NOT_ASSEMBLED");
            return body;
        }
        List<Map<String, Object>> items = jdbc.sql("""
                select id, title, owner, state, created_by, created_at, done_at
                  from action_items where incident_id = :id order by created_at asc
                """).param("id", incidentId)
                .query((rs, i) -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("id", rs.getObject("id", UUID.class).toString());
                    m.put("title", rs.getString("title"));
                    m.put("owner", rs.getString("owner"));
                    m.put("state", rs.getString("state"));
                    m.put("createdBy", rs.getString("created_by"));
                    m.put("createdAt", rs.getTimestamp("created_at").toInstant().toString());
                    m.put("doneAt", rs.getTimestamp("done_at") == null
                            ? null : rs.getTimestamp("done_at").toInstant().toString());
                    return m;
                }).list();
        long done = items.stream().filter(it -> "DONE".equals(it.get("state"))).count();
        body.put("status", "OK");
        body.put("items", items);
        body.put("count", items.size());
        body.put("doneCount", done);
        body.put("closed", !items.isEmpty() && done == items.size());
        return body;
    }

    /** 新增整改项（人工登记真数据；系统不代拟——YAGNI 于自动生成改进建议） */
    @PostMapping(path = "/{incidentId}/action-items", consumes = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> addActionItem(@PathVariable UUID incidentId,
            @RequestBody Map<String, Object> body) {
        String title = str(body.get("title"));
        String owner = str(body.get("owner"));
        if (title == null || title.isBlank()) {
            return Map.of("status", "REJECTED", "reason", "TITLE_REQUIRED");
        }
        if (jdbc == null) {
            return Map.of("status", "UNAVAILABLE", "reason", "DB_FACE_NOT_ASSEMBLED");
        }
        List<UUID> exists = jdbc.sql("select id from incident where id = :id")
                .param("id", incidentId)
                .query((rs, i) -> rs.getObject("id", UUID.class)).list();
        if (exists.isEmpty()) {
            return Map.of("status", "REJECTED", "reason", "NOT_FOUND");
        }
        jdbc.sql("""
                insert into action_items (id, incident_id, title, owner, created_by)
                values (:id, :incidentId, :title, :owner, :by)
                """)
                .param("id", UUID.randomUUID())
                .param("incidentId", incidentId)
                .param("title", title.trim())
                .param("owner", owner == null || owner.isBlank() ? null : owner.trim())
                .param("by", AuthenticatedActor.name())
                .update();
        return Map.of("status", "OK");
    }

    /** 整改项闭环切换（OPEN↔DONE；done_at 随状态落真值，两态互斥不另设字段） */
    @PostMapping("/{incidentId}/action-items/{itemId}/toggle")
    public Map<String, Object> toggleActionItem(@PathVariable UUID incidentId, @PathVariable UUID itemId) {
        if (jdbc == null) {
            return Map.of("status", "UNAVAILABLE", "reason", "DB_FACE_NOT_ASSEMBLED");
        }
        List<String> updated = jdbc.sql("""
                update action_items set
                    state = case when state = 'OPEN' then 'DONE' else 'OPEN' end,
                    done_at = case when state = 'OPEN' then now() else null end
                 where id = :itemId and incident_id = :incidentId
                 returning state
                """)
                .param("itemId", itemId)
                .param("incidentId", incidentId)
                .query((rs, i) -> rs.getString("state")).list();
        if (updated.isEmpty()) {
            return Map.of("status", "REJECTED", "reason", "NOT_FOUND");
        }
        return Map.of("status", "OK", "state", updated.get(0));
    }

    private static String str(Object value) {
        return value == null ? null : String.valueOf(value);
    }
}
