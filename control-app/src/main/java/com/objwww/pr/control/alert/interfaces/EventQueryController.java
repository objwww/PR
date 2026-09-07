package com.objwww.pr.control.alert.interfaces;

import com.objwww.pr.control.alert.application.EventQueryService;
import com.objwww.pr.control.alert.application.RunQueryService;
import com.objwww.pr.control.alert.application.SseStreamService;
import com.objwww.pr.control.alert.domain.repository.RcaRunRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * rca_run 查询/事件流 API（M5-13 §M5-13③；字段级以 mocks/runs.js 为准）。
 *
 * <p>鉴权两层：REST 走 O-4 过渡 bearer（operator 角色，X-Operator-Id 留主体）；
 * SSE 走 stream ticket（POST 换票 → GET 带票开流）——禁止 URL 带长效 token，
 * 票 TTL 30s 单次绑 run+主体（{@link SseStreamService}）。
 *
 * <p>SSE 连接形态（C-18⑥）：单次排水后 complete，客户端以 retry 间隔自动重连、
 * Last-Event-ID 续传（服务端归一为 after_seq 游标）——每连接零长驻线程，
 * 断线/慢客户端结构上不可能拖慢 Run（INV-AM5-8）；真身走表（无 NOTIFY 依赖）。
 */
@RestController
@Profile("docker")
public class EventQueryController {

    private final RunQueryService runQuery;
    private final EventQueryService events;
    private final SseStreamService sse;
    private final RcaRunRepository runs;
    private final byte[] expectedBearer;

    public EventQueryController(RunQueryService runQuery, EventQueryService events,
                                SseStreamService sse, RcaRunRepository runs,
                                @Value("${app.operator.api.bearer}") String bearerToken) {
        this.runQuery = runQuery;
        this.events = events;
        this.sse = sse;
        this.runs = runs;
        this.expectedBearer = (bearerToken == null ? "" : bearerToken)
                .getBytes(StandardCharsets.UTF_8);
    }

    // ------------------------------------------------------------------ REST 查询

    @GetMapping(path = "/api/rca-runs", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Map<String, Object>> list(
            @RequestHeader(value = "Authorization", required = false) String authorization) {
        if (!authorized(authorization)) {
            return ResponseEntity.status(401).build();
        }
        return ResponseEntity.ok(runQuery.list());
    }

    @GetMapping(path = "/api/rca-runs/{runId}", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Map<String, Object>> detail(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @PathVariable String runId) {
        if (!authorized(authorization)) {
            return ResponseEntity.status(401).build();
        }
        UUID id = parseRunId(runId);
        if (id == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "runId 非法"));
        }
        return runQuery.detail(id)
                .<ResponseEntity<Map<String, Object>>>map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.status(404).body(Map.of("error", "run 不存在")));
    }

    /** 初次读取 {@code ?after_seq=0}；gap=true → 客户端按 latestSeq 全量重同步 */
    @GetMapping(path = "/api/rca-runs/{runId}/events", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Map<String, Object>> events(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @PathVariable String runId,
            @RequestParam(name = "after_seq", defaultValue = "0") long afterSeq,
            @RequestParam(name = "limit", defaultValue = "100") int limit) {
        if (!authorized(authorization)) {
            return ResponseEntity.status(401).build();
        }
        UUID id = parseRunId(runId);
        if (id == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "runId 非法"));
        }
        if (!runs.findById(id).isPresent()) {
            return ResponseEntity.status(404).body(Map.of("error", "run 不存在"));
        }
        EventQueryService.EventPage page =
                events.events(id, Math.max(0, afterSeq), limit);
        List<Map<String, Object>> rows = new ArrayList<>();
        for (EventQueryService.EventItem item : page.events()) {
            rows.add(eventRow(item));
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("events", rows);
        out.put("latestSeq", page.latestSeq());
        out.put("gap", page.gap());
        return ResponseEntity.ok(out);
    }

    // ------------------------------------------------------------------ SSE 换票 + 流

    /** 换流票：TTL 30s 单次绑 run+主体（O-4：subject 取 X-Operator-Id，缺省 operator） */
    @PostMapping(path = "/api/rca-runs/{runId}/events/stream-ticket",
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Map<String, Object>> streamTicket(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestHeader(value = "X-Operator-Id", required = false) String operatorId,
            @PathVariable String runId) {
        if (!authorized(authorization)) {
            return ResponseEntity.status(401).build();
        }
        UUID id = parseRunId(runId);
        if (id == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "runId 非法"));
        }
        if (!runs.findById(id).isPresent()) {
            return ResponseEntity.status(404).body(Map.of("error", "run 不存在"));
        }
        String ticket = sse.issueTicket(id, subject(operatorId), Instant.now());
        return ResponseEntity.ok(Map.of("ticket", ticket));
    }

    /**
     * 事件流（SSE）。票鉴权通过后单次排水即 complete：浏览器 EventSource 以
     * Last-Event-ID（服务端签发的 id: seq）自动重连续传；缺口 → resync 事件、
     * 空转 → heartbeat。
     */
    @GetMapping(path = "/api/rca-runs/{runId}/events/stream")
    public ResponseEntity<SseEmitter> stream(
            @PathVariable String runId,
            @RequestParam(name = "ticket") String ticket,
            @RequestParam(name = "subject", defaultValue = "operator") String subject,
            @RequestHeader(value = "Last-Event-ID", required = false) String lastEventId,
            @RequestParam(name = "after_seq", defaultValue = "0") long afterSeq) {
        UUID id = parseRunId(runId);
        if (id == null) {
            return ResponseEntity.badRequest().build();
        }
        if (!sse.consumeTicket(ticket, id, subject, Instant.now())) {
            return ResponseEntity.status(401).build();
        }
        SseEmitter emitter = new SseEmitter(0L);
        long cursor = cursorOf(lastEventId, afterSeq);
        try {
            // 重连节奏服务端定（retry 2s）；每连接一次排水，断线重连 = 天然轮询背压面
            emitter.send(SseEmitter.event().name("ready").reconnectTime(2000L).data("ok"));
            sse.drain(id, cursor, new EmitterSink(emitter), Instant.now());
            emitter.complete();
        } catch (Exception e) {
            emitter.completeWithError(e);
        }
        return ResponseEntity.ok()
                .contentType(MediaType.TEXT_EVENT_STREAM)
                .body(emitter);
    }

    // ------------------------------------------------------------------ 内部

    /** Sink → SseEmitter 桥（id=seq 锚随每条消息写，浏览器 Last-Event-ID 续传） */
    private static final class EmitterSink implements SseStreamService.Sink {

        private final SseEmitter emitter;

        EmitterSink(SseEmitter emitter) {
            this.emitter = emitter;
        }

        @Override
        public void accept(String id, String event, Map<String, Object> data) {
            send(SseEmitter.event().id(id).name(event).data(data));
        }

        @Override
        public void resync(long latestSeq) {
            send(SseEmitter.event().name("resync").data(Map.of("latestSeq", latestSeq)));
        }

        @Override
        public void heartbeat() {
            send(SseEmitter.event().comment("hb"));
        }

        private void send(SseEmitter.SseEventBuilder builder) {
            try {
                emitter.send(builder);
            } catch (Exception e) {
                throw new IllegalStateException("sse send 失败（客户端断开）", e);
            }
        }
    }

    /** 事件行（mock 契约字段：type/level/taskName；payload 已过白名单消毒） */
    private static Map<String, Object> eventRow(EventQueryService.EventItem item) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("seq", item.seq());
        row.put("type", item.eventType());
        row.put("taskId", item.taskId());
        row.put("taskName", null);
        row.put("level", levelOf(item.eventType()));
        row.put("summary", item.summary());
        row.put("payload", item.payload());
        row.put("createdAt", item.createdAt());
        return row;
    }

    /** 仅错误筛选面（mock level=error）：失败/死亡类 error，重试类 warn，其余 info */
    private static String levelOf(String eventType) {
        if (eventType == null) {
            return "info";
        }
        String upper = eventType.toUpperCase();
        if (upper.contains("FAILED") || upper.contains("DEAD") || upper.contains("ERROR")) {
            return "error";
        }
        if (upper.contains("RETRY") || upper.contains("BUDGET")) {
            return "warn";
        }
        return "info";
    }

    private static long cursorOf(String lastEventId, long afterSeq) {
        if (lastEventId != null && !lastEventId.isBlank()) {
            try {
                return Math.max(0, Long.parseLong(lastEventId.trim()));
            } catch (NumberFormatException ignored) {
                // 非法 Last-Event-ID → 退回 query 游标
            }
        }
        return Math.max(0, afterSeq);
    }

    private static String subject(String operatorId) {
        return operatorId == null || operatorId.isBlank() ? "operator"
                : operatorId.trim();
    }

    private static UUID parseRunId(String runId) {
        try {
            return UUID.fromString(runId);
        } catch (IllegalArgumentException | NullPointerException e) {
            return null;
        }
    }

    /** 常量时间比较（O-4 过渡 bearer；OperatorApiController 同构） */
    private boolean authorized(String authorizationHeader) {
        if (authorizationHeader == null || !authorizationHeader.startsWith("Bearer ")) {
            return false;
        }
        byte[] provided = authorizationHeader.substring("Bearer ".length())
                .getBytes(StandardCharsets.UTF_8);
        return MessageDigest.isEqual(provided, expectedBearer);
    }
}
