package com.objwww.pr.control.alert.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.objwww.pr.control.alert.domain.repository.RcaEventReader;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * rca_event 查询服务（M5-13；表+游标是真相源，零迁移——读面按 (run_id, seq) 游标推进）。
 *
 * <p>gap 语义（→ 客户端全量重同步，Unleash delta API 先例）：seq 缺口（游标与首行间
 * 有洞）或客户端超窗（after_seq > latest_seq）即 gap=true；事件 seq 由 appender 保证
 * 连续（M4-10），缺口只可能来自异常现场——防御性显式上报而非静默跳过。
 */
public class EventQueryService {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final int MAX_LIMIT = 200;

    private final RcaEventReader reader;
    private final EventPayloadSanitizer sanitizer;

    public EventQueryService(RcaEventReader reader, EventPayloadSanitizer sanitizer) {
        this.reader = Objects.requireNonNull(reader, "reader");
        this.sanitizer = Objects.requireNonNull(sanitizer, "sanitizer");
    }

    /** 事件项（payload 已过白名单消毒） */
    public record EventItem(long seq, String eventType, String taskId, String summary,
                            Map<String, Object> payload, String createdAt) {
    }

    /** 页投影：latestSeq = 服务端当前最大 seq（客户端心跳/对账锚）；gap=true → 客户端 resync */
    public record EventPage(List<EventItem> events, long latestSeq, boolean gap) {
    }

    public EventPage events(UUID runId, long afterSeq, int limit) {
        int clamped = Math.max(1, Math.min(limit, MAX_LIMIT));
        long latestSeq = reader.latestSeq(runId).orElse(0);
        List<RcaEventReader.EventRow> rows = reader.readAfter(runId, afterSeq, clamped);

        boolean hole = !rows.isEmpty() && rows.get(0).seq() > afterSeq + 1;
        boolean clientAhead = afterSeq > latestSeq;
        boolean drained = rows.isEmpty() && afterSeq < latestSeq;
        boolean gap = hole || clientAhead || drained;

        List<EventItem> items = new ArrayList<>();
        for (RcaEventReader.EventRow row : rows) {
            if (gap) {
                break;
            }
            items.add(toItem(row));
        }
        return new EventPage(items, latestSeq, gap);
    }

    private EventItem toItem(RcaEventReader.EventRow row) {
        Map<String, Object> payload = sanitizer.sanitize(row.payloadJson());
        return new EventItem(row.seq(), row.eventType(),
                text(payload, "task_id", "taskId"),
                text(payload, "summary"),
                payload, row.createdAt().toString());
    }

    private static String text(Map<String, Object> payload, String... keys) {
        for (String key : keys) {
            Object value = payload.get(key);
            if (value != null) {
                return String.valueOf(value);
            }
        }
        return null;
    }

    /** JSON 节点读取（仅消毒后标量面使用；保留给上游诊断） */
    static JsonNode readTree(String json) {
        try {
            return JSON.readTree(json);
        } catch (Exception e) {
            return JSON.createObjectNode();
        }
    }
}
