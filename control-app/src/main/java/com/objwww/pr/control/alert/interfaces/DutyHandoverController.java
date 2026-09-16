package com.objwww.pr.control.alert.interfaces;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * 值班交接记录面（前端产品化收官补项，业界对齐 Rootly/incident.io Handover）。
 * 创建时后端实测注入当班事实（firing 事故数/未决调查/生效静默）——交接摘要不再是
 * 无源空态。挂 /api/v1 operator 面；时间参数显式 Timestamp（JdbcClient 惯例）。
 */
@RestController
@RequestMapping("/api/v1/handovers")
public class DutyHandoverController {

    private final JdbcClient jdbc;

    public DutyHandoverController(ObjectProvider<JdbcClient> jdbc) {
        this.jdbc = jdbc.getIfAvailable();
    }

    public record CreateRequest(String fromOncall, String toOncall, String notes,
                                String createdBy) {
    }

    /** 最近交接记录（详情页总览卡消费）；至多 20 条 */
    @GetMapping
    public Map<String, Object> list() {
        if (jdbc == null) {
            return Map.of("status", "UNAVAILABLE", "reason", "DB_FACE_NOT_ASSEMBLED");
        }
        List<Map<String, Object>> items = jdbc.sql("""
                select from_oncall, coalesce(to_oncall, '') as to_oncall,
                       coalesce(notes, '') as notes,
                       coalesce(content_stats::text, '{}') as content_stats, created_at
                  from duty_handover order by created_at desc limit 20
                """)
                .query((rs, i) -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("from_oncall", rs.getString("from_oncall"));
                    m.put("to_oncall", rs.getString("to_oncall"));
                    m.put("notes", rs.getString("notes"));
                    m.put("stats", rs.getString("content_stats"));
                    m.put("created_at", rs.getTimestamp("created_at").toInstant().toString());
                    return m;
                })
                .list();
        return Map.of("status", "OK", "items", items, "count", items.size());
    }

    /** 写交接：notes 必填；当班事实由本端点实时测算（firing/未决调查/生效静默） */
    @PostMapping
    public Map<String, Object> create(@RequestBody CreateRequest request) {
        Objects.requireNonNull(request.notes(), "notes 必填");
        if (request.notes().isBlank()) {
            return Map.of("status", "REJECTED", "reason", "NOTES_REQUIRED");
        }
        if (jdbc == null) {
            return Map.of("status", "UNAVAILABLE", "reason", "DB_FACE_NOT_ASSEMBLED");
        }
        Instant now = Instant.now();
        Map<String, Object> stats = jdbc.sql("""
                select (select count(*) from incident where status = 'FIRING') as firing,
                       (select count(*) from rca_run
                         where state in ('QUEUED','RUNNING','REPORTING')) as inflight_runs,
                       (select count(*) from notify_silence
                         where state = 'ACTIVE' and expires_at > :now) as silences
                """)
                .param("now", Timestamp.from(now))
                .query((rs, i) -> Map.of(
                        "firingIncidents", (Object) rs.getLong("firing"),
                        "inflightRuns", (Object) rs.getLong("inflight_runs"),
                        "activeSilences", (Object) rs.getLong("silences")))
                .single();
        jdbc.sql("""
                insert into duty_handover (id, from_oncall, to_oncall, notes, content_stats,
                    created_by, created_at)
                values (:id, :fromOncall, :toOncall, :notes,
                        cast(:stats as jsonb), :createdBy, :at)
                """)
                .param("id", UUID.randomUUID())
                .param("fromOncall", request.fromOncall() == null || request.fromOncall().isBlank()
                        ? "oncall" : request.fromOncall().trim())
                .param("toOncall", request.toOncall() == null ? null : request.toOncall().trim())
                .param("notes", request.notes().trim())
                .param("stats", toJson(stats))
                .param("createdBy", request.createdBy() == null || request.createdBy().isBlank()
                        ? "operator" : request.createdBy().trim())
                .param("at", Timestamp.from(now))
                .update();
        return Map.of("status", "OK", "stats", stats);
    }

    private static String toJson(Map<String, Object> stats) {
        StringBuilder sb = new StringBuilder("{");
        int i = 0;
        for (Map.Entry<String, Object> e : stats.entrySet()) {
            if (i++ > 0) {
                sb.append(',');
            }
            sb.append('"').append(e.getKey()).append("\":").append(e.getValue());
        }
        return sb.append('}').toString();
    }
}
