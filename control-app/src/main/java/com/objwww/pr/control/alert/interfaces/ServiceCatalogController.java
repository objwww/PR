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
 * 服务目录（业界路线v2第4项，Backstage Catalog 模式）：以服务为单元聚合真数据——
 * 在警数/事故总数/累计接收/告警名清单/最近活动。owner 字段业界由目录配置提供，
 * 本期无配置源 → 如实 null（不冒充）。纯只读。
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
                select coalesce(service, '（未知服务）') as service,
                       count(*) filter (where status = 'FIRING') as firing,
                       count(*) filter (where status = 'RESOLVED') as resolved,
                       count(*) as total,
                       coalesce(sum(received_count), 0) as received_total,
                       max(last_event_at) as last_event_at,
                       string_agg(distinct alertname, ', ') as alerts
                  from incident
                 group by service
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
                    m.put("owner", null);
                    return m;
                })
                .list();
        body.put("status", "OK");
        body.put("items", items);
        body.put("count", items.size());
        return body;
    }
}
