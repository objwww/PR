package com.objwww.pr.control.alert.interfaces;

import com.objwww.pr.control.alert.application.IncidentWaitingRedrive;
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
import java.util.Set;
import java.util.UUID;

/**
 * AI 结论人工标注面（前端产品化波次2/3）：确认/驳回 AI 的根因结论。
 * 标注落 conclusion_feedback（V122；V124 增驳回分类+真实根因）——Analytics 的采纳率
 * 指标数据源。POST /reinvestigate 为显式重查入口（复用等待重驱同闸同事务路径）。
 * 归 operator 角色（走 /api/v1/** 只读投影面同款授权——POST 为受控标注写面）。
 */
@RestController
@RequestMapping("/api/v1/incidents")
public class ConclusionFeedbackController {

    /** 驳回分类词表（业界对齐：证据不足/误报/根因方向错误/其他） */
    private static final Set<String> REJECT_CATEGORIES = Set.of(
            "INSUFFICIENT_EVIDENCE", "FALSE_POSITIVE", "WRONG_DIRECTION", "OTHER");

    private final JdbcClient jdbc;
    private final IncidentWaitingRedrive redrive;

    public ConclusionFeedbackController(ObjectProvider<JdbcClient> jdbc,
            ObjectProvider<IncidentWaitingRedrive> redrive) {
        this.jdbc = jdbc.getIfAvailable();
        this.redrive = redrive.getIfAvailable();
    }

    public record FeedbackRequest(String verdict, String actor, String reason, UUID runId,
                                  String category, String actualCause) {
    }

    @PostMapping("/{incidentId}/conclusion-feedback")
    public Map<String, Object> feedback(@PathVariable UUID incidentId,
            @RequestBody FeedbackRequest request) {
        Objects.requireNonNull(request.verdict(), "verdict 必填");
        if (!"CONFIRMED".equals(request.verdict()) && !"REJECTED".equals(request.verdict())) {
            return Map.of("status", "REJECTED", "reason", "INVALID_VERDICT");
        }
        boolean reject = "REJECTED".equals(request.verdict());
        if (reject && (request.reason() == null || request.reason().isBlank())) {
            return Map.of("status", "REJECTED", "reason", "REASON_REQUIRED_ON_REJECT");
        }
        if (reject && request.category() != null
                && !REJECT_CATEGORIES.contains(request.category())) {
            return Map.of("status", "REJECTED", "reason", "INVALID_CATEGORY");
        }
        if (jdbc == null) {
            return Map.of("status", "UNAVAILABLE", "reason", "DB_FACE_NOT_ASSEMBLED");
        }
        UUID id = UUID.randomUUID();
        Instant now = Instant.now();
        jdbc.sql("""
                insert into conclusion_feedback(id, incident_id, run_id, verdict, actor,
                    reason, category, actual_cause, created_at)
                values (:id, :incidentId, :runId, :verdict, :actor, :reason, :category,
                    :actualCause, :at)
                """)
                .param("id", id)
                .param("incidentId", incidentId)
                .param("runId", request.runId())
                .param("verdict", request.verdict())
                .param("actor", request.actor())
                .param("reason", request.reason())
                .param("category", request.category())
                .param("actualCause", request.actualCause())
                // JdbcClient 对 Instant 推断不出 SQL 类型（PSQLException）——显式 Timestamp
                .param("at", java.sql.Timestamp.from(now))
                .update();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", "OK");
        body.put("feedback_id", id.toString());
        body.put("verdict", reject ? "已驳回" : "已确认");
        return body;
    }

    /** 单事故的标注历史（详情页展示"人工复核记录"；分类/真实根因缺省 —） */
    @GetMapping("/{incidentId}/conclusion-feedback")
    public Map<String, Object> list(@PathVariable UUID incidentId) {
        if (jdbc == null) {
            return Map.of("status", "UNAVAILABLE", "reason", "DB_FACE_NOT_ASSEMBLED");
        }
        List<Map<String, Object>> items = jdbc.sql("""
                select verdict, actor, coalesce(reason, '') as reason,
                       coalesce(category, '') as category,
                       coalesce(actual_cause, '') as actual_cause, created_at
                  from conclusion_feedback where incident_id = :id order by created_at desc
                """)
                .param("id", incidentId)
                .query((rs, n) -> {
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("verdict", rs.getString("verdict"));
                    item.put("actor", rs.getString("actor"));
                    item.put("reason", rs.getString("reason"));
                    item.put("category", rs.getString("category"));
                    item.put("actual_cause", rs.getString("actual_cause"));
                    item.put("created_at", rs.getTimestamp("created_at").toInstant().toString());
                    return item;
                })
                .list();
        return Map.of("status", "OK", "items", items, "count", items.size());
    }

    /**
     * 重新排查（显式重查入口）：复用等待重驱同闸同事务路径补链 run/task。
     * 活跃 run 已在或路由未放量 → REJECTED + 原因码（诚实失败，不伪装成交付）。
     */
    @PostMapping("/{incidentId}/reinvestigate")
    public Map<String, Object> reinvestigate(@PathVariable UUID incidentId) {
        if (redrive == null) {
            return Map.of("status", "UNAVAILABLE", "reason", "REDRIVE_NOT_ASSEMBLED");
        }
        boolean minted = redrive.redriveIncident(incidentId);
        if (!minted) {
            return Map.of("status", "REJECTED", "reason",
                    "ACTIVE_RUN_EXISTS_OR_ROUTE_NOT_ALLOWED");
        }
        return Map.of("status", "OK");
    }
}
