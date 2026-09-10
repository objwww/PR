package com.objwww.pr.duty.hook;

import com.objwww.pr.duty.snapshot.SnapshotRefresher;
import com.objwww.pr.duty.snapshot.SnapshotState;
import com.objwww.pr.duty.webhook.DutyWebhookClient;
import com.objwww.pr.duty.writeback.WriteBackClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Gatus 接收入口（M7-17）：POST /hook/gatus（bearer 验签）→ 快照通道链直发
 * （errcode 校验，逐通道降级）→ best-effort 台账回写。
 *
 * <p>结局语义：202=事件已受理（至少一通道送达或已尽降级链）；503=无通道可尝试
 * （快照缺席/env 全缺）——Gatus 日志面可见；无重试面（Gatus 对 custom provider
 * 失败无重试语义，持续故障由下一次 firing 再报）。
 */
@RestController
public class DutyHookController {

    private static final Logger log = LoggerFactory.getLogger(DutyHookController.class);

    private final SnapshotRefresher snapshots;
    private final DutyWebhookClient webhooks;
    private final WriteBackClient writeBack;
    private final String token;

    public DutyHookController(SnapshotRefresher snapshots, DutyWebhookClient webhooks,
                              WriteBackClient writeBack,
                              @Value("${app.token}") String token) {
        this.snapshots = snapshots;
        this.webhooks = webhooks;
        this.writeBack = writeBack;
        this.token = token;
    }

    @PostMapping("/hook/gatus")
    public ResponseEntity<Map<String, Object>> hook(
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
            @RequestBody String rawBody) {
        if (!tokenMatches(authorization)) {
            return ResponseEntity.status(401).body(Map.of("error", "unauthorized"));
        }
        GatusEvent event = GatusEvent.parse(rawBody);
        if (event == null) {
            log.warn("DUTY_HOOK_UNPARSEABLE body_head={}", head(rawBody));
            return ResponseEntity.badRequest().body(Map.of("error", "unparseable_payload"));
        }

        SnapshotState snapshot = snapshots.current();
        String deliveredChannel = null;
        String lastDetail = "no_snapshot";
        if (snapshot != null) {
            lastDetail = "no_channel";
            String text = messageText(event, snapshot);
            for (SnapshotState.Channel channel : snapshot.channels()) {
                DutyWebhookClient.SendResult result = webhooks.send(channel, text);
                lastDetail = result.detail();
                if (result.ok()) {
                    deliveredChannel = result.channelName();
                    break;
                }
                log.warn("DUTY_CHANNEL_FAILED channel={} detail={}",
                        result.channelName(), result.detail());
            }
        }

        writeBack.push(new WriteBackClient.Event(
                event.eventStatus(), severityOf(event),
                "探针告警 " + event.endpoint() + " [" + event.eventStatus() + "]",
                eventBody(event), "gatus/" + event.group(),
                Instant.now().toString(), labelsOf(event)));

        if (deliveredChannel == null) {
            log.error("DUTY_HOOK_EXHAUSTED endpoint={} status={} detail={}",
                    event.endpoint(), event.status(), lastDetail);
            return ResponseEntity.status(503).body(Map.of(
                    "ok", false, "delivered", false, "detail", lastDetail));
        }
        return ResponseEntity.accepted().body(Map.of(
                "ok", true, "delivered", true, "channel", deliveredChannel));
    }

    @GetMapping("/health")
    public Map<String, Object> health() {
        return Map.of("ok", true);
    }

    /** 常量时间比较（沿 control-app MachineBearerAuthnFilter 时序侧信道防线） */
    private boolean tokenMatches(String authorization) {
        if (authorization == null || !authorization.regionMatches(true, 0, "Bearer ", 0, 7)) {
            return false;
        }
        String candidate = authorization.substring(7).trim();
        return MessageDigest.isEqual(candidate.getBytes(StandardCharsets.UTF_8),
                token.trim().getBytes(StandardCharsets.UTF_8));
    }

    private static String messageText(GatusEvent event, SnapshotState snapshot) {
        StringBuilder sb = new StringBuilder("【值班告警】");
        sb.append(event.eventStatus()).append(' ').append(event.endpoint()).append('\n');
        sb.append("当班: ").append(snapshot.onCall() == null ? "（未配置）" : snapshot.onCall());
        if (snapshot.viaFallback()) {
            sb.append("（fallback 通道）");
        }
        sb.append('\n');
        if (!event.description().isEmpty()) {
            sb.append("描述: ").append(event.description()).append('\n');
        }
        if (!event.errors().isEmpty()) {
            sb.append("错误: ").append(event.errors()).append('\n');
        }
        sb.append("时间: ").append(Instant.now());
        return sb.toString();
    }

    /** Gatus 无 severity 概念——不伪造（沿 AM7「值班通知不伪造严重度」）；reserved 给回写透传 */
    private static String severityOf(GatusEvent event) {
        return null;
    }

    private static String eventBody(GatusEvent event) {
        return "group=" + event.group() + " target=" + event.target()
                + " description=" + event.description() + " errors=" + event.errors();
    }

    private static Map<String, String> labelsOf(GatusEvent event) {
        Map<String, String> labels = new LinkedHashMap<>();
        labels.put("endpoint", event.endpoint());
        labels.put("group", event.group());
        if (!event.target().isEmpty()) {
            labels.put("target", event.target());
        }
        return labels;
    }

    private static String head(String body) {
        return body == null ? "" : body.substring(0, Math.min(200, body.length()));
    }
}
