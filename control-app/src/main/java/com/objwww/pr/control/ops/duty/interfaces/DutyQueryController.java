package com.objwww.pr.control.ops.duty.interfaces;

import com.objwww.pr.control.ops.duty.application.DutyDispatchService;
import com.objwww.pr.control.ops.duty.domain.DutyResolver;
import com.objwww.pr.control.ops.duty.domain.DutyScheduleSnapshot;
import com.objwww.pr.control.ops.duty.domain.DutyStore;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 值班查询/测试面（M7-15；C3a 鉴权——浏览器会话与 machine:operator-line 同权
 * ROLE_OPERATOR，/api/duty/** 归 SecurityFilterChain 矩阵，无 X-Operator-Id）。
 *
 * <ul>
 *   <li>GET /api/duty/notifications——游标分页（cursor=createdAtEpochMs/id）+未读数；</li>
 *   <li>GET /api/duty/notifications/feed——站内群模拟聊天面（EX-D1）：气泡数据=
 *       通知行 + 真实投递行（duty_delivery 六态 + 演练通道标记），只读不新造写路径；</li>
 *   <li>POST /api/duty/notifications/{id}/read——契约④：已读 CAS 只翻 status，
 *       不触碰 operator_case（READ 不冒充 ACK）；幂等（已读再读=changed:false）；</li>
 *   <li>GET /api/duty/schedule/snapshot——契约⑤：排班快照带 version/生成时刻/有效窗
 *       + 当前解析（当班成员/通道链）——127 adapter 与前端当班卡同源；</li>
 *   <li>POST /api/duty/test-notification——MANUAL 测试入口（每次全新 episode）。</li>
 * </ul>
 */
@RestController
@Profile("docker")
@RequestMapping("/api/duty")
public class DutyQueryController {

    private final DutyStore store;
    private final DutyDispatchService dispatch;

    public DutyQueryController(DutyStore store, DutyDispatchService dispatch) {
        this.store = store;
        this.dispatch = dispatch;
    }

    @GetMapping("/notifications")
    public ResponseEntity<Map<String, Object>> notifications(
            @RequestParam(required = false) String cursor,
            @RequestParam(required = false, defaultValue = "50") int limit,
            @RequestParam(required = false, defaultValue = "false") boolean unread) {
        int bounded = Math.clamp(limit, 1, 200);
        List<DutyStore.DutyNotificationView> rows = store.listNotifications(cursor, bounded,
                unread);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("notifications", rows.stream().map(DutyQueryController::view).toList());
        body.put("unreadCount", store.unreadCount());
        if (rows.size() == bounded) {
            DutyStore.DutyNotificationView last = rows.get(rows.size() - 1);
            body.put("nextCursor", last.createdAt().toEpochMilli() + "/" + last.id());
        }
        return ResponseEntity.ok(body);
    }

    @GetMapping("/notifications/feed")
    public ResponseEntity<Map<String, Object>> feed(
            @RequestParam(required = false) String cursor,
            @RequestParam(required = false, defaultValue = "50") int limit) {
        int bounded = Math.clamp(limit, 1, 100);
        List<DutyStore.NotificationFeedView> rows = store.listFeed(cursor, bounded);
        Map<UUID, List<DutyStore.DeliveryView>> deliveries = store.listDeliveries(
                rows.stream().map(DutyStore.NotificationFeedView::id).toList());
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("messages", rows.stream()
                .map(n -> feedView(n, deliveries.getOrDefault(n.id(), List.of())))
                .toList());
        if (rows.size() == bounded) {
            DutyStore.NotificationFeedView last = rows.get(rows.size() - 1);
            body.put("nextCursor", last.createdAt().toEpochMilli() + "/" + last.id());
        }
        return ResponseEntity.ok(body);
    }

    @PostMapping("/notifications/{id}/read")
    public ResponseEntity<Map<String, Object>> markRead(@PathVariable UUID id) {
        boolean changed = store.markRead(id, Instant.now());
        if (!changed) {
            // 行不存在=404；行已读=幂等 200（changed:false）
            boolean exists = store.listNotifications(null, 200, false).stream()
                    .anyMatch(n -> n.id().equals(id));
            if (!exists) {
                return ResponseEntity.status(404).body(Map.of("ok", false, "error", "not_found"));
            }
        }
        return ResponseEntity.ok(Map.of("ok", true, "changed", changed));
    }

    @GetMapping("/schedule/snapshot")
    public ResponseEntity<Map<String, Object>> snapshot() {
        DutyScheduleSnapshot snapshot = store.loadSnapshot();
        DutyResolver.Resolution resolved = DutyResolver.resolve(Instant.now(), snapshot);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("scheduleId", snapshot.scheduleId().toString());
        body.put("name", snapshot.name());
        body.put("scheduleVersion", snapshot.scheduleVersion());
        body.put("generatedAt", snapshot.generatedAt().toString());
        body.put("validUntil", snapshot.validUntil().toString());
        body.put("rotation", snapshot.rotation().name());
        body.put("anchorDate", snapshot.anchorDate().toString());
        body.put("handoffTime", snapshot.handoffTime().toString());
        body.put("timezone", snapshot.zone().getId());
        body.put("onCall", resolved.onCall());
        body.put("viaOverride", resolved.viaOverride());
        body.put("viaFallback", resolved.viaFallback());
        body.put("escalationChain", resolved.escalationChain());
        // env 键名（非密钥值）：127 duty-adapter 据此读自身 env 取 webhook URL/加签密钥
        body.put("channels", resolved.channelChain().stream()
                .map(c -> Map.of("id", c.id().toString(), "name", c.name(),
                        "platform", c.platform(), "priority", c.priority(),
                        "isFallback", c.isFallback(),
                        "envKeyWebhook", c.envKeyWebhook() == null ? "" : c.envKeyWebhook(),
                        "envKeySecret", c.envKeySecret() == null ? "" : c.envKeySecret()))
                .toList());
        body.put("layers", snapshot.layers().stream()
                .map(l -> Map.of("layerIndex", l.layerIndex(),
                        "members", l.memberNames()))
                .toList());
        return ResponseEntity.ok(body);
    }

    @PostMapping("/notifications")
    public ResponseEntity<Map<String, Object>> writeBack(
            @RequestBody Map<String, Object> request) {
        String eventStatus = text(request.get("eventStatus"));
        String title = text(request.get("title"));
        String body = text(request.get("body"));
        Instant triggeredAt = parseInstant(text(request.get("triggeredAt")));
        if (!"firing".equals(eventStatus) && !"resolved".equals(eventStatus)) {
            return ResponseEntity.badRequest()
                    .body(Map.of("ok", false, "error", "eventStatus 必为 firing/resolved"));
        }
        if (title == null || body == null || triggeredAt == null) {
            return ResponseEntity.badRequest()
                    .body(Map.of("ok", false, "error", "title/body/triggeredAt 必填"));
        }
        Map<String, String> labels = new LinkedHashMap<>();
        Object raw = request.get("labels");
        if (raw instanceof Map<?, ?> map) {
            map.forEach((k, v) -> labels.put(String.valueOf(k), v == null ? "" : String.valueOf(v)));
        }
        DutyStore.DispatchOutcome out = dispatch.dispatchExternal("GATUS", labels,
                textOrDefault(request.get("groupKey"), "gatus"),
                triggeredAt, eventStatus, text(request.get("severity")), title, body);
        return ResponseEntity.ok(Map.of(
                "ok", true,
                "created", out.created(),
                "notificationId", out.notificationId().toString()));
    }

    @PostMapping("/test-notification")
    public ResponseEntity<Map<String, Object>> testNotification(
            @RequestBody Map<String, Object> request) {
        String title = text(request.get("title"));
        String body = text(request.get("body"));
        String severity = text(request.get("severity"));
        if (title == null || body == null) {
            return ResponseEntity.badRequest()
                    .body(Map.of("ok", false, "error", "title/body 必填"));
        }
        DutyStore.DispatchOutcome out = dispatch.dispatchManual(title, body, severity);
        return ResponseEntity.ok(Map.of(
                "ok", true,
                "notificationId", out.notificationId().toString(),
                "created", out.created(),
                "channel", out.channelName() == null ? "" : out.channelName()));
    }

    private static Map<String, Object> view(DutyStore.DutyNotificationView n) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", n.id().toString());
        map.put("source", n.source());
        map.put("episodeId", n.episodeId());
        map.put("eventStatus", n.eventStatus());
        map.put("severity", n.severity());
        map.put("title", n.title());
        map.put("status", n.status());
        map.put("createdAt", n.createdAt().toString());
        return map;
    }

    /** 群模拟气泡：通知行 + 真实投递行（空列表=127 直发回写或空链，前端据 source 标注） */
    private static Map<String, Object> feedView(DutyStore.NotificationFeedView n,
                                                List<DutyStore.DeliveryView> deliveries) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", n.id().toString());
        map.put("source", n.source());
        map.put("episodeId", n.episodeId());
        map.put("eventStatus", n.eventStatus());
        map.put("severity", n.severity());
        map.put("title", n.title());
        map.put("body", n.bodyText());
        map.put("status", n.status());
        map.put("createdAt", n.createdAt().toString());
        map.put("deliveries", deliveries.stream().map(d -> {
            Map<String, Object> dv = new LinkedHashMap<String, Object>();
            dv.put("channel", d.channelName());
            dv.put("platform", d.platform());
            dv.put("priority", d.priority());
            dv.put("state", d.state());
            dv.put("attempts", d.attemptCount());
            dv.put("sentAt", d.sentAt() == null ? null : d.sentAt().toString());
            dv.put("lastError", d.lastError());
            dv.put("drill", d.drill());
            return dv;
        }).toList());
        return map;
    }

    private static String text(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private static String textOrDefault(Object value, String fallback) {
        return value == null ? fallback : String.valueOf(value);
    }

    private static Instant parseInstant(Object value) {
        if (value == null) {
            return null;
        }
        try {
            return Instant.parse(String.valueOf(value));
        } catch (DateTimeParseException e) {
            return null;
        }
    }
}
