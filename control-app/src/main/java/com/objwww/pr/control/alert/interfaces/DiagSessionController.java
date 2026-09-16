package com.objwww.pr.control.alert.interfaces;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * 诊断会话 v1（业界对齐 Bits AI"问 AI"的引用式形态）：预设问题词表，答案由
 * 真源实时组装（断言/时间线/历史调查/费用/事实计数），零模型调用零幻觉；
 * 问答落库 diag_session（V131）可回放。v2 自由问答接 RcaModelGateway 另行评审。
 */
@RestController
@RequestMapping("/api/v1/incidents/{incidentId}/diag")
public class DiagSessionController {

    /** 问题词表：key → 摘要问题文案（前端渲染为可点问题气泡） */
    private static final Map<String, String> QUESTIONS = new LinkedHashMap<>();

    static {
        QUESTIONS.put("impact", "这个告警影响什么？");
        QUESTIONS.put("hypothesis", "当前的调查假设是什么？");
        QUESTIONS.put("timeline", "事件时间线怎么走的？");
        QUESTIONS.put("history", "历史上调查过几次？结论如何？");
        QUESTIONS.put("cost", "这个事件的调查花了多少钱？");
    }

    private final JdbcClient jdbc;

    public DiagSessionController(ObjectProvider<JdbcClient> jdbc) {
        this.jdbc = jdbc.getIfAvailable();
    }

    @GetMapping("/questions")
    public Map<String, Object> questions() {
        List<Map<String, Object>> items = new ArrayList<>();
        QUESTIONS.forEach((k, v) -> items.add(Map.of("key", k, "text", v)));
        return Map.of("status", "OK", "questions", items);
    }

    public record AskRequest(String key, String createdBy) {
    }

    /** 问答：按词表 key 从真源组装答案（无自由文本入参=不可注入不可幻觉） */
    @PostMapping
    public Map<String, Object> ask(@PathVariable UUID incidentId, @RequestBody AskRequest request) {
        Objects.requireNonNull(request.key(), "key 必填");
        if (!QUESTIONS.containsKey(request.key())) {
            return Map.of("status", "REJECTED", "reason", "UNKNOWN_QUESTION_KEY");
        }
        if (jdbc == null) {
            return Map.of("status", "UNAVAILABLE", "reason", "DB_FACE_NOT_ASSEMBLED");
        }
        String answer = switch (request.key()) {
            case "impact" -> text(jdbc.sql("""
                    select '服务=' || coalesce(service, '—') || '；状态=' || status
                           || '；首次发生=' || coalesce(episode_started_at::text, '—')
                           || '；累计接收 ' || received_count || ' 次'
                      from incident where id = :id
                    """).param("id", incidentId));
            case "hypothesis" -> text(jdbc.sql("""
                    select coalesce(string_agg(c.reason, ' ｜ '), '尚无结构化断言（确定性引擎无假设清单）')
                      from rca_claim c
                      join rca_run r on r.id = c.run_id
                     where r.incident_id = :id
                    """).param("id", incidentId));
            case "timeline" -> text(jdbc.sql("""
                    select coalesce(string_agg(e.status || '@' || to_char(e.starts_at, 'MM-DD HH24:MI'), ' → '),
                           '暂无事件')
                      from (select status, starts_at from alert_event
                             where incident_id = :id order by starts_at desc limit 8) e
                    """).param("id", incidentId));
            case "history" -> text(jdbc.sql("""
                    select '累计 ' || count(*) || ' 次调查：成功 ' || count(*) filter (where state = 'SUCCEEDED')
                           || '，失败 ' || count(*) filter (where state in ('FAILED','EXPIRED'))
                           || '，在途 ' || count(*) filter (where state in ('QUEUED','RUNNING','REPORTING'))
                      from rca_run where incident_id = :id
                    """).param("id", incidentId));
            case "cost" -> text(jdbc.sql("""
                    select coalesce('累计模型费用 ' || round(sum(m.cost_micros) / 1000000.0, 4) || ' '
                           || coalesce(max(m.currency), ''), '暂无可计价调用（模型名缺失或 usage 缺失，如实未知）')
                      from rca_model_call m join rca_run r on r.id = m.run_id
                      where r.incident_id = :id
                    """).param("id", incidentId));
            default -> "不支持的问题";
        };
        UUID sessionId = UUID.randomUUID();
        jdbc.sql("""
                insert into diag_session (id, incident_id, question_key, question, answer,
                    answer_refs, created_by, created_at)
                values (:id, :incidentId, :key, :question, :answer,
                        cast(:refs as jsonb), :createdBy, :at)
                """)
                .param("id", sessionId)
                .param("incidentId", incidentId)
                .param("key", request.key())
                .param("question", QUESTIONS.get(request.key()))
                .param("answer", answer)
                .param("refs", "[{\"source\":\"live-sql\"}]")
                .param("createdBy", request.createdBy() == null || request.createdBy().isBlank()
                        ? "operator" : request.createdBy().trim())
                .param("at", Timestamp.from(Instant.now()))
                .update();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", "OK");
        body.put("session_id", sessionId.toString());
        body.put("question", QUESTIONS.get(request.key()));
        body.put("answer", answer);
        return body;
    }

    /** 本事件的会话回放（最近 20 条） */
    @GetMapping
    public Map<String, Object> history(@PathVariable UUID incidentId) {
        List<Map<String, Object>> items = jdbc == null ? List.of() : jdbc.sql("""
                select question, answer, created_by, created_at
                  from diag_session where incident_id = :id
                 order by created_at desc limit 20
                """)
                .param("id", incidentId)
                .query((rs, i) -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("question", rs.getString("question"));
                    m.put("answer", rs.getString("answer"));
                    m.put("created_by", rs.getString("created_by"));
                    m.put("created_at", rs.getTimestamp("created_at").toInstant().toString());
                    return m;
                })
                .list();
        return Map.of("status", "OK", "items", items, "count", items.size());
    }

    private static String text(List<String> rows) {
        return rows.isEmpty() || rows.get(0) == null ? "未找到相关记录" : String.join("；", rows);
    }
}
