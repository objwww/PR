package com.objwww.pr.control.alert.interfaces;

import com.objwww.pr.control.alert.domain.model.AlertInbox;
import com.objwww.pr.control.alert.domain.repository.AlertInboxRepository;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * AM8 隔离区人工放行面（PA-A3 状态机 QUARANTINED→RECEIVED 边的管理入口）：
 * 注入告警人工复核后放行重驱。归 operator 角色；放行人入 last_error 审计列。
 */
@RestController
@RequestMapping("/api/inbox-admin")
public class InboxAdminController {

    private final AlertInboxRepository inbox;
    private final Clock clock;

    public InboxAdminController(ObjectProvider<AlertInboxRepository> inbox) {
        this.inbox = inbox.getIfAvailable();
        this.clock = Clock.systemUTC();
    }

    public record ReleaseRequest(String releasedBy) {
    }

    @PostMapping("/alerts/{id}/release")
    public Map<String, Object> release(@PathVariable UUID id,
            @org.springframework.web.bind.annotation.RequestBody ReleaseRequest request) {
        Objects.requireNonNull(request.releasedBy(), "releasedBy 必填");
        Map<String, Object> body = new LinkedHashMap<>();
        if (inbox == null) {
            body.put("status", "UNAVAILABLE");
            body.put("reason", "INBOX_FACE_NOT_ASSEMBLED");
            return body;
        }
        Instant now = clock.instant();
        body.put("released", inbox.releaseQuarantined(id, request.releasedBy(), now));
        body.put("state", "RECEIVED");
        return body;
    }

    /** 隔离区清单（前端产品化波次1 审批处置页）：QUARANTINED 行 + 命中特征审计 */
    @org.springframework.web.bind.annotation.GetMapping("/quarantined")
    public Map<String, Object> quarantined() {
        Map<String, Object> body = new LinkedHashMap<>();
        if (inbox == null) {
            body.put("status", "UNAVAILABLE");
            body.put("reason", "INBOX_FACE_NOT_ASSEMBLED");
            return body;
        }
        java.util.List<Map<String, Object>> items = new java.util.ArrayList<>();
        for (AlertInbox row : inbox.listQuarantined()) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", row.id().toString());
            item.put("group_key", row.envelope() == null ? null : row.envelope().groupKey());
            var labels = row.envelope() == null ? Map.<String, String>of()
                    : row.envelope().groupLabels();
            item.put("alertname", labels.getOrDefault("alertname", "—"));
            item.put("service", labels.getOrDefault("service", "—"));
            item.put("severity", labels.getOrDefault("severity", "—"));
            item.put("state", row.state().name());
            item.put("alert_count", row.envelope() == null ? null : row.envelope().alertCount());
            item.put("last_error", row.lastError());
            item.put("received_at", row.receivedAt().toString());
            items.add(item);
        }
        body.put("status", "OK");
        body.put("items", items);
        body.put("count", items.size());
        return body;
    }
}
