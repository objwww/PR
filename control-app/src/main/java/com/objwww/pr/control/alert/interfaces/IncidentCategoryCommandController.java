package com.objwww.pr.control.alert.interfaces;

import com.objwww.pr.control.alert.application.CategoryOverrideService;
import com.objwww.pr.control.infrastructure.auth.AuthenticatedActor;
import org.springframework.context.annotation.Profile;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * UX-01 人工分类 override 命令 API（写面独立于只读 IncidentQueryController）：
 * <ul>
 *   <li>POST /api/v1/incidents/{id}/category-override——body
 *       {category, reason, expectedRevision, idempotencyKey}：确立/改判人工分类；</li>
 *   <li>POST /api/v1/incidents/{id}/category-override/revoke——body
 *       {reason, expectedRevision, idempotencyKey}：显式撤销（审计动作 REVOKE，
 *       生效面回落规则分类）。</li>
 * </ul>
 *
 * <p>HTTP 面（RunCommandController 同律）：APPLIED=200；REJECTED_STALE=409；
 * incident 不存在=404；参数/理由缺失=400；幂等重放 200 + replayed=true。
 * 认证/授权归 SecurityFilterChain（/api/v1/** → ROLE_OPERATOR）；actor=认证主体，
 * 不采信 body 自报。浏览器会话走 CSRF token（与 /api/rca-runs 命令面同策）。
 */
@RestController
@Profile("docker")
@RequestMapping(path = "/api/v1/incidents", produces = MediaType.APPLICATION_JSON_VALUE)
public class IncidentCategoryCommandController {

    private final CategoryOverrideService service;

    public IncidentCategoryCommandController(CategoryOverrideService service) {
        this.service = service;
    }

    @PostMapping(path = "/{incidentId}/category-override",
            consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> override(@PathVariable String incidentId,
                                      @RequestBody Map<String, Object> body) {
        UUID id = parseId(incidentId);
        if (id == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "incidentId 非法"));
        }
        try {
            Optional<CategoryOverrideService.Result> result = service.override(id,
                    body.get("category") instanceof String c ? c : null,
                    body.get("reason") instanceof String r ? r : null,
                    body.get("expectedRevision") instanceof Number n ? n.intValue() : null,
                    body.get("idempotencyKey") instanceof String k ? k : null,
                    truncate(AuthenticatedActor.name()));
            return respond(result);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @PostMapping(path = "/{incidentId}/category-override/revoke",
            consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> revoke(@PathVariable String incidentId,
                                    @RequestBody Map<String, Object> body) {
        UUID id = parseId(incidentId);
        if (id == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "incidentId 非法"));
        }
        try {
            Optional<CategoryOverrideService.Result> result = service.revoke(id,
                    body.get("reason") instanceof String r ? r : null,
                    body.get("expectedRevision") instanceof Number n ? n.intValue() : null,
                    body.get("idempotencyKey") instanceof String k ? k : null,
                    truncate(AuthenticatedActor.name()));
            return respond(result);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    // ------------------------------------------------------------------ 内部

    private static ResponseEntity<?> respond(Optional<CategoryOverrideService.Result> result) {
        if (result.isEmpty()) {
            return ResponseEntity.status(404).body(Map.of("error", "incident 不存在"));
        }
        CategoryOverrideService.Result r = result.get();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("state", r.state().name());
        out.put("action", r.action());
        if (r.auditId() != null) {
            out.put("auditId", r.auditId().toString());
        }
        if (r.category() != null) {
            out.put("category", r.category());
            out.put("categorySource", r.categorySource());
        }
        out.put("revision", r.revision());
        out.put("replayed", r.replayed());
        return switch (r.state()) {
            case APPLIED -> ResponseEntity.ok(out);
            case REJECTED_STALE -> ResponseEntity.status(409).body(out);
        };
    }

    /** actor 截断面沿 RunCommandController（审计列宽防御） */
    private static String truncate(String actor) {
        return actor.length() > 64 ? actor.substring(0, 64) : actor;
    }

    private static UUID parseId(String raw) {
        try {
            return UUID.fromString(raw);
        } catch (IllegalArgumentException | NullPointerException e) {
            return null;
        }
    }
}
