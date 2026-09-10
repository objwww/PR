package com.objwww.pr.control.alert.interfaces;

import com.objwww.pr.control.alert.application.CommandService;
import com.objwww.pr.control.alert.domain.model.OperatorCommand;
import com.objwww.pr.control.alert.domain.repository.RcaRunRepository;
import com.objwww.pr.control.infrastructure.auth.AuthenticatedActor;
import org.springframework.context.annotation.Profile;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Run 干预命令 API（M5-14 §M5-14③；RunDetailView P3-B 头部按钮组契约）：
 * POST /api/rca-runs/{runId}/commands body {type, idempotencyKey, expectedRevision,
 * payload} → {commandId, state}。
 *
 * <p>状态 → HTTP 面：APPLIED=200；REJECTED_STALE=409（旧 revision，零副作用）；
 * REJECTED_FORBIDDEN=403（终态 Run 越权）；幂等重放返回原 commandId/state（含
 * 原拒绝码）。批量评测命令明确不在本期（开放项 O-2，落码方案 §M5-14③ annot 原文）。
 * EX-C3a：验签归 SecurityFilterChain（ROLE_OPERATOR）；actor=认证主体，
 * X-Operator-Id 自报面摘除。
 */
@RestController
@Profile("docker")
public class RunCommandController {

    private final CommandService service;
    private final RcaRunRepository runs;

    public RunCommandController(CommandService service, RcaRunRepository runs) {
        this.service = service;
        this.runs = runs;
    }

    @PostMapping(path = "/api/rca-runs/{runId}/commands",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Map<String, Object>> submit(
            @PathVariable String runId,
            @RequestBody Map<String, Object> body) {
        UUID id = parseRunId(runId);
        if (id == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "runId 非法"));
        }
        if (!runs.findById(id).isPresent()) {
            return ResponseEntity.status(404).body(Map.of("error", "run 不存在"));
        }
        OperatorCommand.Type type = parseType(body.get("type"));
        String idempotencyKey = body.get("idempotencyKey") instanceof String key && !key.isBlank()
                ? key : null;
        Long expectedRevision = body.get("expectedRevision") instanceof Number n
                ? n.longValue() : null;
        if (type == null || idempotencyKey == null || expectedRevision == null) {
            return ResponseEntity.badRequest().body(Map.of(
                    "error", "type(CANCEL|HINT|FEEDBACK)/idempotencyKey/expectedRevision 必填"));
        }
        Map<String, Object> payload = body.get("payload") instanceof Map<?, ?> p
                ? copyOf(p) : Map.of();

        CommandService.Result result = service.submit(id, type, idempotencyKey,
                expectedRevision, payload, truncate(AuthenticatedActor.name()));
        return respond(result);
    }

    // ------------------------------------------------------------------ 内部

    private static ResponseEntity<Map<String, Object>> respond(CommandService.Result result) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("commandId", result.commandId().toString());
        out.put("state", result.state().name());
        out.put("replayed", result.replayed());
        return switch (result.state()) {
            case APPLIED -> ResponseEntity.ok(out);
            case REJECTED_STALE -> ResponseEntity.status(409).body(out);
            case REJECTED_FORBIDDEN -> ResponseEntity.status(403).body(out);
            case PERSISTED -> ResponseEntity.accepted().body(out);
        };
    }

    private static OperatorCommand.Type parseType(Object raw) {
        if (!(raw instanceof String value)) {
            return null;
        }
        try {
            return OperatorCommand.Type.valueOf(value);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static Map<String, Object> copyOf(Map<?, ?> raw) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (Map.Entry<?, ?> e : raw.entrySet()) {
            out.put(String.valueOf(e.getKey()), e.getValue());
        }
        return out;
    }

    /** actor 截断面沿旧 actor()（审计列宽防御） */
    private static String truncate(String actor) {
        return actor.length() > 64 ? actor.substring(0, 64) : actor;
    }

    private static UUID parseRunId(String runId) {
        try {
            return UUID.fromString(runId);
        } catch (IllegalArgumentException | NullPointerException e) {
            return null;
        }
    }
}
