package com.objwww.pr.control.interfaces.webhook;

import com.objwww.pr.control.application.IntakeService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * GitHub webhook 入口（interfaces）。职责收敛为：验签（401）→ 解析（400）→ 事件过滤（200 忽略）
 * → 交 IntakeService 异步派发（202）。HTTP 线程不做任何评审/存储重活（19 阶段表 #1）。
 *
 * <p>B-3：M0 不做 inbox 去重（M1 补）；同一 delivery 重投由 run_key 唯一约束兜底。
 *
 * <p>@Profile("docker")：无数据库的默认 profile 下 IntakeService 无法装配（验签通过也落不了
 * 接收记录），endpoint 随整条评审链路只在 docker profile 暴露；401/400/忽略语义由
 * WebhookControllerTest（MockMvc standalone）覆盖（EX-08）。
 */
@RestController
@Profile("docker")
public class WebhookController {

    private final GitHubSignatureVerifier verifier;
    private final GitHubWebhookParser parser;
    private final IntakeService intakeService;

    public WebhookController(@Value("${app.github.webhook-secret}") String webhookSecret,
                             IntakeService intakeService) {
        this.verifier = new GitHubSignatureVerifier(webhookSecret);
        this.parser = new GitHubWebhookParser();
        this.intakeService = intakeService;
    }

    @PostMapping("/webhooks/github")
    public ResponseEntity<Map<String, String>> handle(
            @RequestBody byte[] body,
            @RequestHeader(value = "X-Hub-Signature-256", required = false) String signature,
            @RequestHeader(value = "X-GitHub-Event", required = false) String eventType,
            @RequestHeader(value = "X-GitHub-Delivery", required = false) String deliveryId) {

        // 1) 验签（HMAC-SHA256）：失败 401，不解析不入库（EX-08）
        if (!verifier.verify(body, signature)) {
            return ResponseEntity.status(401).body(Map.of("error", "invalid_signature"));
        }

        // 2) 读 action；JSON 畸形 400
        final String action;
        try {
            action = parser.readAction(body);
        } catch (MalformedPayloadException e) {
            return ResponseEntity.badRequest().body(Map.of("error", "malformed_payload"));
        }

        // 3) 事件过滤：非 pull_request 或 action 不在 opened/synchronize/reopened → 200 忽略
        if (!parser.isHandled(eventType, action)) {
            return ResponseEntity.ok(Map.of("status", "ignored"));
        }

        // 4) 全量解析：缺必需字段 400
        final PullRequestEvent event;
        try {
            event = parser.parsePullRequest(body, deliveryId, action);
        } catch (MalformedPayloadException e) {
            return ResponseEntity.badRequest().body(Map.of("error", "malformed_payload"));
        }

        // 5) 快速 202：异步派发 T0/T1，HTTP 线程立即返回
        intakeService.accept(event, body);
        return ResponseEntity.accepted().body(Map.of("status", "accepted", "delivery", deliveryId));
    }
}
