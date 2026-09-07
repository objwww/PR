package com.objwww.pr.control.release.interfaces;

import com.objwww.pr.control.release.application.ConfigBundleService;
import com.objwww.pr.control.release.domain.repository.ConfigBundleRepository.ActivePointer;
import com.objwww.pr.shared.Digest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Map;

/**
 * ConfigBundle 发布/回滚/查询 API（M5-09 §M5-09③ 契约；RBAC=release 角色）。
 *
 * <p>RBAC 现状（O-4 开放项：control-app 全系统无用户体系）：沿 AlertWebhookController
 * 惯例——静态 bearer（{@code app.release.api.bearer}，release 角色共享凭证）+
 * 常量时间比较 + @Profile("docker") 只在部署面暴露；O-4 用户体系落地后切角色鉴权，
 * 本类的 401 面是切换点。
 *
 * <p>幂等语义：POST /api/config-bundles 的幂等锚 = 内容 canonical digest（同内容
 * 重发返回 replayed=true，不新增行）；Idempotency-Key 头仅随审计日志留痕（发布面
 * 无存储列，O-4 面可补）。activate 的 canaryPercent/whitelist 为 M5-10 CanaryRouter
 * 消费面——本门只落 pointer（审计行留痕），不冒充放量语义。
 */
@RestController
@Profile("docker")
public class ConfigBundleController {

    private static final Logger log = LoggerFactory.getLogger(ConfigBundleController.class);

    private final ConfigBundleService service;
    private final byte[] expectedBearer;

    public ConfigBundleController(ConfigBundleService service,
                                  @Value("${app.release.api.bearer}") String bearerToken) {
        this.service = service;
        this.expectedBearer = (bearerToken == null ? "" : bearerToken)
                .getBytes(StandardCharsets.UTF_8);
    }

    /**
     * 发布：契约 body = {content{...}, expectedRevision?}（落码方案 §M5-09③）；
     * content 必带（缺/非对象 → 400）。expectedRevision 与 activate 的
     * canaryPercent/whitelist 同为本期只收不用（M5-10 消费面，C 决策记账）。
     */
    @PostMapping(path = "/api/config-bundles", consumes = "application/json")
    public ResponseEntity<Map<String, Object>> publish(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestBody Map<String, Object> body) {
        if (!authorized(authorization)) {
            return ResponseEntity.status(401).body(Map.of("error", "unauthorized"));
        }
        if (!(body.get("content") instanceof Map)) {
            return ResponseEntity.badRequest().body(Map.of("error", "content 必须是对象"));
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> content = (Map<String, Object>) body.get("content");
        try {
            ConfigBundleService.PublishResult result =
                    service.publish(content, actorOf(idempotencyKey));
            return ResponseEntity.ok(Map.of(
                    "bundleDigest", result.bundleDigest().hex(),
                    "revision", result.revision(),
                    "replayed", result.replayed()));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    /** 原子激活（CAS）：409 = 指针已被并发移走（败者面，零状态改写） */
    @PostMapping(path = "/api/config-bundles/{digest}/activate", consumes = "application/json")
    public ResponseEntity<Map<String, Object>> activate(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @PathVariable String digest,
            @RequestBody(required = false) Map<String, Object> body) {
        if (!authorized(authorization)) {
            return ResponseEntity.status(401).body(Map.of("error", "unauthorized"));
        }
        audit("activate", digest, body);
        try {
            return respond(digest, service.activate(new Digest(digest), "release-operator"));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(404).body(Map.of("error", e.getMessage()));
        }
    }

    /** 回滚：pointer 指回 toDigest（历史行零改写；INV-AM5-5） */
    @PostMapping(path = "/api/config-bundles/rollback", consumes = "application/json")
    public ResponseEntity<Map<String, Object>> rollback(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestBody(required = false) Map<String, Object> body) {
        if (!authorized(authorization)) {
            return ResponseEntity.status(401).body(Map.of("error", "unauthorized"));
        }
        Object toDigest = body == null ? null : body.get("toDigest");
        audit("rollback", toDigest == null ? null : String.valueOf(toDigest), body);
        if (toDigest == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "toDigest 必填"));
        }
        String digestHex = String.valueOf(toDigest);
        try {
            return respond(digestHex, service.rollback(new Digest(digestHex), "release-operator"));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(404).body(Map.of("error", e.getMessage()));
        }
    }

    /** 当前激活指针：{bundleDigest, revision, activatedAt}；从未激活 → 404（种子行 NULL 面） */
    @GetMapping(path = "/api/config-bundles/active")
    public ResponseEntity<Map<String, Object>> active(
            @RequestHeader(value = "Authorization", required = false) String authorization) {
        if (!authorized(authorization)) {
            return ResponseEntity.status(401).body(Map.of("error", "unauthorized"));
        }
        return service.activePointer()
                .<ResponseEntity<Map<String, Object>>>map(pointer -> ResponseEntity.ok(Map.of(
                        "bundleDigest", pointer.bundleDigest().hex(),
                        "revision", pointer.revision(),
                        "activatedAt", pointer.activatedAt().toString())))
                .orElseGet(() -> ResponseEntity.status(404).body(Map.of("error", "never_activated")));
    }

    // ------------------------------------------------------------------ 内部

    /**
     * moved=true → 200 新激活；moved=false 且指针已在目标 → 200 replayed（幂等重放）；
     * moved=false 且指针在别处 → 409 并带当前指针（CAS 竞争败者不改任何状态）。
     */
    private ResponseEntity<Map<String, Object>> respond(String requestedDigestHex,
                                                        ConfigBundleService.ActivationResult r) {
        if (!r.moved() && !r.activeDigest().hex().equals(requestedDigestHex)) {
            return ResponseEntity.status(409).body(Map.of(
                    "error", "activation_conflict",
                    "activeDigest", r.activeDigest().hex(),
                    "revision", r.revision()));
        }
        return ResponseEntity.ok(Map.of(
                "bundleDigest", r.activeDigest().hex(),
                "revision", r.revision(),
                "replayed", !r.moved()));
    }

    /** 常量时间比较（Bearer 验签；防时序侧信道，AlertWebhookController 同构） */
    private boolean authorized(String authorizationHeader) {
        if (authorizationHeader == null || !authorizationHeader.startsWith("Bearer ")) {
            return false;
        }
        byte[] provided = authorizationHeader.substring("Bearer ".length())
                .getBytes(StandardCharsets.UTF_8);
        return MessageDigest.isEqual(provided, expectedBearer);
    }

    private static String actorOf(String idempotencyKey) {
        // 审计 actor 面：O-4 用户体系前的机器语义占位（幂等键若带即随审计行留痕）
        return idempotencyKey == null || idempotencyKey.isBlank()
                ? "release-operator" : "release-operator#" + idempotencyKey;
    }

    /** 决策可回溯（方案 §8）：激活/回滚每次裁决的 digest/请求参数留审计日志行 */
    private static void audit(String action, String digestHex, Map<String, Object> body) {
        log.info("config-bundle {}: digest={} body-keys={}", action, digestHex,
                body == null ? "{}" : body.keySet());
    }
}
