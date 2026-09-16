package com.objwww.pr.control.alert.interfaces;

import com.objwww.pr.control.alert.application.agent.RcaModelGateway;
import com.objwww.pr.control.alert.domain.agent.RcaModelCallContext;
import com.objwww.pr.control.alert.domain.agent.RcaModelOutcome;
import com.objwww.pr.shared.Digest;
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

    private static final Map<String, String> QUESTIONS = new LinkedHashMap<>();

    static {
        QUESTIONS.put("impact", "这个告警影响什么？");
        QUESTIONS.put("hypothesis", "当前的调查假设是什么？");
        QUESTIONS.put("timeline", "事件时间线怎么走的？");
        QUESTIONS.put("history", "历史上调查过几次？结论如何？");
        QUESTIONS.put("cost", "这个事件的调查花了多少钱？");
    }

    private final JdbcClient jdbc;
    /** v2 自由问答的模型网关（复用 RCA 账本纪律：有锚才触网） */
    private final RcaModelGateway gateway;

    public DiagSessionController(ObjectProvider<JdbcClient> jdbc,
            ObjectProvider<RcaModelGateway> gateway) {
        this.jdbc = jdbc.getIfAvailable();
        this.gateway = gateway.getIfAvailable();
    }

    @GetMapping("/questions")
    public Map<String, Object> questions() {
        List<Map<String, Object>> items = new ArrayList<>();
        QUESTIONS.forEach((k, v) -> items.add(Map.of("key", k, "text", v)));
        return Map.of("status", "OK", "questions", items);
    }

    public record AskRequest(String key, String createdBy) {
    }

    @PostMapping
    public Map<String, Object> ask(@PathVariable UUID incidentId, @RequestBody AskRequest request) {
        Objects.requireNonNull(request.key(), "key 必填");
        if (!QUESTIONS.containsKey(request.key())) {
            return Map.of("status", "REJECTED", "reason", "UNKNOWN_QUESTION_KEY");
        }
        if (jdbc == null) {
            return Map.of("status", "UNAVAILABLE", "reason", "DB_FACE_NOT_ASSEMBLED");
        }
        String answer;
        switch (request.key()) {
            case "impact" -> answer = lines(jdbc.sql("""
                    select '· 服务：' || coalesce(e.lab->>'service', e.lab->>'service_name', '—')
                           || char(10) || '· 告警：' || coalesce(substring(i.incident_key from 'alertname=([^|]+)'), '—')
                           || char(10) || '· 状态：' || i.status
                           || coalesce(char(10) || '· 首次发生：' || to_char(i.episode_started_at, 'YYYY-MM-DD HH24:MI'), '')
                           || char(10) || '· 累计接收 ' || i.received_count || ' 次（去重事件 ' || i.distinct_event_count || '）'
                      from incident i
                      left join lateral (
                          select labels as lab from alert_event
                           where incident_id = i.id
                           order by recorded_at desc limit 1
                      ) e on true
                     where i.id = :id
                    """).param("id", incidentId).query((rs, i) -> rs.getString(1)).list(),
                    "· 事件不存在");
            case "hypothesis" -> answer = lines(jdbc.sql("""
                    select '· [' || coalesce(c.status, '—') || '] ' || c.reason
                      from rca_claim c join rca_run r on r.id = c.run_id
                     where r.incident_id = :id limit 5
                    """).param("id", incidentId).query((rs, i) -> rs.getString(1)).list(),
                    "· 尚无结构化断言（确定性引擎无假设清单）——可用自由追问让模型基于事实作答");
            case "timeline" -> answer = lines(jdbc.sql("""
                    select '· ' || to_char(e.starts_at, 'MM-DD HH24:MI') || ' ' ||
                           case e.status when 'firing' then '告警触发' else '恢复' end
                      from (select status, starts_at from alert_event
                             where incident_id = :id order by starts_at desc limit 8) e
                    """).param("id", incidentId).query((rs, i) -> rs.getString(1)).list(),
                    "· 暂无事件");
            case "history" -> answer = lines(jdbc.sql("""
                    select '· 累计调查 ' || count(*) || ' 次'
                           || char(10) || '· 成功 ' || count(*) filter (where state = 'SUCCEEDED')
                           || ' ｜ 失败 ' || count(*) filter (where state in ('FAILED','EXPIRED'))
                           || ' ｜ 在途 ' || count(*) filter (where state in ('QUEUED','RUNNING','REPORTING'))
                      from rca_run where incident_id = :id
                    """).param("id", incidentId).query((rs, i) -> rs.getString(1)).list(),
                    "· 尚未发起过调查");
            case "cost" -> answer = lines(jdbc.sql("""
                    select '· 模型调用 ' || count(m.id) || ' 次'
                           || char(10) || '· 累计费用 ' || coalesce(round(sum(m.cost_micros) / 1000000.0, 4)::text || ' ' || coalesce(max(m.currency), ''), '—')
                      from rca_model_call m join rca_run r on r.id = m.run_id
                     where r.incident_id = :id
                    """).param("id", incidentId).query((rs, i) -> rs.getString(1)).list(),
                    "· 暂无可计价调用（模型名缺失或 usage 缺失，如实未知）");
            default -> answer = "不支持的问题";
        }
        answer = answer + System.lineSeparator() + "—— 数据截至 "
                + Timestamp.from(Instant.now()).toInstant().toString().replace('T', ' ').substring(0, 16)
                + "（实时查询，与事件状态一致）";
        UUID sessionId = UUID.randomUUID();
        Instant now = Instant.now();
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
                .param("at", Timestamp.from(now))
                .update();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", "OK");
        body.put("session_id", sessionId.toString());
        body.put("question", QUESTIONS.get(request.key()));
        body.put("answer", answer);
        return body;
    }

    public record FreeAskRequest(String question, String createdBy) {
    }

    /**
     * v2 自由问答（接真模型）：账本锚=该事件最近一次模型调用的 run/task/attempt 三元组
     * （已验证过的合法组合，不伪造上下文），roleId=diag-chat 与调查调用隔离可审计；
     * 无历史调用（从未调查）→ REJECTED，不硬造锚。时长限 60s，问题限 500 字。
     */
    @PostMapping("/free")
    public Map<String, Object> free(@PathVariable UUID incidentId, @RequestBody FreeAskRequest request) {
        Objects.requireNonNull(request.question(), "question 必填");
        String question = request.question().trim();
        if (question.isEmpty()) {
            return Map.of("status", "REJECTED", "reason", "QUESTION_REQUIRED");
        }
        if (question.length() > 500) {
            return Map.of("status", "REJECTED", "reason", "QUESTION_TOO_LONG");
        }
        if (jdbc == null || gateway == null) {
            return Map.of("status", "UNAVAILABLE", "reason", "GATEWAY_NOT_ASSEMBLED");
        }
        List<Map<String, Object>> anchors = jdbc.sql("""
                select m.run_id, m.task_id, m.attempt_id, m.lease_epoch, m.config_epoch,
                       m.release_digest,
                       (select coalesce(max(x.action_seq), -1) + 1 from rca_model_call x
                         where x.run_id = m.run_id and x.task_id = m.task_id
                           and x.attempt_id = m.attempt_id) as next_action_seq
                  from rca_model_call m
                 where m.run_id in (select id from rca_run where incident_id = :id)
                 order by m.created_at desc limit 1
                """)
                .param("id", incidentId)
                .query((rs, i) -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("runId", rs.getObject("run_id", UUID.class));
                    m.put("taskId", rs.getObject("task_id", UUID.class));
                    m.put("attemptId", rs.getObject("attempt_id", UUID.class));
                    m.put("leaseEpoch", rs.getLong("lease_epoch"));
                    m.put("configEpoch", rs.getObject("config_epoch") == null
                            ? null : rs.getLong("config_epoch"));
                    m.put("releaseDigest", rs.getString("release_digest"));
                    m.put("nextActionSeq", rs.getLong("next_action_seq"));
                    return m;
                })
                .list();
        if (anchors.isEmpty()) {
            return Map.of("status", "REJECTED", "reason", "NO_RUN_TO_ANCHOR");
        }
        Map<String, Object> a = anchors.get(0);
        String prompt = "你是告警根因分析助手。以下是该告警事件的已核实事实：\n" + incidentContext(incidentId)
                + "\n值班员的提问：" + question
                + "\n要求：只基于以上事实回答；事实不足以回答时如实说明当前记录不足以回答。";
        Instant now = Instant.now();
        RcaModelCallContext ctx = new RcaModelCallContext(
                (UUID) a.get("runId"), (UUID) a.get("taskId"), (UUID) a.get("attemptId"),
                (Long) a.get("nextActionSeq"), 0, "diag-chat", "v1", Digest.sha256Of("diag-chat-v1").value(),
                (Long) a.get("leaseEpoch"), (Long) a.get("configEpoch"),
                a.get("releaseDigest") == null ? null : String.valueOf(a.get("releaseDigest")),
                Digest.sha256Of(prompt).value(), null,
                now.plusSeconds(60), now.plusSeconds(60), () -> true);
        RcaModelOutcome outcome;
        try {
            outcome = gateway.call(ctx, prompt, 1024);
        } catch (com.objwww.pr.control.alert.domain.agent.RcaModelCallException e) {
            return Map.of("status", "REJECTED", "reason", "MODEL_CALL_FAILED:" + e.errorCode());
        }
        UUID sessionId = UUID.randomUUID();
        jdbc.sql("""
                insert into diag_session (id, incident_id, question_key, question, answer,
                    answer_refs, created_by, created_at)
                values (:id, :incidentId, 'FREE', :question, :answer,
                        cast(:refs as jsonb), :createdBy, :at)
                """)
                .param("id", sessionId)
                .param("incidentId", incidentId)
                .param("question", question)
                .param("answer", outcome.content())
                .param("refs", "{\"model\":\"" + outcome.actualModel() + "\",\"operationId\":\""
                        + outcome.operationId() + "\",\"totalTokens\":" + outcome.totalTokens() + "}")
                .param("createdBy", request.createdBy() == null || request.createdBy().isBlank()
                        ? "operator" : request.createdBy().trim())
                .param("at", Timestamp.from(now))
                .update();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", "OK");
        body.put("session_id", sessionId.toString());
        body.put("question", question);
        body.put("answer", outcome.content());
        body.put("model", outcome.actualModel());
        body.put("totalTokens", outcome.totalTokens());
        return body;
    }

    /** 事件已核实事实块（供提示词引用；全部真源 SQL。alertname/service 无独立列——INV-AM1-4，
     *  沿 IncidentQueryReader 契约从每 incident 最新一条 alert_event 的 labels jsonb 提取） */
    private String incidentContext(UUID incidentId) {
        StringBuilder sb = new StringBuilder();
        List<String> impact = jdbc.sql("""
                select 'alert=' || coalesce(e.lab->>'alertname', '-')
                       || '; service=' || coalesce(e.lab->>'service', e.lab->>'service_name', '-')
                       || '; status=' || i.status || '; received=' || i.received_count
                  from incident i
                  left join lateral (
                      select labels as lab from alert_event
                       where incident_id = i.id
                       order by recorded_at desc limit 1
                  ) e on true
                 where i.id = :id
                """).param("id", incidentId).query((rs, i) -> rs.getString(1)).list();
        sb.append(impact.isEmpty() ? "event not found" : impact.get(0));
        List<String> claims = jdbc.sql("""
                select coalesce(c.reason, '') from rca_claim c
                  join rca_run r on r.id = c.run_id
                 where r.incident_id = :id limit 5
                """).param("id", incidentId).query((rs, i) -> rs.getString(1)).list();
        if (!claims.isEmpty()) {
            sb.append(". claims: ").append(String.join(" | ", claims));
        }
        return sb.toString();
    }

    @GetMapping
    public Map<String, Object> history(@PathVariable UUID incidentId) {
        List<Map<String, Object>> items = jdbc == null ? List.of() : jdbc.sql("""
                select question_key, answer_refs, question, answer, created_by, created_at
                  from diag_session where incident_id = :id
                 order by created_at desc limit 20
                """)
                .param("id", incidentId)
                .query((rs, i) -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("question_key", rs.getString("question_key"));
                    m.put("answer_refs", rs.getString("answer_refs"));
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

    /** 多行答案组装：每行一个事实点；无行时用兜底文案（不再单行分号串） */
    private static String lines(List<String> rows, String fallback) {
        if (rows == null || rows.isEmpty()) {
            return fallback;
        }
        StringBuilder sb = new StringBuilder();
        for (String r : rows) {
            if (r != null && !r.isBlank()) {
                if (sb.length() > 0) {
                    sb.append(System.lineSeparator());
                }
                sb.append(r.startsWith("·") ? r : "· " + r);
            }
        }
        return sb.length() > 0 ? sb.toString() : fallback;
    }
}
