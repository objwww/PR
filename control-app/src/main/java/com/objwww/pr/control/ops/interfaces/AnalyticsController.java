package com.objwww.pr.control.ops.interfaces;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 运营分析只读投影（前端产品化波次2，/api/v1/analytics/**；Google SRE 口径 +
 * 告警疲劳指标）：近 7 天逐日——接收告警数、聚合事件数、转化率、降噪率、
 * 完成调查数；外加 AI 结论采纳率（conclusion_feedback 标注闭环）。
 */
@RestController
@RequestMapping("/api/v1/analytics")
public class AnalyticsController {

    private final JdbcClient jdbc;

    public AnalyticsController(ObjectProvider<JdbcClient> jdbc) {
        this.jdbc = jdbc.getIfAvailable();
    }

    @GetMapping("/trends")
    public Map<String, Object> trends() {
        Map<String, Object> body = new LinkedHashMap<>();
        if (jdbc == null) {
            body.put("status", "UNAVAILABLE");
            body.put("reason", "DB_FACE_NOT_ASSEMBLED");
            return body;
        }
        List<Map<String, Object>> days = new ArrayList<>();
        jdbc.sql("""
                select to_char(d.day, 'MM-DD') as label,
                  (select count(*) from alert_event e
                     where e.recorded_at >= d.day and e.recorded_at < d.day + interval '1 day'
                  ) as alerts,
                  (select count(*) from incident i
                     where i.created_at >= d.day and i.created_at < d.day + interval '1 day'
                  ) as incidents,
                  (select count(*) from rca_run r
                     where r.state = 'SUCCEEDED' and r.finished_at >= d.day
                       and r.finished_at < d.day + interval '1 day'
                  ) as runs_done
                from generate_series(current_date - interval '6 days', current_date,
                    interval '1 day') as d(day)
                order by d.day
                """)
                .query((rs, n) -> {
                    Map<String, Object> row = new LinkedHashMap<>();
                    long alerts = rs.getLong("alerts");
                    long incidents = rs.getLong("incidents");
                    row.put("day", rs.getString("label"));
                    row.put("alerts", alerts);
                    row.put("incidents", incidents);
                    row.put("runsDone", rs.getLong("runs_done"));
                    row.put("conversionRate", alerts == 0 ? null
                            : Math.round(incidents * 1000.0 / alerts) / 10.0);
                    row.put("noiseReduction", alerts == 0 ? null
                            : Math.round((1 - incidents * 1.0 / alerts) * 1000.0) / 10.0);
                    return row;
                })
                .list()
                .forEach(days::add);

        Map<String, Object> feedback = jdbc.sql("""
                select count(*) as total,
                  count(*) filter (where verdict = 'CONFIRMED') as confirmed
                  from conclusion_feedback where created_at >= current_date - interval '7 days'
                """)
                .query((rs, n) -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("total", rs.getLong("total"));
                    m.put("confirmed", rs.getLong("confirmed"));
                    return m;
                })
                .single();
        long total = (long) feedback.get("total");
        long confirmed = (long) feedback.get("confirmed");
        Map<String, Object> aiAcceptance = new LinkedHashMap<>();
        aiAcceptance.put("total", total);
        aiAcceptance.put("confirmed", confirmed);
        aiAcceptance.put("rate", total == 0 ? null : Math.round(confirmed * 1000.0 / total) / 10.0);
        body.put("status", "OK");
        body.put("days", days);
        body.put("aiAcceptance", aiAcceptance);
        return body;
    }
}
