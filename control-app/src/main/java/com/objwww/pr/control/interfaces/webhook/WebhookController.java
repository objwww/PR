package com.objwww.pr.control.interfaces.webhook;

import com.objwww.pr.control.domain.model.WebhookInbox;
import com.objwww.pr.control.domain.repository.WebhookInboxRepository;
import com.objwww.pr.shared.Digests;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Optional;

/**
 * GitHub webhook 入口（interfaces，M1-T03 两段式改造，方案 §4.2 HTTP 线程段）。
 * 职责瘦身为三件事，目标 <100ms、短事务：
 * <ol>
 *   <li>验签：HMAC-SHA256 对 raw body（401 不落库，EX-08 语义不动）；</li>
 *   <li>落 inbox：payload_raw=原始字节（HMAC 复核与审计的唯一权威，CT-18）、
 *       payload_json=尝试 ::jsonb（畸形 JSON 置 NULL 不报错，E2E-22）、
 *       payload_digest=sha256(raw 字节)、github_event/action 尽力提取；</li>
 *   <li>立即应答 202。</li>
 * </ol>
 * 事件过滤与全量解析全部后移 InboxProcessor——一切签名合法事件都落 RECEIVED 留痕
 * （INC-16 关闭；M0 的 400/200-ignored 入口语义随之变更，见类尾行为变更说明）。
 *
 * <p>重投语义（insertNew 主键冲突 = false，I9/I13）：
 * digest 相同 → 按原行 state 回放原结果（{@link RedeliveryDecision}）；
 * digest 不同 → 409 + 安全告警，原行不覆盖（EX-13）。
 *
 * <p>安全告警的诚实边界（EX-13 的落库缺口）：方案要求"追加安全告警 execution_event"，
 * 但 V1 schema 的 execution_event.review_run_id / pr_revision_id 为 NOT NULL + FK，
 * 且 ExecutionEvent record 自身 requireNonNull——入口时刻无 Run/Revision 可挂，
 * 合法挂接需要 schema 变更（V4 决策：允许 NULL 或专设安全事件表）。本版本不改 V3，
 * 以 WARN 级安全日志替代（digest 对、delivery、事件头入日志；payload/密钥永入日志）。
 *
 * <p>@Profile("docker")：理由同 M0——无 DataSource 的默认 profile 下 inbox 无从装配，
 * endpoint 随整条链路只在 docker profile 暴露；语义由 WebhookControllerTest 覆盖。
 *
 * <p>行为变更（相对 M0）：畸形 JSON 400（EX-08 旧裁决）→ 202 + inbox 留 raw 审计
 * （E2E-22 新裁决）；非处理事件 200 ignored 不落库 → 202 + RECEIVED 行（ST-16）；
 * 缺必需字段 400 → 202 + 由 Processor 判死信。
 */
@RestController
@Profile("docker")
public class WebhookController {

    private static final Logger log = LoggerFactory.getLogger(WebhookController.class);

    private final GitHubSignatureVerifier verifier;
    private final GitHubWebhookParser parser;
    private final WebhookInboxRepository inbox;

    public WebhookController(@Value("${app.github.webhook-secret}") String webhookSecret,
                             WebhookInboxRepository inbox) {
        this.verifier = new GitHubSignatureVerifier(webhookSecret);
        this.parser = new GitHubWebhookParser();
        this.inbox = inbox;
    }

    @PostMapping("/webhooks/github")
    public ResponseEntity<Map<String, String>> handle(
            @RequestBody byte[] body,
            @RequestHeader(value = "X-Hub-Signature-256", required = false) String signature,
            @RequestHeader(value = "X-GitHub-Event", required = false) String eventType,
            @RequestHeader(value = "X-GitHub-Delivery", required = false) String deliveryId) {

        // 1) 验签（HMAC-SHA256 对 raw body）：失败 401，不落库（EX-08 不动）
        if (!verifier.verify(body, signature)) {
            return ResponseEntity.status(401).body(Map.of("error", "invalid_signature"));
        }

        // delivery_id 是 inbox 主键（去重与重投语义的全部基础）：缺失则无从落库，400
        if (deliveryId == null || deliveryId.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "missing_delivery"));
        }

        // 2) 落库元数据一遍解析：畸形 JSON 不报错——action/ids 置 null、payload_json 置 NULL，
        //    原始字节照样落 raw 留审计（E2E-22）；payloadJson 只在 JSON 合法时非 null
        //    （port 契约：payloadJson 必须是已验证合法的 JSON 文本，否则 ::jsonb 强转会炸）
        String action = null;
        Long installationId = null;
        Long repositoryId = null;
        String payloadJson = null;
        try {
            GitHubWebhookParser.EntryMeta meta = parser.readEntryMeta(body);
            action = meta.action();
            installationId = meta.installationId();
            repositoryId = meta.repositoryId();
            payloadJson = new String(body, StandardCharsets.UTF_8);
        } catch (MalformedPayloadException e) {
            // 合法签名 + 畸形 JSON：payload_raw 是唯一权威，照常落库（E2E-22）
        }

        // digest 对原始字节计算（CT-18：与 HMAC 同一批字节，不经 String 转换失真）
        String digest = Digests.sha256Hex(body);
        boolean inserted = inbox.insertNew(deliveryId,
                eventType != null && !eventType.isBlank() ? eventType : "unknown",
                action, installationId, repositoryId, body, payloadJson, digest);
        if (inserted) {
            // 3) 立即应答：处理是 InboxProcessor 的事
            return ResponseEntity.accepted().body(Map.of("status", "accepted", "delivery", deliveryId));
        }

        // 4) 主键冲突 = 重投/重放（I9）：查原行按 digest 与 state 应答，原行永不覆盖（I13）
        Optional<WebhookInbox> existing = inbox.findByDeliveryId(deliveryId);
        if (existing.isEmpty()) {
            // 防御性分支：冲突却读不到行（理论不可达）——按冲突拒绝，绝不覆盖
            log.warn("inbox 主键冲突但读不到原行 delivery={}", deliveryId);
            return ResponseEntity.status(409).body(Map.of("error", "inbox_conflict"));
        }
        WebhookInbox row = existing.get();
        if (!row.getPayloadDigest().equals(digest)) {
            // EX-13：同 delivery 异 digest = 重放/篡改嫌疑。execution_event 落库被 V1 schema
            // 挡住（见类注释），本版本以 WARN 安全日志替代；payload/密钥不入日志。
            log.warn("安全告警：同 delivery 异 digest 重投（疑似重放/篡改） delivery={} event={} 原digest={} 新digest={}",
                    deliveryId, eventType, row.getPayloadDigest(), digest);
            return ResponseEntity.status(409).body(Map.of("error", "digest_mismatch"));
        }
        return switch (RedeliveryDecision.of(row.getState())) {
            case DUPLICATE ->
                    ResponseEntity.ok(Map.of("status", "duplicate", "delivery", deliveryId));
            case PROCESSING ->
                    ResponseEntity.accepted().body(Map.of("status", "processing", "delivery", deliveryId));
            case DEAD_LETTER ->
                // I16：死信不被重投唤醒，如实相告；复活只有显式管理操作（CT-16）
                    ResponseEntity.ok(Map.of("status", "dead_letter", "delivery", deliveryId));
        };
    }
}
