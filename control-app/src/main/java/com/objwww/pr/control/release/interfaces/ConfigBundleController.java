package com.objwww.pr.control.release.interfaces;

import com.objwww.pr.control.infrastructure.auth.AuthenticatedActor;
import com.objwww.pr.control.release.application.ConfigBundleService;
import com.objwww.pr.control.release.domain.repository.ConfigBundleRepository.ActivePointer;
import com.objwww.pr.shared.Digest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * ConfigBundle 发布/回滚/查询 API（M5-09 §M5-09③ 契约；RBAC=release 角色）。
 *
 * <p>EX-C3a：验签归 SecurityFilterChain（ROLE_RELEASE 机器线或会话用户）；401 由
 * 安全链入口点承担，手抄 bearer 验签摘除。actor=认证主体（原 "release-operator"
 * 占位退役），activate/rollback 的 change_event actor 面随真实身份落库。
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

    public ConfigBundleController(ConfigBundleService service) {
        this.service = service;
    }

    /**
     * 发布：契约 body = {content{...}, expectedRevision?}（落码方案 §M5-09③）；
     * content 必带（缺/非对象 → 400）。expectedRevision 与 activate 的
     * canaryPercent/whitelist 同为本期只收不用（M5-10 消费面，C 决策记账）。
     */
    @PostMapping(path = "/api/config-bundles", consumes = "application/json")
    public ResponseEntity<Map<String, Object>> publish(
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestBody Map<String, Object> body) {
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

    /**
     * 原子激活（EN-02 资格化 CAS）：body 必带 expectedActiveRevision（客户端预期的
     * 当前激活 revision，0 = 从未激活约定；服务端不替用户推算预期——P06）。
     * 409 = 预期陈旧（竞争败者，零状态改写）；422 = 资格门拒绝（QUALIFICATION_* 原因码）；
     * 404 = 未知 digest。
     */
    @PostMapping(path = "/api/config-bundles/{digest}/activate", consumes = "application/json")
    public ResponseEntity<Map<String, Object>> activate(
            @PathVariable String digest,
            @RequestBody(required = false) Map<String, Object> body) {
        audit("activate", digest, body);
        Long expected = expectedRevisionOf(body);
        if (expected == null) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "expectedActiveRevision 必填（0 = 从未激活）"));
        }
        try {
            return respond(digest,
                    service.activate(new Digest(digest), expected, AuthenticatedActor.name()));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(404).body(Map.of("error", e.getMessage()));
        } catch (IllegalStateException e) {
            return ResponseEntity.status(422).body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * 回滚（EN-02）：pointer 指回 toDigest（历史行零改写；INV-AM5-5）。回滚目标
     * <b>同样过资格门</b>（P11：不能因"回滚"绕过有效性）；expectedActiveRevision 必带。
     */
    @PostMapping(path = "/api/config-bundles/rollback", consumes = "application/json")
    public ResponseEntity<Map<String, Object>> rollback(
            @RequestBody(required = false) Map<String, Object> body) {
        Object toDigest = body == null ? null : body.get("toDigest");
        audit("rollback", toDigest == null ? null : String.valueOf(toDigest), body);
        if (toDigest == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "toDigest 必填"));
        }
        Long expected = expectedRevisionOf(body);
        if (expected == null) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "expectedActiveRevision 必填（0 = 从未激活）"));
        }
        String digestHex = String.valueOf(toDigest);
        try {
            return respond(digestHex,
                    service.rollback(new Digest(digestHex), expected, AuthenticatedActor.name()));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(404).body(Map.of("error", e.getMessage()));
        } catch (IllegalStateException e) {
            return ResponseEntity.status(422).body(Map.of("error", e.getMessage()));
        }
    }

    /** 当前激活指针：{bundleDigest, revision, activatedAt}；从未激活 → 404（种子行 NULL 面） */
    @GetMapping(path = "/api/config-bundles/active")
    public ResponseEntity<Map<String, Object>> active() {
        return service.activePointer()
                .<ResponseEntity<Map<String, Object>>>map(pointer -> ResponseEntity.ok(Map.of(
                        "bundleDigest", pointer.bundleDigest().hex(),
                        "revision", pointer.revision(),
                        "activatedAt", pointer.activatedAt().toString())))
                .orElseGet(() -> ResponseEntity.status(404).body(Map.of("error", "never_activated")));
    }

    // ------------------------------------------------------------------ 内部

    /** EN-02：expectedActiveRevision 解析（数字面；缺失/非法 → null = 400） */
    private static Long expectedRevisionOf(Map<String, Object> body) {
        if (body == null) {
            return null;
        }
        Object raw = body.get("expectedActiveRevision");
        if (raw instanceof Number n) {
            return n.longValue();
        }
        if (raw instanceof String s && !s.isBlank()) {
            try {
                return Long.parseLong(s.trim());
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

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

    /** 审计 actor 面：认证主体 + 幂等键注记（幂等键若带即随审计行留痕） */
    private static String actorOf(String idempotencyKey) {
        String actor = AuthenticatedActor.name();
        return idempotencyKey == null || idempotencyKey.isBlank()
                ? actor : actor + "#" + idempotencyKey;
    }

    /** 决策可回溯（方案 §8）：激活/回滚每次裁决的 digest/请求参数留审计日志行 */
    private static void audit(String action, String digestHex, Map<String, Object> body) {
        log.info("config-bundle {}: digest={} body-keys={}", action, digestHex,
                body == null ? "{}" : body.keySet());
    }
}
