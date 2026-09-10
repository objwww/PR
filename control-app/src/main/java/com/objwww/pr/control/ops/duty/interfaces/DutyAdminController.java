package com.objwww.pr.control.ops.duty.interfaces;

import com.objwww.pr.control.ops.duty.domain.DutyAdminStore;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 值班管理面（M7-15；operator 授权沿 DutyQueryController 同矩阵）。
 * 成员/通道软删（active/enabled），层与 override 物理删（排班编辑语义）；
 * 通道只收 env 键名——URL/密钥不进请求体（INV-AM3-3，duty_channel 无密钥列）。
 */
@RestController
@Profile("docker")
@RequestMapping("/api/duty")
public class DutyAdminController {

    private final DutyAdminStore admin;

    public DutyAdminController(DutyAdminStore admin) {
        this.admin = admin;
    }

    // ---------------- 成员 ----------------

    @GetMapping("/members")
    public List<DutyAdminStore.MemberView> members() {
        return admin.listMembers();
    }

    @PostMapping("/members")
    public ResponseEntity<Map<String, Object>> createMember(
            @RequestBody Map<String, Object> body) {
        String name = text(body.get("name"));
        if (name == null || name.isBlank()) {
            return badRequest("name 必填");
        }
        DutyAdminStore.MemberView created = admin.insertMember(name, text(body.get("displayName")));
        return ResponseEntity.ok(Map.of("ok", true, "member", created));
    }

    @PutMapping("/members/{id}")
    public ResponseEntity<Map<String, Object>> updateMember(
            @PathVariable UUID id, @RequestBody Map<String, Object> body) {
        Boolean active = body.get("active") == null ? null
                : Boolean.parseBoolean(String.valueOf(body.get("active")));
        if (!admin.updateMember(id, text(body.get("displayName")), active)) {
            return notFound();
        }
        return ok();
    }

    // ---------------- 通道 ----------------

    @GetMapping("/channels")
    public List<DutyAdminStore.ChannelView> channels() {
        return admin.listChannels();
    }

    @PostMapping("/channels")
    public ResponseEntity<Map<String, Object>> createChannel(
            @RequestBody Map<String, Object> body) {
        String name = text(body.get("name"));
        String platform = text(body.get("platform"));
        String envKey = text(body.get("envKeyWebhook"));
        Integer priority = integer(body.get("priority"));
        if (name == null || platform == null || envKey == null || priority == null) {
            return badRequest("name/platform/envKeyWebhook/priority 必填");
        }
        if (!platform.equalsIgnoreCase("DINGTALK") && !platform.equalsIgnoreCase("WECOM")) {
            return badRequest("platform 只支持 DINGTALK/WECOM");
        }
        try {
            DutyAdminStore.ChannelView created = admin.insertChannel(name,
                    platform.toUpperCase(), envKey, text(body.get("envKeySecret")), priority,
                    Boolean.parseBoolean(String.valueOf(body.getOrDefault("isFallback", "false"))),
                    Boolean.parseBoolean(String.valueOf(body.getOrDefault("enabled", "true"))));
            return ResponseEntity.ok(Map.of("ok", true, "channel", created));
        } catch (org.springframework.dao.DataIntegrityViolationException e) {
            return ResponseEntity.status(409).body(Map.of("ok", false,
                    "error", "priority/fallback 冲突（每优先级一通道、fallback 恰一行）"));
        }
    }

    @PutMapping("/channels/{id}")
    public ResponseEntity<Map<String, Object>> updateChannel(
            @PathVariable UUID id, @RequestBody Map<String, Object> body) {
        Boolean isFallback = body.get("isFallback") == null ? null
                : Boolean.parseBoolean(String.valueOf(body.get("isFallback")));
        Boolean enabled = body.get("enabled") == null ? null
                : Boolean.parseBoolean(String.valueOf(body.get("enabled")));
        if (!admin.updateChannel(id, text(body.get("envKeyWebhook")),
                text(body.get("envKeySecret")), integer(body.get("priority")),
                isFallback, enabled)) {
            return notFound();
        }
        return ok();
    }

    // ---------------- 排班 / 层 / override ----------------

    @GetMapping("/schedule")
    public ResponseEntity<Map<String, Object>> schedule() {
        DutyAdminStore.ScheduleView view = admin.currentSchedule();
        if (view == null) {
            return ResponseEntity.ok(Map.of("exists", false));
        }
        return ResponseEntity.ok(Map.of(
                "exists", true,
                "schedule", view,
                "layers", admin.listLayers(),
                "overrides", admin.listOverrides()));
    }

    @PutMapping("/schedule/{id}")
    public ResponseEntity<Map<String, Object>> updateSchedule(
            @PathVariable UUID id, @RequestBody Map<String, Object> body) {
        if (!admin.updateSchedule(id, text(body.get("name")), text(body.get("timezone")),
                text(body.get("rotation")), text(body.get("anchorDate")),
                text(body.get("handoffTime")))) {
            return notFound();
        }
        return ok();
    }

    @PostMapping("/schedule/{id}/layers")
    public ResponseEntity<Map<String, Object>> addLayer(
            @PathVariable UUID id, @RequestBody Map<String, Object> body) {
        List<UUID> memberIds = uuidList(body.get("memberIds"));
        if (memberIds == null || memberIds.isEmpty()) {
            return badRequest("memberIds 必填（至少一名成员）");
        }
        try {
            return ResponseEntity.ok(Map.of("ok", true, "layer", admin.insertLayer(id, memberIds)));
        } catch (org.springframework.dao.DataIntegrityViolationException e) {
            return ResponseEntity.status(409).body(Map.of("ok", false, "error", "成员不存在"));
        }
    }

    @PutMapping("/layers/{id}")
    public ResponseEntity<Map<String, Object>> replaceLayer(
            @PathVariable UUID id, @RequestBody Map<String, Object> body) {
        List<UUID> memberIds = uuidList(body.get("memberIds"));
        if (memberIds == null) {
            return badRequest("memberIds 必填");
        }
        if (!admin.replaceLayerMembers(id, memberIds)) {
            return notFound();
        }
        return ok();
    }

    @DeleteMapping("/layers/{id}")
    public ResponseEntity<Map<String, Object>> deleteLayer(@PathVariable UUID id) {
        return admin.deleteLayer(id) ? ok() : notFound();
    }

    @PostMapping("/overrides")
    public ResponseEntity<Map<String, Object>> addOverride(
            @RequestBody Map<String, Object> body) {
        UUID scheduleId = uuid(body.get("scheduleId"));
        UUID memberId = uuid(body.get("memberId"));
        Instant startsAt = instant(text(body.get("startsAt")));
        Instant endsAt = instant(text(body.get("endsAt")));
        if (scheduleId == null || memberId == null || startsAt == null || endsAt == null
                || !endsAt.isAfter(startsAt)) {
            return badRequest("scheduleId/memberId/startsAt/endsAt 必填且窗口为正区间");
        }
        try {
            return ResponseEntity.ok(Map.of("ok", true,
                    "override", admin.insertOverride(scheduleId, memberId, startsAt, endsAt,
                            text(body.get("reason")))));
        } catch (org.springframework.dao.DataIntegrityViolationException e) {
            return ResponseEntity.status(409).body(Map.of("ok", false, "error", "成员/排班不存在"));
        }
    }

    @DeleteMapping("/overrides/{id}")
    public ResponseEntity<Map<String, Object>> deleteOverride(@PathVariable UUID id) {
        return admin.deleteOverride(id) ? ok() : notFound();
    }

    // ---------------- 内部

    private static ResponseEntity<Map<String, Object>> ok() {
        return ResponseEntity.ok(Map.of("ok", true));
    }

    private static ResponseEntity<Map<String, Object>> notFound() {
        return ResponseEntity.status(404).body(Map.of("ok", false, "error", "not_found"));
    }

    private static ResponseEntity<Map<String, Object>> badRequest(String message) {
        return ResponseEntity.badRequest().body(Map.of("ok", false, "error", message));
    }

    private static String text(Object value) {
        return value == null ? null : String.valueOf(value);
    }
    private static Integer integer(Object value) {
        return value instanceof Number n ? n.intValue() : null;
    }

    private static UUID uuid(Object value) {
        try {
            return value == null ? null : UUID.fromString(String.valueOf(value));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    private static List<UUID> uuidList(Object value) {
        if (!(value instanceof List<?> list)) {
            return null;
        }
        try {
            return ((List<Object>) list).stream().map(v -> UUID.fromString(String.valueOf(v)))
                    .toList();
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static Instant instant(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Instant.parse(value);
        } catch (DateTimeParseException e) {
            return null;
        }
    }
}
