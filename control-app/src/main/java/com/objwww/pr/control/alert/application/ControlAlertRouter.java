package com.objwww.pr.control.alert.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.objwww.pr.control.alert.domain.model.AlertGroupEnvelope;
import com.objwww.pr.control.alert.domain.model.AlertGroupSummary;
import com.objwww.pr.control.alert.domain.model.AlertInbox;
import com.objwww.pr.control.alert.domain.model.AlertFiringStatus;
import com.objwww.pr.control.alert.domain.model.InboxDecision;
import com.objwww.pr.control.alert.domain.model.InboxState;
import com.objwww.pr.control.alert.domain.repository.AlertInboxRepository;
import com.objwww.pr.control.infrastructure.observability.StructuredLog;
import com.objwww.pr.shared.Digest;
import com.objwww.pr.shared.Digests;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.zip.GZIPInputStream;

/**
 * M5-16 控制面防自噬路由：webhook 入口二次判断（落码方案 §M5-16①；INV-AM5-4）。
 *
 * <p>判定链（三面全过才算控制面，任一失败 fail-closed 拒绝）：
 * <ol>
 *   <li>身份：独立控制面 bearer（常量时间比较；配置为空 = 恒拒）；</li>
 *   <li>AM route 白名单：envelope.receiver ∈ allowed-receivers；</li>
 *   <li>monitoring_scope 白名单：组内 scope 值全 ∈ allowed-scopes。</li>
 * </ol>
 * body 里的同名 label（route/monitoring_scope）<b>单独不作数</b>（方案 §3.2）——
 * 伪造控制面 label 而无控制面身份 → 401 拒绝零落库；有身份但 route/scope 越白名单 → 400。
 *
 * <p>ROUTED 落点 = alert_inbox 直写 {@code PROCESSED + SUPPRESSED}（V7 decision check
 * 预留枚举的消费者；无迁移约束下的投递记录锚）：payload_raw 审计唯一权威照存，
 * claim 面永不领取（结构性证明"不创建 Incident/Run"）。值班通道投递 = 结构化事件
 * CONTROL_ALERT_ONCALL（仅标识符字段，非内容纪律同 StructuredLog）。
 *
 * <p>结构垃圾（畸形 JSON/缺 envelope 必填）一律 PASS_THROUGH——intake 是结构拒绝链
 * 的唯一权威（4xx 语义单源）；声明扫描只在结构可读的组上进行。
 */
public class ControlAlertRouter {

    private static final Logger log = LoggerFactory.getLogger(ControlAlertRouter.class);
    private static final String CLAIM_FAMILY_PREFIX = "RCA_SYSTEM";
    private static final String SCOPE_LABEL = "monitoring_scope";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    public enum Outcome { PASS_THROUGH, ROUTED_ONCALL, REJECTED }

    /**
     * @param httpStatus 仅 REJECTED 有意义（401 身份失败 / 400 白名单越界）；其余 0
     * @param group      仅 ROUTED_ONCALL 非空——值班派发消费的组级标识摘要（M7-13）
     */
    public record Decision(Outcome outcome, int httpStatus, String reason, UUID inboxId,
                    AlertGroupSummary group) {

        static Decision passThrough() {
            return new Decision(Outcome.PASS_THROUGH, 0, null, null, null);
        }

        static Decision routedOnCall(UUID inboxId, AlertGroupSummary group) {
            return new Decision(Outcome.ROUTED_ONCALL, 0, null, inboxId, group);
        }

        static Decision rejected(int httpStatus, String reason) {
            return new Decision(Outcome.REJECTED, httpStatus, reason, null, null);
        }
    }

    private final String controlBearer;
    private final Set<String> allowedReceivers;
    private final Set<String> allowedScopes;
    private final AlertInboxRepository inbox;
    private final AlertClock clock;
    private final AlertIntakeLimits limits;

    public ControlAlertRouter(String controlBearer,
                              Set<String> allowedReceivers,
                              Set<String> allowedScopes,
                              AlertInboxRepository inbox,
                              AlertClock clock,
                              AlertIntakeLimits limits) {
        this.controlBearer = controlBearer == null ? "" : controlBearer;
        this.allowedReceivers = Set.copyOf(allowedReceivers);
        this.allowedScopes = Set.copyOf(allowedScopes);
        this.inbox = inbox;
        this.clock = clock;
        this.limits = limits;
    }

    /** 入口二次判断。结构问题放行（intake 权威拒绝）；控制面声明走三面门。 */
    public Decision guard(byte[] raw, boolean gzipEncoded, String authorizationHeader) {
        byte[] body = decompress(raw, gzipEncoded);
        if (body == null || body.length > limits.maxBodyBytes()) {
            return Decision.passThrough();   // 解压失败/超界由 intake 出权威 4xx
        }
        JsonNode root;
        try {
            root = MAPPER.readTree(new String(body, StandardCharsets.UTF_8));
        } catch (Exception e) {
            return Decision.passThrough();
        }
        JsonNode receiverNode = root.path("receiver");
        String receiver = receiverNode.isTextual() ? receiverNode.asText() : null;
        if (receiver == null || !root.path("alerts").isArray()) {
            return Decision.passThrough();
        }
        // 条数与组状态结构前置（intake 权威 4xx 的面）——ROUTED 直写不重走 intake 校验链，
        // 超限/垃圾值不能借三面门内的路径绕过权威拒绝或炸成 500
        JsonNode statusNode = root.path("status");
        if (root.path("alerts").size() > limits.maxAlerts()
                || !statusNode.isTextual()
                || (!"firing".equals(statusNode.asText()) && !"resolved".equals(statusNode.asText()))) {
            return Decision.passThrough();
        }

        Set<String> scopes = claimedScopes(root);
        if (!claims(root, scopes)) {
            return Decision.passThrough();
        }

        // 门 1：身份（配置空 = 恒拒；常量时间比较）
        if (controlBearer.isBlank() || !bearerEquals(authorizationHeader, controlBearer)) {            logRejected(receiver, "identity");
            return Decision.rejected(401, "control claim rejected: identity");
        }
        // 门 2：AM route 白名单
        if (!allowedReceivers.contains(receiver)) {
            logRejected(receiver, "route_not_allowlisted");
            return Decision.rejected(400, "control claim rejected: route not allowlisted");
        }
        // 门 3：monitoring_scope 白名单（声明组必须有可校验 scope）
        if (scopes.isEmpty() || !allowedScopes.containsAll(scopes)) {
            logRejected(receiver, "scope_not_allowlisted");
            return Decision.rejected(400, "control claim rejected: scope not allowlisted");
        }

        return routeToOnCall(root, body, receiver);
    }

    // ------------------------------------------------------------------ 判定面

    /** 声明两形：RCA_SYSTEM 家族 alertname，或任一 alert 带非空 monitoring_scope label */
    private static boolean claims(JsonNode root, Set<String> scopes) {
        if (!scopes.isEmpty()) {
            return true;
        }
        for (JsonNode alert : root.path("alerts")) {
            JsonNode alertname = alert.path("labels").path("alertname");
            if (alertname.isTextual() && alertname.asText().startsWith(CLAIM_FAMILY_PREFIX)) {
                return true;
            }
        }
        return false;
    }

    /** 组内全部 alert 的非空 monitoring_scope 值（commonLabels 也在 AM 协议面内） */
    private static Set<String> claimedScopes(JsonNode root) {
        Set<String> scopes = new LinkedHashSet<>();
        for (JsonNode alert : root.path("alerts")) {
            collectScope(alert.path("labels"), scopes);
        }
        collectScope(root.path("commonLabels"), scopes);
        return scopes;
    }

    private static void collectScope(JsonNode labels, Set<String> into) {
        JsonNode scope = labels.path(SCOPE_LABEL);
        if (scope.isTextual() && !scope.asText().isBlank()) {
            into.add(scope.asText());
        }
    }

    /** 常量时间比较（与 webhook 入口同纪律）；缺/畸形 Authorization 头即不等 */
    private static boolean bearerEquals(String authorizationHeader, String expected) {
        String prefix = "Bearer ";
        if (authorizationHeader == null || authorizationHeader.length() <= prefix.length()
                || !authorizationHeader.regionMatches(true, 0, prefix, 0, prefix.length())) {
            return false;
        }
        byte[] provided = authorizationHeader.substring(prefix.length()).trim()
                .getBytes(StandardCharsets.UTF_8);
        return MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8), provided);
    }

    // ------------------------------------------------------------------ ROUTED 面

    /** 投递记录锚：PROCESSED+SUPPRESSED 直写（claim 面不可达）+ 值班通道结构化事件 */
    private Decision routeToOnCall(JsonNode root, byte[] body, String receiver) {
        Instant now = clock.now();
        AlertGroupEnvelope envelope = envelopeOf(root, body);
        UUID id = UUID.randomUUID();
        inbox.insert(new AlertInbox(id, envelope, InboxState.PROCESSED, InboxDecision.SUPPRESSED,
                null, null, 0, 0, 5, null, null, now, now, now));

        Set<String> alertnames = new LinkedHashSet<>();
        Instant startsAt = null;
        for (JsonNode alert : root.path("alerts")) {
            JsonNode alertname = alert.path("labels").path("alertname");
            if (alertname.isTextual()) {
                alertnames.add(alertname.asText());
            }
            JsonNode sa = alert.path("startsAt");
            if (sa.isTextual()) {
                try {
                    Instant t = Instant.parse(sa.asText());
                    startsAt = startsAt == null || t.isBefore(startsAt) ? t : startsAt;
                } catch (RuntimeException ignore) {
                    // 非 ISO 时刻按缺席处理（身份铸造用 now 兜底，不因畸形字段拒路由）
                }
            }
        }
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("inbox_id", id.toString());
        fields.put("receiver", receiver);
        fields.put("group_key", envelope.groupKey());
        fields.put("alertnames", String.join(",", alertnames));
        fields.put("scopes", String.join(",", claimedScopes(root)));
        fields.put("alert_count", envelope.alertCount());
        StructuredLog.event(log, "CONTROL_ALERT_ONCALL", fields);

        // M7-13：组级标识摘要随行（值班派发只消费标识符字段；startsAt 缺席=now 兜底）
        return Decision.routedOnCall(id, new AlertGroupSummary(receiver, envelope.groupKey(),
                root.path("status").asText(), textMap(root.path("commonLabels")),
                List.copyOf(alertnames), startsAt == null ? now : startsAt,
                envelope.alertCount()));
    }

    private static AlertGroupEnvelope envelopeOf(JsonNode root, byte[] body) {
        return new AlertGroupEnvelope(
                root.path("version").asText("4"),
                root.path("receiver").asText(),
                root.path("groupKey").asText(),
                textMap(root.path("groupLabels")),
                textMap(root.path("commonLabels")),
                textMap(root.path("commonAnnotations")),
                root.path("externalURL").isTextual() ? root.path("externalURL").asText() : null,
                AlertFiringStatus.fromRaw(root.path("status").asText()),
                root.path("truncatedAlerts").asInt(0),
                root.path("alerts").size(),
                body,
                new Digest(Digests.sha256Hex(body)));
    }

    private static Map<String, String> textMap(JsonNode node) {
        Map<String, String> out = new LinkedHashMap<>();
        if (node.isObject()) {
            node.fields().forEachRemaining(e -> {
                if (e.getValue().isTextual()) {
                    out.put(e.getKey(), e.getValue().asText());
                }
            });
        }
        return out;
    }

    // ------------------------------------------------------------------ 解压面

    /** 与 intake 同上限；任何解压失败返回 null（intake 权威 400），防解压炸弹在此放大 */
    private byte[] decompress(byte[] raw, boolean gzipEncoded) {
        if (raw.length > limits.maxBodyBytes()) {
            return raw;   // 超界原样交 intake 权威 413
        }
        if (!gzipEncoded) {
            return raw;
        }
        try (GZIPInputStream in = new GZIPInputStream(new ByteArrayInputStream(raw));
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
                if (out.size() > limits.gzipMaxBytes()) {
                    return null;   // 炸弹 → intake 权威 413（此处不落库）
                }
            }
            return out.toByteArray();
        } catch (Exception e) {
            return null;
        }
    }

    private static void logRejected(String receiver, String gate) {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("receiver", receiver);
        fields.put("gate", gate);
        StructuredLog.event(log, "CONTROL_ALERT_REJECTED", fields);
    }
}
