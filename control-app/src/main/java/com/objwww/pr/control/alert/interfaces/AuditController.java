package com.objwww.pr.control.alert.interfaces;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 审计与变更历史真源面（业界对齐 PagerDuty Audit Logs / LaunchDarkly 审计流）：
 * 认证事件（auth_event）+ 配置变更事件（change_event）按时间倒序统一流，
 * SRE"变更四问"（谁/何时/改了什么/为什么）页面化。纯只读。
 */
@RestController
@RequestMapping("/api/v1/audit")
public class AuditController {

    private final JdbcClient jdbc;

    public AuditController(ObjectProvider<JdbcClient> jdbc) {
        this.jdbc = jdbc.getIfAvailable();
    }

    @GetMapping
    public Map<String, Object> list(@RequestParam(name = "limit", defaultValue = "100") int limit) {
        Map<String, Object> body = new LinkedHashMap<>();
        if (jdbc == null) {
            body.put("status", "UNAVAILABLE");
            body.put("reason", "DB_FACE_NOT_ASSEMBLED");
            return body;
        }
        int cap = Math.min(Math.max(limit, 1), 300);
        List<Map<String, Object>> items = new ArrayList<>();
        // JdbcClient.query(RowMapper) 返回惰性 MappedQuerySpec——必须挂终端操作（.list()）
        // 才真正发 SQL；裸调用零执行、items 恒空（195 实测审计页恒空根因）
        items.addAll(jdbc.sql("select actor, event_type, remote_addr, coalesce(detail,'') as detail, occurred_at"
                        + " from auth_event order by occurred_at desc limit " + cap)
                .query((rs, i) -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("source", "认证");
                    m.put("actor", rs.getString("actor"));
                    m.put("action", rs.getString("event_type"));
                    m.put("detail", rs.getString("detail"));
                    m.put("extra", rs.getString("remote_addr"));
                    m.put("occurredAt", rs.getTimestamp("occurred_at").toInstant().toString());
                    return m;
                })
                .list());
        items.addAll(jdbc.sql("select actor, action, coalesce(service,'') as service, coalesce(config_digest,'') as config_digest,"
                        + " coalesce(status,'') as status, coalesce(rollback_of::text,'') as rollback_of, created_at"
                        + " from change_event order by created_at desc limit " + cap)
                .query((rs, i) -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("source", "配置变更");
                    m.put("actor", rs.getString("actor"));
                    m.put("action", rs.getString("action"));
                    m.put("detail", "service=" + rs.getString("service")
                            + (rs.getString("config_digest").isEmpty() ? ""
                            : " config=" + rs.getString("config_digest").substring(0, 12))
                            + (rs.getString("rollback_of").isEmpty() ? ""
                            : " 回滚自=" + rs.getString("rollback_of").substring(0, 12)));
                    m.put("extra", rs.getString("status"));
                    m.put("occurredAt", rs.getTimestamp("created_at").toInstant().toString());
                    return m;
                })
                .list());
        items.sort((a, b) -> String.valueOf(b.get("occurredAt")).compareTo(String.valueOf(a.get("occurredAt"))));
        if (items.size() > cap) {
            items.subList(cap, items.size()).clear();
        }
        body.put("status", "OK");
        body.put("items", items);
        body.put("count", items.size());
        return body;
    }
}
