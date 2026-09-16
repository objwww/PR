package com.objwww.pr.control.alert.interfaces;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * AI 结论人工标注面（前端产品化波次2）：确认/驳回 AI 的根因结论。
 * 标注落 conclusion_feedback（V122）——Analytics 的采纳率/precision 指标数据源；
 * 驳回即反馈环输入（不触发自动重查，重查走「重新排查」显式动作）。
 * 归 operator 角色（走 /api/v1/** 只读投影面同款授权——POST 为受控标注写面）。
 */
@RestController
@RequestMapping("/api/v1/incidents")
public class ConclusionFeedbackController {

    private final JdbcClient jdbc;

    public ConclusionFeedbackController(ObjectProvider<JdbcClient> jdbc) {
        this.jdbc = jdbc.getIfAvailable();
    }

    public record FeedbackRequest(String verdict, String actor, String reason, UUID runId) {
    }

    @PostMapping("/{incidentId}/conclusion-feedback")
    public Map<String, Object> feedback(@PathVariable UUID incidentId,
            @RequestBody FeedbackRequest request) {
        Objects.requireNonNull(request.verdict(), "verdict 必填");
        if (!"CONFIRMED".equals(request.verdict()) && !"REJECTED".equals(request.verdict())) {
            return Map.of("status", "REJECTED", "reason", "INVALID_VERDICT");
        }
        if ("REJECTED".equals(request.verdict())
                && (request.reason() == null || request.reason().isBlank())) {
            return Map.of("status", "REJECTED", "reason", "REASON_REQUIRED_ON_REJECT");
        }
        if (jdbc == null) {
            return Map.of("status", "UNAVAILABLE", "reason", "DB_FACE_NOT_ASSEMBLED");
        }
        UUID id = UUID.randomUUID();
        Instant now = Instant.now();
        jdbc.sql("""
                insert into conclusion_feedback(id, incident_id, run_id, verdict, actor,
                    reason, created_at)
                values (:id, :incidentId, :runId, :verdict, :actor, :reason, :at)
                """)
                .param("id", id)
                .param("incidentId", incidentId)
                .param("runId", request.runId())
                .param("verdict", request.verdict())
                .param("actor", request.actor())
                .param("reason", request.reason())
                .param("at", now)
                .update();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", "OK");
        body.put("feedback_id", id.toString());
        body.put("verdict", "CONFIRMED".equals(request.verdict()) ? "已确认" : "已驳回");
        return body;
    }

    /** 单事故的标注历史（详情页展示"人工复核记录"） */
    @GetMapping("/{incidentId}/conclusion-feedback")
    public Map<String, Object> list(@PathVariable UUID incidentId) {
        if (jdbc == null) {
            return Map.of("status", "UNAVAILABLE", "reason", "DB_FACE_NOT_ASSEMBLED");
        }
        List<Map<String, Object>> items = jdbc.sql("""
                select verdict, actor, coalesce(reason, '—') as reason, created_at
                  from conclusion_feedback where incident_id = :id order by created_at desc
                """)
                .param("id", incidentId)
                .query((rs, n) -> {
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("verdict", rs.getString("verdict"));
                    item.put("actor", rs.getString("actor"));
                    item.put("reason", rs.getString("reason"));
                    item.put("created_at", rs.getTimestamp("created_at").toInstant().toString());
                    return item;
                })
                .list();
        return Map.of("status", "OK", "items", items, "count", items.size());
    }
}
