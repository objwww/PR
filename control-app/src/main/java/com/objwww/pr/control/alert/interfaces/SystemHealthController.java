package com.objwww.pr.control.alert.interfaces;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 系统健康真源面（前端产品化波次5，UI-DATA-1）：总览页「系统健康」卡的数据源。
 * 全部为实时 SQL 实测值（DB 连通/迁移版本/事件链/在途调查/通知积压/投递终败/隔离区），
 * 无任何写死状态；任一查询异常按 CRIT 如实呈现（不吞异常不装绿）。
 */
@RestController
@RequestMapping("/api/v1/system")
public class SystemHealthController {

    private final JdbcClient jdbc;

    public SystemHealthController(ObjectProvider<JdbcClient> jdbc) {
        this.jdbc = jdbc.getIfAvailable();
    }

    @GetMapping("/health")
    public Map<String, Object> health() {
        Instant now = Instant.now();
        List<Map<String, Object>> items = new ArrayList<>();
        if (jdbc == null) {
            items.add(item("数据库连接", "CRIT", "JdbcClient 未装配"));
            return Map.of("status", "OK", "checkedAt", now.toString(), "items", items);
        }
        // 1. 数据库连通 + 迁移版本
        try {
            Integer one = jdbc.sql("select 1").query((rs, i) -> rs.getInt(1)).single();
            String version = jdbc.sql(
                    "select version::text from flyway_schema_history where success order by installed_rank desc limit 1")
                    .query((rs, i) -> rs.getString(1)).single();
            items.add(item("数据库连接", one != null && one == 1 ? "OK" : "CRIT",
                    "连通正常，迁移版本 V" + version));
        } catch (Exception e) {
            items.add(item("数据库连接", "CRIT", "查询失败：" + rootMessage(e)));
        }
        // 2. 事件链规模（rca_event 追加账本）
        try {
            Map<String, Object> chain = jdbc.sql("""
                    select count(*) as events, count(distinct run_id) as runs from rca_event
                    """).query((rs, i) -> Map.of(
                    "events", (Object) rs.getLong("events"),
                    "runs", (Object) rs.getLong("runs"))).single();
            items.add(item("调查事件链", "OK",
                    chain.get("events") + " 条事件 / 覆盖 " + chain.get("runs") + " 次调查"));
        } catch (Exception e) {
            items.add(item("调查事件链", "CRIT", "查询失败：" + rootMessage(e)));
        }
        // 3. 在途调查（活跃 run；>5 视为积压 WARN）
        try {
            Long active = jdbc.sql("""
                    select count(*) from rca_run
                     where state in ('QUEUED','RUNNING','REPORTING')
                    """).query((rs, i) -> rs.getLong(1)).single();
            items.add(item("在途调查", active != null && active > 5 ? "WARN" : "OK",
                    "活跃 " + active + " 次（排队/执行/组装）"));
        } catch (Exception e) {
            items.add(item("在途调查", "CRIT", "查询失败：" + rootMessage(e)));
        }
        // 4. 通知投递积压 + 24h 终败
        try {
            Map<String, Object> notify = jdbc.sql("""
                    select count(*) filter (where state in ('PENDING','RETRY_WAIT','CLAIMED')) as backlog,
                           count(*) filter (where state = 'DEAD'
                              and coalesce(updated_at, created_at) > now() - interval '24 hours') as dead24h
                      from notify_outbox
                    """).query((rs, i) -> Map.of(
                    "backlog", (Object) rs.getLong("backlog"),
                    "dead24h", (Object) rs.getLong("dead24h"))).single();
            long dead24h = (Long) notify.get("dead24h");
            items.add(item("通知投递", dead24h > 0 ? "WARN" : "OK",
                    "待投递 " + notify.get("backlog") + " ｜ 24h 终败 " + dead24h));
        } catch (Exception e) {
            items.add(item("通知投递", "CRIT", "查询失败：" + rootMessage(e)));
        }
        // 5. 隔离区积压（人工放行待办）
        try {
            Long quarantined = jdbc.sql(
                    "select count(*) from alert_inbox where state = 'QUARANTINED'")
                    .query((rs, i) -> rs.getLong(1)).single();
            items.add(item("告警隔离区", quarantined != null && quarantined > 0 ? "WARN" : "OK",
                    "待人工放行 " + quarantined + " 条"));
        } catch (Exception e) {
            items.add(item("告警隔离区", "CRIT", "查询失败：" + rootMessage(e)));
        }
        boolean anyCrit = items.stream().anyMatch(m -> "CRIT".equals(m.get("state")));
        return Map.of("status", anyCrit ? "CRIT" : "OK",
                "checkedAt", now.toString(), "items", items);
    }

    private static Map<String, Object> item(String name, String state, String detail) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", name);
        m.put("state", state);
        m.put("detail", detail);
        return m;
    }

    private static String rootMessage(Throwable e) {
        Throwable cur = e;
        while (cur.getCause() != null && cur.getCause() != cur) {
            cur = cur.getCause();
        }
        String msg = cur.getMessage();
        return msg == null ? cur.getClass().getSimpleName() : msg;
    }
}
