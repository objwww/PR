package com.objwww.pr.control.alert.interfaces;

import com.objwww.pr.control.alert.domain.repository.NotifySilenceStore;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * 通知静默窗口管理面（前端产品化波次4，/api/v1 通知投影面的受控写面）：
 * 列出现行规则、创建窗口、人工停用。归 operator（同 /api/v1/** 授权矩阵）。
 * 时间参数一律显式 Timestamp（JdbcClient Instant 推断教训）。
 */
@RestController
@RequestMapping("/api/v1/notifications/silences")
public class NotifySilenceController {

    /** 有效时长上限 7 天（业界 maintenance window 常规上界），防呆 */
    private static final long MAX_HOURS = 24 * 7;

    private final NotifySilenceStore store;

    public NotifySilenceController(ObjectProvider<NotifySilenceStore> store) {
        this.store = store.getIfAvailable();
    }

    public record CreateRequest(String alertname, String service, String reason,
                                String createdBy, Double hours) {
    }

    @GetMapping
    public Map<String, Object> list() {
        if (store == null) {
            return Map.of("status", "UNAVAILABLE", "reason", "STORE_NOT_ASSEMBLED");
        }
        Instant now = Instant.now();
        List<Map<String, Object>> items = store.findActive(now).stream()
                .map(r -> {
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("id", r.id().toString());
                    item.put("alertname", r.alertname());
                    item.put("service", r.service());
                    item.put("reason", r.reason());
                    item.put("createdBy", r.createdBy());
                    item.put("createdAt", r.createdAt().toString());
                    item.put("expiresAt", r.expiresAt().toString());
                    item.put("remainingMinutes",
                            Math.max(0, (r.expiresAt().toEpochMilli() - now.toEpochMilli()) / 60000));
                    return item;
                })
                .toList();
        return Map.of("status", "OK", "items", items, "count", items.size());
    }

    @PostMapping
    public Map<String, Object> create(@RequestBody CreateRequest request) {
        Objects.requireNonNull(request.reason(), "reason 必填");
        if (request.reason().isBlank()) {
            return Map.of("status", "REJECTED", "reason", "REASON_REQUIRED");
        }
        double hours = request.hours() == null ? 4 : request.hours();
        if (hours <= 0 || hours > MAX_HOURS) {
            return Map.of("status", "REJECTED", "reason", "INVALID_HOURS");
        }
        if (store == null) {
            return Map.of("status", "UNAVAILABLE", "reason", "STORE_NOT_ASSEMBLED");
        }
        Instant now = Instant.now();
        UUID id = UUID.randomUUID();
        java.time.Duration window = java.time.Duration.ofSeconds((long) (hours * 3600));
        store.insert(new NotifySilenceStore.SilenceRow(id,
                blankToNull(request.alertname()), blankToNull(request.service()),
                request.reason().trim(),
                request.createdBy() == null || request.createdBy().isBlank()
                        ? "operator" : request.createdBy().trim(),
                now, now.plus(window), "ACTIVE"));
        return Map.of("status", "OK", "silence_id", id.toString(),
                "expiresAt", now.plus(window).toString());
    }

    @PostMapping("/{id}/disable")
    public Map<String, Object> disable(@PathVariable UUID id) {
        if (store == null) {
            return Map.of("status", "UNAVAILABLE", "reason", "STORE_NOT_ASSEMBLED");
        }
        boolean done = store.disable(id, Instant.now());
        return done ? Map.of("status", "OK")
                : Map.of("status", "REJECTED", "reason", "NOT_ACTIVE");
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
