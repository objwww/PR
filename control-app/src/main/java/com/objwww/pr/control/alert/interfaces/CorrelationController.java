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
 * 告警关联视图（业界路线v2第3项，Moogsoft/Datadog Watchdog 聚类模式）：
 * 同服务且最近30分钟内有告警活动的事故聚为一组（疑似同因），组内按最近事件倒序。
 * 聚类口径诚实：同服务+时间窗重叠是关联的必要条件而非充分条件——是否同因由人判断。
 * 纯只读。
 */
@RestController
@RequestMapping("/api/v1/correlation")
public class CorrelationController {

    private final JdbcClient jdbc;

    public CorrelationController(ObjectProvider<JdbcClient> jdbc) {
        this.jdbc = jdbc.getIfAvailable();
    }

    @GetMapping
    public Map<String, Object> groups() {
        Map<String, Object> body = new LinkedHashMap<>();
        if (jdbc == null) {
            body.put("status", "UNAVAILABLE");
            body.put("reason", "DB_FACE_NOT_ASSEMBLED");
            return body;
        }
        List<Map<String, Object>> groups = jdbc.sql("""
                select coalesce(substring(i.incident_key from 'service=([^|]+)'), '（未知服务）') as service,
                       count(*) as firing_count,
                       sum(i.received_count) as received_total,
                       max(i.last_event_at) as last_event_at,
                       string_agg(coalesce(substring(i.incident_key from 'alertname=([^|]+)'), i.incident_key), ', ' order by i.last_event_at desc) as alerts
                  from incident i
                 where i.status = 'FIRING'
                   and i.last_event_at > now() - interval '30 minutes'
                 group by i.incident_key
                having count(*) > 1
                 order by firing_count desc, last_event_at desc
                """)
                .query((rs, i) -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("service", rs.getString("service"));
                    m.put("firingCount", rs.getLong("firing_count"));
                    m.put("receivedTotal", rs.getLong("received_total"));
                    m.put("lastEventAt", rs.getTimestamp("last_event_at").toInstant().toString());
                    m.put("alerts", rs.getString("alerts"));
                    m.put("hint", "同服务 30 分钟窗口内多告警并发——疑似同一根因，建议合并排查");
                    return m;
                })
                .list();
        body.put("status", "OK");
        body.put("groups", groups);
        body.put("count", groups.size());
        body.put("window", "30 分钟");
        return body;
    }
}
