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
 * 璇婃柇浼氳瘽 v1锛堜笟鐣屽榻?Bits AI"闂?AI"鐨勫紩鐢ㄥ紡褰㈡€侊級锛氶璁鹃棶棰樿瘝琛紝绛旀鐢? * 鐪熸簮瀹炴椂缁勮锛堟柇瑷€/鏃堕棿绾?鍘嗗彶璋冩煡/璐圭敤/浜嬪疄璁℃暟锛夛紝闆舵ā鍨嬭皟鐢ㄩ浂骞昏锛? * 闂瓟钀藉簱 diag_session锛圴131锛夊彲鍥炴斁銆倂2 鑷敱闂瓟鎺?RcaModelGateway 鍙﹁璇勫銆? */
@RestController
@RequestMapping("/api/v1/incidents/{incidentId}/diag")
public class DiagSessionController {

    /** 闂璇嶈〃锛歬ey 鈫?鎽樿闂鏂囨锛堝墠绔覆鏌撲负鍙偣闂姘旀场锛?*/
    private static final Map<String, String> QUESTIONS = new LinkedHashMap<>();

    static {
        QUESTIONS.put("impact", "杩欎釜鍛婅褰卞搷浠€涔堬紵");
        QUESTIONS.put("hypothesis", "褰撳墠鐨勮皟鏌ュ亣璁炬槸浠€涔堬紵");
        QUESTIONS.put("timeline", "浜嬩欢鏃堕棿绾挎€庝箞璧扮殑锛?);
        QUESTIONS.put("history", "鍘嗗彶涓婅皟鏌ヨ繃鍑犳锛熺粨璁哄浣曪紵");
        QUESTIONS.put("cost", "杩欎釜浜嬩欢鐨勮皟鏌ヨ姳浜嗗灏戦挶锛?);
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

    /** 闂瓟锛氭寜璇嶈〃 key 浠庣湡婧愮粍瑁呯瓟妗堬紙鏃犺嚜鐢辨枃鏈叆鍙?涓嶅彲娉ㄥ叆涓嶅彲骞昏锛?*/
    @PostMapping
    public Map<String, Object> ask(@PathVariable UUID incidentId, @RequestBody AskRequest request) {
        Objects.requireNonNull(request.key(), "key 蹇呭～");
        if (!QUESTIONS.containsKey(request.key())) {
            return Map.of("status", "REJECTED", "reason", "UNKNOWN_QUESTION_KEY");
        }
        if (jdbc == null) {
            return Map.of("status", "UNAVAILABLE", "reason", "DB_FACE_NOT_ASSEMBLED");
        }
        String answer = switch (request.key()) {
            case "impact" -> text(jdbc.sql("""
                    select '鏈嶅姟=' || coalesce(service, '鈥?) || '锛涚姸鎬?' || status
                           || '锛涢娆″彂鐢?' || coalesce(episode_started_at::text, '鈥?)
                           || '锛涚疮璁℃帴鏀?' || received_count || ' 娆?
                      from incident where id = :id
                    """).param("id", incidentId).query((rs, i) -> rs.getString(1)).list());
            case "hypothesis" -> text(jdbc.sql("""
                    select coalesce(string_agg(c.reason, ' 锝?'), '灏氭棤缁撴瀯鍖栨柇瑷€锛堢‘瀹氭€у紩鎿庢棤鍋囪娓呭崟锛?)
                      from rca_claim c
                      join rca_run r on r.id = c.run_id
                     where r.incident_id = :id
                    """).param("id", incidentId).query((rs, i) -> rs.getString(1)).list());
            case "timeline" -> text(jdbc.sql("""
                    select coalesce(string_agg(e.status || '@' || to_char(e.starts_at, 'MM-DD HH24:MI'), ' 鈫?'),
                           '鏆傛棤浜嬩欢')
                      from (select status, starts_at from alert_event
                             where incident_id = :id order by starts_at desc limit 8) e
                    """).param("id", incidentId).query((rs, i) -> rs.getString(1)).list());
            case "history" -> text(jdbc.sql("""
                    select '绱 ' || count(*) || ' 娆¤皟鏌ワ細鎴愬姛 ' || count(*) filter (where state = 'SUCCEEDED')
                           || '锛屽け璐?' || count(*) filter (where state in ('FAILED','EXPIRED'))
                           || '锛屽湪閫?' || count(*) filter (where state in ('QUEUED','RUNNING','REPORTING'))
                      from rca_run where incident_id = :id
                    """).param("id", incidentId).query((rs, i) -> rs.getString(1)).list());
            case "cost" -> text(jdbc.sql("""
                    select coalesce('绱妯″瀷璐圭敤 ' || round(sum(m.cost_micros) / 1000000.0, 4) || ' '
                           || coalesce(max(m.currency), ''), '鏆傛棤鍙浠疯皟鐢紙妯″瀷鍚嶇己澶辨垨 usage 缂哄け锛屽瀹炴湭鐭ワ級')
                      from rca_model_call m join rca_run r on r.id = m.run_id
                      where r.incident_id = :id
                    """).param("id", incidentId).query((rs, i) -> rs.getString(1)).list());
            default -> "涓嶆敮鎸佺殑闂";
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

    /** 鏈簨浠剁殑浼氳瘽鍥炴斁锛堟渶杩?20 鏉★級 */
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
        return rows.isEmpty() || rows.get(0) == null ? "鏈壘鍒扮浉鍏宠褰? : String.join("锛?, rows);
    }
}
