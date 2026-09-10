package com.objwww.pr.control.ops.dutybot.interfaces;

import com.objwww.pr.control.infrastructure.auth.AuthenticatedActor;
import com.objwww.pr.control.ops.dutybot.application.DutyBotService;
import com.objwww.pr.control.ops.dutybot.domain.DutyBotStore;
import org.springframework.context.annotation.Profile;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.DateTimeException;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * UX-02 值班仿真机器人 API（/api/v1/duty-bot/**；control 认证域，不进 duty-adapter）：
 * <ul>
 *   <li>POST /sessions——建会话（owner=认证主体）；</li>
 *   <li>POST /sessions/{id}/messages——发用户消息，同步返回机器人回复（两条消息
 *       同事务落库）；body {content, clientMessageId?}，同键重放 200 replayed=true；</li>
 *   <li>GET /sessions——本人会话列表（游标 (createdAtEpochMs,id)）；</li>
 *   <li>GET /sessions/{id}/messages——消息流（seq 游标，最新在前，向上翻历史）。</li>
 * </ul>
 * 越权（非本人会话）一律 404 不泄露存在性；错误面：400 参数/长度、404 会话不可见、
 * 409 会话条数达上限、429 频率超限。验签归 SecurityFilterChain（/api/v1/** =
 * ROLE_OPERATOR）；浏览器会话写面需 CSRF（与 /api/rca-runs 命令面同策）。
 */
@RestController
@Profile("docker")
@RequestMapping(path = "/api/v1/duty-bot", produces = MediaType.APPLICATION_JSON_VALUE)
public class DutyBotController {

    private final DutyBotService service;

    public DutyBotController(DutyBotService service) {
        this.service = service;
    }

    @PostMapping(path = "/sessions", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> createSession(@RequestBody(required = false) Map<String, Object> body) {
        String title = body == null ? null : text(body.get("title"));
        DutyBotStore.SessionRow session = service.createSession(actor(), title);
        return ResponseEntity.status(201).body(sessionView(session));
    }

    @PostMapping(path = "/sessions/{id}/messages", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> postMessage(@PathVariable String id,
                                         @RequestBody(required = false) Map<String, Object> body) {
        UUID sessionId = parseId(id);
        if (sessionId == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "sessionId 非法"));
        }
        Map<String, Object> safe = body == null ? Map.of() : body;
        DutyBotService.PostResult result;
        try {
            var maybe = service.postMessage(sessionId, actor(), text(safe.get("content")),
                    text(safe.get("clientMessageId")));
            if (maybe.isEmpty()) {
                return ResponseEntity.status(404).body(Map.of("error", "会话不存在或不可见"));
            }
            result = maybe.get();
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (DutyBotService.RateLimitExceededException e) {
            return ResponseEntity.status(429).body(Map.of("error", e.getMessage()));
        } catch (DutyBotService.SessionFullException e) {
            return ResponseEntity.status(409).body(Map.of("error", e.getMessage()));
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("userMessage", messageView(result.userMessage()));
        out.put("botMessage", result.botMessage() == null ? null : messageView(result.botMessage()));
        out.put("replayed", result.replayed());
        return ResponseEntity.status(result.replayed() ? 200 : 201).body(out);
    }

    @GetMapping("/sessions")
    public ResponseEntity<?> sessions(@RequestParam(required = false) String cursor,
                                      @RequestParam(required = false, defaultValue = "20") int limit) {
        int bounded = Math.clamp(limit, 1, 50);
        DutyBotStore.SessionCursor parsed = parseSessionCursor(cursor);
        if (cursor != null && parsed == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "cursor 非法"));
        }
        List<DutyBotStore.SessionRow> rows = service.listSessions(actor(), parsed, bounded);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("sessions", rows.stream().map(DutyBotController::sessionView).toList());
        if (rows.size() == bounded) {
            DutyBotStore.SessionRow last = rows.get(rows.size() - 1);
            body.put("nextCursor", last.createdAt().toEpochMilli() + "/" + last.id());
        }
        return ResponseEntity.ok(body);
    }

    @GetMapping("/sessions/{id}/messages")
    public ResponseEntity<?> messages(@PathVariable String id,
                                      @RequestParam(required = false) String cursor,
                                      @RequestParam(required = false, defaultValue = "50") int limit) {
        UUID sessionId = parseId(id);
        if (sessionId == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "sessionId 非法"));
        }
        int bounded = Math.clamp(limit, 1, 100);
        Long cursorSeq = parseSeq(cursor);
        if (cursor != null && cursorSeq == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "cursor 非法"));
        }
        var maybe = service.listMessages(sessionId, actor(), cursorSeq, bounded);
        if (maybe.isEmpty()) {
            return ResponseEntity.status(404).body(Map.of("error", "会话不存在或不可见"));
        }
        List<DutyBotStore.MessageRow> rows = maybe.get();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("messages", rows.stream().map(DutyBotController::messageView).toList());
        if (rows.size() == bounded) {
            body.put("nextCursor", String.valueOf(rows.get(rows.size() - 1).seq()));
        }
        return ResponseEntity.ok(body);
    }

    // ------------------------------------------------------------------ 内部

    /** actor 唯一来源 = 认证主体（截断 64，对齐 V83 owner CHECK） */
    private static String actor() {
        String name = AuthenticatedActor.name();
        return name.length() > 64 ? name.substring(0, 64) : name;
    }

    private static Map<String, Object> sessionView(DutyBotStore.SessionRow s) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", s.id().toString());
        map.put("title", s.title());
        map.put("owner", s.owner());
        map.put("createdAt", s.createdAt().toString());
        return map;
    }

    private static Map<String, Object> messageView(DutyBotStore.MessageRow m) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", m.id().toString());
        map.put("seq", m.seq());
        map.put("role", m.role());
        map.put("content", m.content());
        map.put("intent", m.intent());
        map.put("references", m.references().stream()
                .map(r -> Map.of("type", r.type(), "id", r.id())).toList());
        map.put("createdAt", m.createdAt().toString());
        return map;
    }

    private static String text(Object raw) {
        return raw instanceof String s ? s : null;
    }

    private static UUID parseId(String raw) {
        try {
            return UUID.fromString(raw);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static Long parseSeq(String raw) {
        if (raw == null) {
            return null;
        }
        try {
            return Long.parseLong(raw);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** 会话游标 = createdAtEpochMs/id（DutyQueryController 同式） */
    private static DutyBotStore.SessionCursor parseSessionCursor(String raw) {
        if (raw == null) {
            return null;
        }
        int slash = raw.indexOf('/');
        if (slash <= 0) {
            return null;
        }
        try {
            Instant at = Instant.ofEpochMilli(Long.parseLong(raw.substring(0, slash)));
            return new DutyBotStore.SessionCursor(at, UUID.fromString(raw.substring(slash + 1)));
        } catch (IllegalArgumentException | DateTimeException e) {
            return null;
        }
    }
}
