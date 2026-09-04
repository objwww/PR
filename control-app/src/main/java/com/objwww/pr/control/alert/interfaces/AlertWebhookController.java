package com.objwww.pr.control.alert.interfaces;

import com.objwww.pr.control.alert.application.AlertIntakeService;
import com.objwww.pr.control.alert.application.IntakeRejectedException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.DataAccessException;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Map;
import java.util.UUID;

/**
 * 告警入口（§6.4）：POST /webhooks/alertmanager。
 *
 * <p>四类状态码语义（webhook.go 源码事实：仅 5xx 可恢复、Retry-After 头不被读取）：
 * <ul>
 *   <li>401 验签失败（常量时间比较；零落库）</li>
 *   <li>400/413 结构非法/超尺寸（零落库；AM 不重试 4xx）</li>
 *   <li>503 仅整组无法持久化（DB 故障；AM 仅对 5xx 整组重试）</li>
 *   <li>202 整组落库（含空组 IGNORED——202=已持久化）</li>
 * </ul>
 * 不落表 @Profile("docker") 之外的世界（沿旧线 WebhookController 惯例：默认 profile 无
 * DataSource，endpoint 随整条链路只在 docker profile 暴露；语义由 EX-A* 覆盖）。
 */
@RestController
@Profile("docker")
public class AlertWebhookController {

    private static final Logger log = LoggerFactory.getLogger(AlertWebhookController.class);

    private final AlertIntakeService intake;
    private final byte[] expectedBearer;

    public AlertWebhookController(AlertIntakeService intake,
                                  @Value("${app.alert.webhook.bearer}") String bearerToken) {
        this.intake = intake;
        this.expectedBearer = (bearerToken == null ? "" : bearerToken)
                .getBytes(StandardCharsets.UTF_8);
    }

    @PostMapping(path = "/webhooks/alertmanager", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Map<String, Object>> receive(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestHeader(value = "Content-Encoding", required = false) String contentEncoding,
            @RequestBody byte[] body) {

        if (!authorized(authorization)) {
            // INV-AM1-1：未验签零落库——不进 service，不触任何仓储
            return ResponseEntity.status(401).body(Map.of("error", "unauthorized"));
        }
        boolean gzip = contentEncoding != null
                && contentEncoding.toLowerCase().contains("gzip");

        try {
            UUID inboxId = intake.store(body, gzip);
            return ResponseEntity.accepted().body(Map.of("status", "accepted", "inboxId", inboxId.toString()));
        } catch (IntakeRejectedException e) {
            return ResponseEntity.status(e.httpStatus()).body(Map.of("error", e.getMessage()));
        } catch (DataAccessException e) {
            // 503 仅 DB 故障（AM 整组重试它该重试的）
            log.warn("整组无法持久化，返回 503: {}", e.getMostSpecificCause().getMessage());
            return ResponseEntity.status(503).body(Map.of("error", "storage unavailable"));
        }
    }

    /** 常量时间比较（Bearer 验签；防时序侧信道） */
    private boolean authorized(String authorizationHeader) {
        if (authorizationHeader == null) {
            return false;
        }
        String prefix = "Bearer ";
        if (authorizationHeader.length() <= prefix.length()
                || !authorizationHeader.regionMatches(true, 0, prefix, 0, prefix.length())) {
            return false;
        }
        byte[] provided = authorizationHeader.substring(prefix.length()).trim()
                .getBytes(StandardCharsets.UTF_8);
        return MessageDigest.isEqual(expectedBearer, provided);
    }
}
