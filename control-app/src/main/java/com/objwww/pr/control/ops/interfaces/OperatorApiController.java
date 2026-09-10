package com.objwww.pr.control.ops.interfaces;

import com.objwww.pr.control.infrastructure.auth.AuthenticatedActor;
import com.objwww.pr.control.ops.application.CaseNotFoundException;
import com.objwww.pr.control.ops.application.CaseRevisionConflictException;
import com.objwww.pr.control.ops.application.OperatorCaseService;
import com.objwww.pr.control.ops.application.OperatorQueryService;
import com.objwww.pr.control.ops.domain.statemachine.IllegalCaseActionException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

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
 * <p>EX-C3a：验签与身份归 SecurityFilterChain/AuthenticatedActor——401 由安全链入口点
 * 承担，O-4 过渡面（静态 bearer 手抄验签 + X-Operator-Id 自报）摘除；actor=认证主体
 * （浏览器用户名或 machine: 线主体），审计列宽防御（64 截断）沿旧 actor()。
 */
@RestController
@Profile("docker")
public class OperatorApiController {

    private static final Logger log = LoggerFactory.getLogger(OperatorApiController.class);

    private final OperatorQueryService query;
    private final OperatorCaseService cases;

    public OperatorApiController(OperatorQueryService query,
                                 OperatorCaseService cases) {
        this.query = query;
        this.cases = cases;
    }

    // ------------------------------------------------------------------ 查询面

    @GetMapping(path = "/api/cases/summary")
    public ResponseEntity<Map<String, Object>> summary() {
        return ResponseEntity.ok(query.summary(actor()));
    }

    @GetMapping(path = "/api/cases")
    public ResponseEntity<java.util.List<Map<String, Object>>> list(
            @RequestParam(required = false) String bucket,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String priority,
            @RequestParam(required = false) String reasonCode) {
        return ResponseEntity.ok(query.list(bucket, actor(), status, priority, reasonCode));
    }

    @GetMapping(path = "/api/cases/{id}")
    public ResponseEntity<Map<String, Object>> detail(
            @PathVariable UUID id) {
        return query.detail(id)
                .<ResponseEntity<Map<String, Object>>>map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.status(404).body(Map.of("error", "not_found")));
    }

    // ------------------------------------------------------------------ 命令面

    @PostMapping(path = "/api/cases/{id}/claim", consumes = "application/json")
    public ResponseEntity<Map<String, Object>> claim(
            @PathVariable UUID id,
            @RequestBody Map<String, Object> body) {
        return command("claim", id, body, (revision, actor, idemKey) ->
                cases.claim(id, revision, actor, idemKey));
    }

    @PostMapping(path = "/api/cases/{id}/ack", consumes = "application/json")
    public ResponseEntity<Map<String, Object>> ack(
            @PathVariable UUID id,
            @RequestBody Map<String, Object> body) {
        return command("ack", id, body, (revision, actor, idemKey) ->
                cases.ack(id, revision, actor, idemKey));
    }

    /**
     * resolve 必填结构化 reason：落码方案契约形状 {reason:{code,note}}；
     * CasesView.vue 实际发送 {reason:<code>, remark:<note>}（联调行为不变面，双形状同收）。
     */
    @PostMapping(path = "/api/cases/{id}/resolve", consumes = "application/json")
    public ResponseEntity<Map<String, Object>> resolve(
            @PathVariable UUID id,
            @RequestBody Map<String, Object> body) {
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
        return command("resolve", id, body, (revision, actor, idemKey) ->
                cases.resolve(id, revision, code, note, actor, idemKey));
    }

    /** 转派：契约 toOwner；CasesView.vue 实际发送 assignee（双形状同收） */
    @PostMapping(path = "/api/cases/{id}/assign", consumes = "application/json")
    public ResponseEntity<Map<String, Object>> assign(
            @PathVariable UUID id,
            @RequestBody Map<String, Object> body) {
        String toOwner = text(body.get("toOwner")) != null
                ? text(body.get("toOwner")) : text(body.get("assignee"));
        if (toOwner == null || toOwner.isBlank()) {
            return badRequest("assign 必填 toOwner");
        }
        return command("assign", id, body, (revision, actor, idemKey) ->
                cases.assign(id, revision, toOwner, actor, idemKey));
    }

    // ------------------------------------------------------------------ 内部

    private interface CommandCall {
        com.objwww.pr.control.ops.domain.model.OperatorCase apply(long revision, String actor,
                                                                  String idempotencyKey);
    }

    private ResponseEntity<Map<String, Object>> command(String action, UUID id,
                                                        Map<String, Object> body,
                                                        CommandCall call) {
        if (!(body.get("expectedRevision") instanceof Number expected)) {
            return badRequest("expectedRevision 必填（数字）");
        }
        String idempotencyKey = text(body.get("idempotencyKey"));
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            return badRequest("idempotencyKey 必填（命令幂等重放锚）");
        }
        String actor = actor();
        audit(action, id, idempotencyKey, actor);
        try {
            com.objwww.pr.control.ops.domain.model.OperatorCase updated =
                    call.apply(expected.longValue(), actor, idempotencyKey);
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

    private static ResponseEntity<Map<String, Object>> badRequest(String message) {
        return ResponseEntity.badRequest().body(Map.of("ok", false, "error", message));
    }

    /** actor=认证主体；审计列宽防御（64 截断）沿旧 actor() */
    private static String actor() {
        String name = AuthenticatedActor.name().trim();
        return name.length() > 64 ? name.substring(0, 64) : name;
    }

    private static String text(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    /** 命令审计留痕（决策可回溯；字段仅标识符） */
    private static void audit(String action, UUID caseId, String idempotencyKey, String actor) {
        log.info("operator-case {}: case={} idem-key={} operator={}",
                action, caseId, idempotencyKey, actor);
    }
}
