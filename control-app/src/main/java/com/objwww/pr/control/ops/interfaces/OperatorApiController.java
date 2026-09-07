package com.objwww.pr.control.ops.interfaces;

import com.objwww.pr.control.ops.application.CaseNotFoundException;
import com.objwww.pr.control.ops.application.CaseRevisionConflictException;
import com.objwww.pr.control.ops.application.OperatorCaseService;
import com.objwww.pr.control.ops.application.OperatorQueryService;
import com.objwww.pr.control.ops.domain.statemachine.IllegalCaseActionException;
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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Operator API 最小集（M5-12 §M5-12③ 契约；字段级以 mocks/cases.js + CasesView.vue
 * 实际调用为准）：
 * <ul>
 *   <li>GET /api/cases/summary → {updatedAt, tabs{mine,all,unassigned,overdue,notifyUnread}}；</li>
 *   <li>GET /api/cases → 数组（view 直接消费；SLA 风险排序 + bucket/status/priority/reasonCode 过滤）；</li>
 *   <li>GET /api/cases/{id} → 工作区投影（证据只回安全摘要面，不回 canonical payload）；</li>
 *   <li>POST /api/cases/{id}/claim|ack|resolve|assign —— expected_revision + idempotency_key，
 *       旧 revision → 409 携最新投影；非法迁移 → 422；resolve 必填结构化 reason。</li>
 * </ul>
 *
 * <p>RBAC 现状（O-4 开放项）：静态 bearer（{@code app.operator.api.bearer}，operator
 * 角色共享凭证）+ 常量时间比较；操作者身份 = X-Operator-Id 头（缺省 operator，
 * 审计 actor 面）——O-4 用户体系落地后切角色鉴权，本类 401 面是切换点。
 */
@RestController
@Profile("docker")
public class OperatorApiController {

    private static final Logger log = LoggerFactory.getLogger(OperatorApiController.class);
    private static final String DEFAULT_OPERATOR = "operator";

    private final OperatorQueryService query;
    private final OperatorCaseService cases;
    private final byte[] expectedBearer;

    public OperatorApiController(OperatorQueryService query,
                                 OperatorCaseService cases,
                                 @Value("${app.operator.api.bearer}") String bearerToken) {
        this.query = query;
        this.cases = cases;
        this.expectedBearer = (bearerToken == null ? "" : bearerToken)
                .getBytes(StandardCharsets.UTF_8);
    }

    // ------------------------------------------------------------------ 查询面

    @GetMapping(path = "/api/cases/summary")
    public ResponseEntity<Map<String, Object>> summary(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestHeader(value = "X-Operator-Id", required = false) String operatorId) {
        if (!authorized(authorization)) {
            return unauthorized();
        }
        return ResponseEntity.ok(query.summary(actor(operatorId)));
    }

    @GetMapping(path = "/api/cases")
    public ResponseEntity<java.util.List<Map<String, Object>>> list(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestHeader(value = "X-Operator-Id", required = false) String operatorId,
            @RequestParam(required = false) String bucket,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String priority,
            @RequestParam(required = false) String reasonCode) {
        if (!authorized(authorization)) {
            return ResponseEntity.status(401).body(java.util.List.of());
        }
        return ResponseEntity.ok(query.list(bucket, actor(operatorId), status, priority, reasonCode));
    }

    @GetMapping(path = "/api/cases/{id}")
    public ResponseEntity<Map<String, Object>> detail(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @PathVariable UUID id) {
        if (!authorized(authorization)) {
            return unauthorized();
        }
        return query.detail(id)
                .<ResponseEntity<Map<String, Object>>>map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.status(404).body(Map.of("error", "not_found")));
    }

    // ------------------------------------------------------------------ 命令面

    @PostMapping(path = "/api/cases/{id}/claim", consumes = "application/json")
    public ResponseEntity<Map<String, Object>> claim(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestHeader(value = "X-Operator-Id", required = false) String operatorId,
            @PathVariable UUID id,
            @RequestBody Map<String, Object> body) {
        if (!authorized(authorization)) {
            return unauthorized();
        }
        return command("claim", id, body, operatorId, (revision, actor, idemKey) ->
                cases.claim(id, revision, actor, idemKey));
    }

    @PostMapping(path = "/api/cases/{id}/ack", consumes = "application/json")
    public ResponseEntity<Map<String, Object>> ack(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestHeader(value = "X-Operator-Id", required = false) String operatorId,
            @PathVariable UUID id,
            @RequestBody Map<String, Object> body) {
        if (!authorized(authorization)) {
            return unauthorized();
        }
        return command("ack", id, body, operatorId, (revision, actor, idemKey) ->
                cases.ack(id, revision, actor, idemKey));
    }

    /**
     * resolve 必填结构化 reason：落码方案契约形状 {reason:{code,note}}；
     * CasesView.vue 实际发送 {reason:<code>, remark:<note>}（联调行为不变面，双形状同收）。
     */
    @PostMapping(path = "/api/cases/{id}/resolve", consumes = "application/json")
    public ResponseEntity<Map<String, Object>> resolve(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestHeader(value = "X-Operator-Id", required = false) String operatorId,
            @PathVariable UUID id,
            @RequestBody Map<String, Object> body) {
        if (!authorized(authorization)) {
            return unauthorized();
        }
        Object reason = body.get("reason");
        String code;
        String note;
        if (reason instanceof Map<?, ?> structured) {
            code = text(structured.get("code"));
            note = text(structured.get("note"));
        } else if (reason instanceof String flat) {
            code = flat;
            note = text(body.get("remark"));
        } else {
            code = null;
            note = null;
        }
        if (code == null || code.isBlank() || note == null || note.isBlank()) {
            return badRequest("resolve 必填结构化 reason(code,note)");
        }
        return command("resolve", id, body, operatorId, (revision, actor, idemKey) ->
                cases.resolve(id, revision, code, note, actor, idemKey));
    }

    /** 转派：契约 toOwner；CasesView.vue 实际发送 assignee（双形状同收） */
    @PostMapping(path = "/api/cases/{id}/assign", consumes = "application/json")
    public ResponseEntity<Map<String, Object>> assign(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestHeader(value = "X-Operator-Id", required = false) String operatorId,
            @PathVariable UUID id,
            @RequestBody Map<String, Object> body) {
        if (!authorized(authorization)) {
            return unauthorized();
        }
        String toOwner = text(body.get("toOwner")) != null
                ? text(body.get("toOwner")) : text(body.get("assignee"));
        if (toOwner == null || toOwner.isBlank()) {
            return badRequest("assign 必填 toOwner");
        }
        return command("assign", id, body, operatorId, (revision, actor, idemKey) ->
                cases.assign(id, revision, toOwner, actor, idemKey));
    }

    // ------------------------------------------------------------------ 内部

    private interface CommandCall {
        com.objwww.pr.control.ops.domain.model.OperatorCase apply(long revision, String actor,
                                                                  String idempotencyKey);
    }

    private ResponseEntity<Map<String, Object>> command(String action, UUID id,
                                                        Map<String, Object> body,
                                                        String operatorId, CommandCall call) {
        if (!(body.get("expectedRevision") instanceof Number expected)) {
            return badRequest("expectedRevision 必填（数字）");
        }
        String idempotencyKey = text(body.get("idempotencyKey"));
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            return badRequest("idempotencyKey 必填（命令幂等重放锚）");
        }
        audit(action, id, idempotencyKey, operatorId);
        try {
            com.objwww.pr.control.ops.domain.model.OperatorCase updated =
                    call.apply(expected.longValue(), actor(operatorId), idempotencyKey);
            Map<String, Object> ok = new LinkedHashMap<>();
            ok.put("ok", true);
            ok.put("conflict", false);
            ok.put("case", query.projection(updated));
            return ResponseEntity.ok(ok);
        } catch (CaseRevisionConflictException e) {
            Map<String, Object> conflict = new LinkedHashMap<>();
            conflict.put("ok", false);
            conflict.put("conflict", true);
            conflict.put("error", "REVISION_CONFLICT");
            conflict.put("case", query.listItem(id).orElse(null));
            return ResponseEntity.status(409).body(conflict);
        } catch (IllegalCaseActionException e) {
            return ResponseEntity.status(422).body(Map.of(
                    "ok", false, "error", "illegal_transition",
                    "currentStatus", String.valueOf(e.currentStatus()),
                    "action", e.action().name()));
        } catch (CaseNotFoundException e) {
            return ResponseEntity.status(404).body(Map.of("ok", false, "error", "not_found"));
        } catch (IllegalArgumentException e) {
            return badRequest(e.getMessage());
        }
    }

    /** 常量时间比较（Bearer 验签；ConfigBundleController/AlertWebhookController 同构） */
    private boolean authorized(String authorizationHeader) {
        if (authorizationHeader == null || !authorizationHeader.startsWith("Bearer ")) {
            return false;
        }
        byte[] provided = authorizationHeader.substring("Bearer ".length())
                .getBytes(StandardCharsets.UTF_8);
        return MessageDigest.isEqual(provided, expectedBearer);
    }

    private static ResponseEntity<Map<String, Object>> unauthorized() {
        return ResponseEntity.status(401).body(Map.of("error", "unauthorized"));
    }

    private static ResponseEntity<Map<String, Object>> badRequest(String message) {
        return ResponseEntity.badRequest().body(Map.of("ok", false, "error", message));
    }

    /** O-4 过渡身份：X-Operator-Id 头（缺省 operator；审计 actor 面） */
    private static String actor(String operatorId) {
        if (operatorId == null || operatorId.isBlank()) {
            return DEFAULT_OPERATOR;
        }
        return operatorId.trim().length() > 64
                ? operatorId.trim().substring(0, 64) : operatorId.trim();
    }

    private static String text(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    /** 命令审计留痕（决策可回溯；字段仅标识符） */
    private static void audit(String action, UUID caseId, String idempotencyKey, String operatorId) {
        log.info("operator-case {}: case={} idem-key={} operator={}",
                action, caseId, idempotencyKey, actor(operatorId));
    }
}
