package com.objwww.pr.control.alert.interfaces;

import com.objwww.pr.control.infrastructure.auth.AuthenticatedActor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 服务目录（业界路线v2第4项 + 前端产品化 3.13 补强，Backstage Catalog 模式）：
 * 以服务为单元聚合真数据——在警/已解决/事故总数/累计接收/告警名/最近活动 +
 * 近 30 天事件数与 MTTR（30 天内已解决事件的 mean(resolved_at-first_seen_at)）+
 * 负责人（service_owner 人工登记表，V135——Backstage owner 语义，写面 OPERATOR）。
 */
@RestController
@RequestMapping("/api/v1/catalog")
public class ServiceCatalogController {

    private final JdbcClient jdbc;

    public ServiceCatalogController(ObjectProvider<JdbcClient> jdbc) {
        this.jdbc = jdbc.getIfAvailable();
    }

    @GetMapping
    public Map<String, Object> services() {
        Map<String, Object> body = new LinkedHashMap<>();
        if (jdbc == null) {
            body.put("status", "UNAVAILABLE");
            body.put("reason", "DB_FACE_NOT_ASSEMBLED");
            return body;
        }
        List<Map<String, Object>> items = jdbc.sql("""
                select t.service,
                       count(*) filter (where t.status = 'FIRING') as firing,
                       count(*) filter (where t.status = 'RESOLVED') as resolved,
                       count(*) as total,
                       coalesce(sum(t.received_count), 0) as received_total,
                       max(t.last_event_at) as last_event_at,
                       string_agg(distinct substring(t.incident_key from 'alertname=([^|]+)'), ', ') as alerts,
                       count(*) filter (where t.first_seen_at >= now() - interval '30 days') as events_30d,
                       avg(case when t.status = 'RESOLVED' and t.resolved_at is not null
                                     and t.resolved_at >= now() - interval '30 days'
                            then extract(epoch from (t.resolved_at - t.first_seen_at)) end) as mttr_30d_sec,
                       o.owner as owner
                  from (select i.*, coalesce(substring(i.incident_key from 'service=([^|]+)'), '（未知服务）') as service
                          from incident i) t
                  left join service_owner o on o.service_name = t.service
                 group by t.service, o.owner
                 order by firing desc, total desc
                """)
                .query((rs, i) -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("service", rs.getString("service"));
                    m.put("firing", rs.getLong("firing"));
                    m.put("resolved", rs.getLong("resolved"));
                    m.put("total", rs.getLong("total"));
                    m.put("receivedTotal", rs.getLong("received_total"));
                    m.put("lastEventAt", rs.getTimestamp("last_event_at") == null
                            ? null : rs.getTimestamp("last_event_at").toInstant().toString());
                    m.put("alerts", rs.getString("alerts"));
                    m.put("events30d", rs.getLong("events_30d"));
                    Double mttr = rs.getObject("mttr_30d_sec") == null
                            ? null : rs.getDouble("mttr_30d_sec");
                    m.put("mttr30dSec", mttr == null ? null : Math.round(mttr));
                    m.put("owner", rs.getString("owner"));
                    return m;
                })
                .list();
        body.put("status", "OK");
        body.put("items", items);
        body.put("count", items.size());
        return body;
    }

    /** 负责人登记（upsert；Backstage owner 语义——人工配置真数据，可清空） */
    @PostMapping(path = "/owner", consumes = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> setOwner(@RequestBody Map<String, Object> body) {
        String service = str(body.get("service"));
        String owner = str(body.get("owner"));
        String note = str(body.get("note"));
        if (service == null || service.isBlank()) {
            return Map.of("status", "REJECTED", "reason", "SERVICE_REQUIRED");
        }
        if (jdbc == null) {
            return Map.of("status", "UNAVAILABLE", "reason", "DB_FACE_NOT_ASSEMBLED");
        }
        if (owner == null || owner.isBlank()) {
            jdbc.sql("delete from service_owner where service_name = :service")
                    .param("service", service.trim()).update();
            return Map.of("status", "OK", "owner", null);
        }
        jdbc.sql("""
                insert into service_owner (service_name, owner, note, tagged_by)
                 values (:service, :owner, :note, :by)
                 on conflict (service_name)
                 do update set owner = excluded.owner, note = excluded.note,
                               tagged_by = excluded.tagged_by, tagged_at = now()
                """)
                .param("service", service.trim())
                .param("owner", owner.trim())
                .param("note", note)
                .param("by", AuthenticatedActor.name())
                .update();
        return Map.of("status", "OK", "owner", owner.trim());
    }

    private static String str(Object value) {
        return value == null ? null : String.valueOf(value);
    }
}
