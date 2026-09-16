package com.objwww.pr.control.alert.interfaces;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 诊断统计读面（3.4 AI 诊断页顶栏；业界对齐 Sage AI/诊断统计看板）：
 * diag_session 真账本聚合直出——累计问答、涉及事件、今日新增、自由问数。
 * 权限沿 /api/v1/** OPERATOR 只读矩阵（SecurityConfig 既有行）。
 */
@RestController
@RequestMapping(path = "/api/v1/diag", produces = MediaType.APPLICATION_JSON_VALUE)
public class DiagStatsController {

    private final JdbcClient jdbc;

    public DiagStatsController(ObjectProvider<JdbcClient> jdbc) {
        this.jdbc = jdbc.getIfAvailable();
    }

    @GetMapping("/stats")
    public Map<String, Object> stats() {
        if (jdbc == null) {
            return Map.of("status", "UNAVAILABLE", "reason", "DB_FACE_NOT_ASSEMBLED");
        }
        return jdbc.sql("""
                select count(*) as total,
                       count(distinct incident_id) as incidents,
                       count(*) filter (where created_at >= date_trunc('day', now())) as today,
                       count(*) filter (where question_key = 'FREE') as free_count
                  from diag_session
                """)
                .query((rs, i) -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("status", "OK");
                    m.put("total", rs.getLong("total"));
                    m.put("incidents", rs.getLong("incidents"));
                    m.put("today", rs.getLong("today"));
                    m.put("freeCount", rs.getLong("free_count"));
                    return m;
                })
                .single();
    }
}
