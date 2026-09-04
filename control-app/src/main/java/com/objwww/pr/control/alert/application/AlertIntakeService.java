package com.objwww.pr.control.alert.application;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.objwww.pr.control.alert.domain.model.AlertFiringStatus;
import com.objwww.pr.control.alert.domain.model.AlertGroupEnvelope;
import com.objwww.pr.control.alert.domain.model.AlertInbox;
import com.objwww.pr.control.alert.domain.model.InboxState;
import com.objwww.pr.control.alert.domain.repository.AlertInboxRepository;
import com.objwww.pr.shared.Digest;
import com.objwww.pr.shared.Digests;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.zip.GZIPInputStream;

/**
 * 验签后的整组落库（§6.4：组协议完整性优先——HTTP 层无法按条拒绝，全部四类状态码语义在此兑现）。
 *
 * <p>拒绝链（4xx 零落库，AM 不重试）：body 超限/解压超限/超条数→413；
 * 畸形 JSON/缺 envelope 字段/label 长度超限/深度超限→400。
 * DB 故障→DataAccessException 上抛（controller 映射 503，AM 整组重试）。
 * alerts[] 空组：落 IGNORED 行（EX-A10，202=已持久化 + 行内审计）。
 */
public class AlertIntakeService {

    private final AlertInboxRepository inbox;
    private final AlertIntakeLimits limits;
    private final AlertClock clock;
    private final ObjectMapper mapper;

    public AlertIntakeService(AlertInboxRepository inbox, AlertIntakeLimits limits, AlertClock clock) {
        this.inbox = inbox;
        this.limits = limits;
        this.clock = clock;
        JsonFactory factory = new JsonFactory();
        factory.setStreamReadConstraints(StreamReadConstraints.builder()
                .maxNestingDepth(limits.maxDepth())
                .build());
        this.mapper = new ObjectMapper(factory);
    }

    /** 整组校验+落库；返回 inbox 行 id。 */
    public UUID store(byte[] raw, boolean gzipEncoded) {
        if (raw.length > limits.maxBodyBytes()) {
            throw new IntakeRejectedException(413, "请求体超过 " + limits.maxBodyBytes() + " 字节");
        }
        byte[] body = gzipEncoded ? gunzip(raw) : raw;

        JsonNode root = parse(body);
        if (!root.isObject()) {
            throw new IntakeRejectedException(400, "载荷必须是 JSON 对象");
        }

        String version = requiredText(root, "version");
        String receiver = requiredText(root, "receiver");
        String groupKey = requiredText(root, "groupKey");
        String statusRaw = requiredText(root, "status");
        AlertFiringStatus groupStatus = groupStatus(statusRaw);

        int truncated = root.path("truncatedAlerts").asInt(0);
        if (truncated < 0) {
            throw new IntakeRejectedException(400, "truncatedAlerts 不能为负");
        }

        JsonNode alertsNode = root.get("alerts");
        if (alertsNode == null || !alertsNode.isArray()) {
            throw new IntakeRejectedException(400, "缺少 alerts 数组");
        }
        if (alertsNode.size() > limits.maxAlerts()) {
            throw new IntakeRejectedException(413, "alerts 条数 " + alertsNode.size()
                    + " 超上限 " + limits.maxAlerts());
        }
        for (JsonNode alert : alertsNode) {
            validateAlert(alert);
        }

        Instant now = clock.now();
        AlertGroupEnvelope envelope = new AlertGroupEnvelope(
                version, receiver, groupKey,
                stringMap(root.path("groupLabels")),
                stringMap(root.path("commonLabels")),
                stringMap(root.path("commonAnnotations")),
                optionalText(root, "externalURL"),
                groupStatus,
                truncated,
                alertsNode.size(),
                body,
                new Digest(Digests.sha256Hex(body)));

        InboxState initial = alertsNode.isEmpty() ? InboxState.IGNORED : InboxState.RECEIVED;
        UUID id = UUID.randomUUID();
        inbox.insert(new AlertInbox(id, envelope, initial, null,
                null, null, 0, 0, 5, null, null, now, now,
                initial == InboxState.IGNORED ? now : null));
        return id;
    }

    // ------------------------------------------------------------------ 校验链

    private byte[] gunzip(byte[] raw) {
        try (GZIPInputStream in = new GZIPInputStream(new ByteArrayInputStream(raw));
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buf = new byte[8192];
            int total = 0;
            int n;
            while ((n = in.read(buf)) > 0) {
                total += n;
                if (total > limits.gzipMaxBytes()) {
                    throw new IntakeRejectedException(413, "gzip 解压后超过 " + limits.gzipMaxBytes() + " 字节");
                }
                out.write(buf, 0, n);
            }
            return out.toByteArray();
        } catch (IntakeRejectedException e) {
            throw e;
        } catch (Exception e) {
            throw new IntakeRejectedException(400, "gzip 解压失败: " + e.getMessage());
        }
    }

    private JsonNode parse(byte[] body) {
        try {
            return mapper.readTree(new String(body, StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new IntakeRejectedException(400, "JSON 解析失败: " + e.getMessage());
        }
    }

    private AlertFiringStatus groupStatus(String raw) {
        try {
            return AlertFiringStatus.fromRaw(raw);
        } catch (IllegalArgumentException e) {
            throw new IntakeRejectedException(400, "status 非法: " + raw);
        }
    }

    private void validateAlert(JsonNode alert) {
        if (!alert.isObject()) {
            throw new IntakeRejectedException(400, "alerts 条目必须是 JSON 对象");
        }
        String status = requiredText(alert, "status");
        if (!"firing".equals(status) && !"resolved".equals(status)) {
            throw new IntakeRejectedException(400, "alert.status 非法: " + status);
        }
        requiredText(alert, "fingerprint");
        checkLabelBounds(stringMap(alert.path("labels")), "labels");
        checkLabelBounds(stringMap(alert.path("annotations")), "annotations");
        try {
            Instant.parse(requiredText(alert, "startsAt"));
        } catch (DateTimeParseException e) {
            throw new IntakeRejectedException(400, "startsAt 非法");
        }
        if (alert.hasNonNull("endsAt")) {
            try {
                Instant.parse(alert.get("endsAt").asText());
            } catch (DateTimeParseException e) {
                throw new IntakeRejectedException(400, "endsAt 非法");
            }
        }
    }

    private void checkLabelBounds(Map<String, String> map, String what) {
        int total = 0;
        for (Map.Entry<String, String> e : map.entrySet()) {
            if (e.getValue().length() > limits.maxLabelChars()) {
                throw new IntakeRejectedException(400,
                        what + " 值超长: " + e.getKey() + " > " + limits.maxLabelChars());
            }
            total += e.getKey().length() + e.getValue().length();
        }
        if (total > limits.maxTotalLabelChars()) {
            throw new IntakeRejectedException(400, what + " 总长超限 " + total);
        }
    }

    private String requiredText(JsonNode node, String field) {
        JsonNode v = node.get(field);
        if (v == null || !v.isTextual() || v.asText().isBlank()) {
            throw new IntakeRejectedException(400, "缺少/非法字段: " + field);
        }
        return v.asText();
    }

    private String optionalText(JsonNode node, String field) {
        JsonNode v = node.get(field);
        return v != null && v.isTextual() ? v.asText() : null;
    }

    /** 值必须全为字符串的 map 字段（AM 协议保证；漂移即 400） */
    private Map<String, String> stringMap(JsonNode node) {
        Map<String, String> out = new LinkedHashMap<>();
        if (node == null || node.isMissingNode() || node.isNull()) {
            return out;
        }
        if (!node.isObject()) {
            throw new IntakeRejectedException(400, "标签字段必须是对象");
        }
        for (Iterator<Map.Entry<String, JsonNode>> it = node.fields(); it.hasNext(); ) {
            Map.Entry<String, JsonNode> e = it.next();
            if (!e.getValue().isTextual()) {
                throw new IntakeRejectedException(400, "标签值必须是字符串: " + e.getKey());
            }
            out.put(e.getKey(), e.getValue().asText());
        }
        return out;
    }
}
