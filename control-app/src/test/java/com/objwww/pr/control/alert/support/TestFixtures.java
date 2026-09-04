package com.objwww.pr.control.alert.support;

import com.objwww.pr.control.alert.domain.model.AlertFiringStatus;
import com.objwww.pr.control.alert.domain.model.AlertGroupEnvelope;
import com.objwww.pr.control.alert.domain.model.AlertInbox;
import com.objwww.pr.control.alert.domain.model.InboxState;
import com.objwww.pr.shared.Digest;
import com.objwww.pr.shared.Digests;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * 告警域测试种子（ST 场景与单测共用；沿旧线 TestFixtures 惯例）。
 */
public final class TestFixtures {

    private TestFixtures() {
    }

    /** 一条合法 AM 组载荷（默认 firing + 1 条 critical 告警） */
    public static String amWebhookBody(String alertName, String service, String severity, String status) {
        return """
                {
                  "version": "4",
                  "receiver": "control-app",
                  "groupKey": "g-%s-%s",
                  "groupLabels": {"alertname": "%s"},
                  "commonLabels": {"alertname": "%s", "service": "%s", "severity": "%s"},
                  "commonAnnotations": {"summary": "s"},
                  "externalURL": "http://am.local",
                  "status": "%s",
                  "alerts": [
                    {
                      "status": "%s",
                      "labels": {"alertname": "%s", "service": "%s", "severity": "%s"},
                      "annotations": {"summary": "s", "runbook": "rb-1"},
                      "startsAt": "2026-09-03T09:00:00Z",
                      "endsAt": "0001-01-01T00:00:00Z",
                      "generatorURL": "http://prom.local/graph",
                      "fingerprint": "fp-%s-%s"
                    }
                  ],
                  "truncatedAlerts": 0
                }
                """.formatted(alertName, service, alertName, alertName, service, severity,
                status, status, alertName, service, severity, alertName, service);
    }

    /** alert_inbox 行（组信封 + 处理机初值 RECEIVED） */
    public static AlertInbox inboxRow(UUID id, String groupKey, InboxState state) {
        byte[] raw = ("{\"groupKey\":\"" + groupKey + "\"}").getBytes(StandardCharsets.UTF_8);
        AlertGroupEnvelope envelope = new AlertGroupEnvelope(
                "4", "control-app", groupKey,
                Map.of("alertname", "HighErrorRate"), Map.of(), Map.of(),
                "http://am.local", AlertFiringStatus.FIRING, 0, 1,
                raw, new Digest(Digests.sha256Hex(raw)));
        Instant now = Instant.parse("2026-09-03T10:00:00Z");
        return new AlertInbox(id, envelope, state, null, null, null, 0,
                0, 5, null, null, now, now, null);
    }

    /** 单条 alert JSON（ST 场景拼组用；summary 变化 = 材料变化，startsAt 变化 = episode 判定） */
    public static String alertJson(String alertName, String service, String severity, String status,
                                   String startsAt, String summary) {
        return """
                {"status": "%s", "labels": {"alertname": "%s", "service": "%s", "severity": "%s"},
                 "annotations": {"summary": "%s", "runbook": "rb-1"},
                 "startsAt": "%s", "endsAt": "0001-01-01T00:00:00Z",
                 "generatorURL": "http://prom.local/graph",
                 "fingerprint": "fp-%s-%s-%s"}
                """.formatted(status, alertName, service, severity, summary, startsAt,
                alertName, service, severity);
    }

    /** 多条 alert 拼整组（投影器只消费 payloadRaw，envelope 其余字段仅审计） */
    public static String amGroup(String groupKey, int truncatedAlerts, String... alerts) {
        return """
                {"version": "4", "receiver": "control-app", "groupKey": "%s",
                 "groupLabels": {}, "commonLabels": {}, "commonAnnotations": {},
                 "externalURL": "http://am.local", "status": "firing",
                 "alerts": [%s], "truncatedAlerts": %d}
                """.formatted(groupKey, String.join(",", alerts), truncatedAlerts);
    }

    /** 载荷真实可投影的 inbox 行（RECEIVED；truncatedAlerts/alertCount 从载荷解析，畸形 JSON 按空组造行——死信用例入口） */
    public static AlertInbox inboxRowOf(UUID id, String payload) {
        byte[] raw = payload.getBytes(StandardCharsets.UTF_8);
        int truncatedAlerts = 0;
        int alertCount = 0;
        try {
            com.fasterxml.jackson.databind.JsonNode root =
                    new com.fasterxml.jackson.databind.ObjectMapper().readTree(payload);
            truncatedAlerts = root.path("truncatedAlerts").asInt(0);
            alertCount = root.path("alerts").size();
        } catch (Exception ignored) {
            // 载荷腐坏场景（DEAD_LETTER 用例）：envelope 只承载 raw，投影器解析失败
        }
        AlertGroupEnvelope envelope = new AlertGroupEnvelope(
                "4", "control-app", "g-test",
                Map.of(), Map.of(), Map.of(), null,
                AlertFiringStatus.FIRING, truncatedAlerts, alertCount,
                raw, new Digest(Digests.sha256Hex(raw)));
        Instant now = Instant.parse("2026-09-03T10:00:00Z");
        return new AlertInbox(id, envelope, InboxState.RECEIVED, null, null, null, 0,
                0, 5, null, null, now, now, null);
    }
}
