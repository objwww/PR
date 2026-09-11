package com.objwww.pr.control.eval.interfaces;

import com.objwww.pr.control.eval.application.EvalCommandService;
import com.objwww.pr.control.eval.domain.model.EvalLaunchPlan;
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
import java.util.UUID;

/**
 * EV-04 评测发起/取消命令 API（/api/eval 写面；方案 §5.3 第二三行）：
 * <ul>
 *   <li>POST /api/eval/runs——body {idempotencyKey, displayName, mode(E/B/L),
 *       datasetVersion, model?, promptVersion?, budgetMaxTokens?, maxConcurrency?,
 *       deadlineSeconds?, roundsPerScenario?}；新受理 202 {runId, commandId,
 *       state=ACCEPTED}；同键同计划重放 200（replayed=true，同 runId）；同键异计划
 *       409（existingRunId）。run 行由 worker 领取命令后落库（V81 授权纪律：
 *       control_app 对 eval_run 只读）——202 到 run 可查之间存在领取窗口，
 *       读面在此之前如实 404；</li>
 *   <li>POST /api/eval/runs/{runId}/cancel——body {idempotencyKey, reason?}；
 *       受理 202 {state=CANCELLING}（只表示"取消中"，推进归 worker 案例边界检查点，
 *       L 模式强制恢复核验后才到终态）；同键重放 200；run 不存在 404；终态 run
 *       409；同键指向其他 run 409。</li>
 * </ul>
 * 验签归 SecurityFilterChain（/api/eval/** = ROLE_OPERATOR，读写在 HTTP 面同权；
 * DB 面 control_app 只增命令、eval_run 零写——EU10 服务端拒绝 = 双因子）。
 * actor 唯一来源 = 认证主体（AuthenticatedActor），请求体不自报。
 */
@RestController
@Profile("docker")
@RequestMapping(path = "/api/eval", produces = MediaType.APPLICATION_JSON_VALUE)
public class EvalCommandController {

    private final EvalCommandService service;

    public EvalCommandController(EvalCommandService service) {
        this.service = service;
    }

    @PostMapping(path = "/runs", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> launch(@RequestBody Map<String, Object> body) {
        EvalLaunchPlan plan;
        String idempotencyKey;
        try {
            idempotencyKey = text(body.get("idempotencyKey"));
            plan = new EvalLaunchPlan(
                    text(body.get("displayName")), text(body.get("mode")),
                    text(body.get("datasetVersion")), text(body.get("model")),
                    text(body.get("promptVersion")),
                    longValue(body.get("budgetMaxTokens")),
                    intValue(body.get("maxConcurrency")),
                    longValue(body.get("deadlineSeconds")),
                    intValue(body.get("roundsPerScenario")));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
        EvalCommandService.LaunchResult result;
        try {
            result = service.launch(plan, idempotencyKey, truncate(AuthenticatedActor.name()));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("runId", result.runId().toString());
        out.put("commandId", result.commandId().toString());
        return switch (result.status()) {
            case ACCEPTED -> {
                out.put("state", "ACCEPTED");
                out.put("replayed", false);
                yield ResponseEntity.status(202).body(out);
            }
            case REPLAYED -> {
                out.put("state", "ACCEPTED");
                out.put("replayed", true);
                yield ResponseEntity.ok(out);
            }
            case CONFLICT -> {
                out.clear();
                out.put("error", "幂等键已被不同计划占用");
                out.put("existingRunId", result.runId().toString());
                yield ResponseEntity.status(409).body(out);
            }
        };
    }

    @PostMapping(path = "/runs/{runId}/cancel", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> cancel(@PathVariable String runId,
                                    @RequestBody(required = false) Map<String, Object> body) {
        UUID id = parseId(runId);
        if (id == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "runId 非法"));
        }
        Map<String, Object> safeBody = body == null ? Map.of() : body;
        String idempotencyKey = text(safeBody.get("idempotencyKey"));
        String reason = text(safeBody.get("reason"));
        EvalCommandService.CancelResult result;
        try {
            var maybe = service.cancel(id, idempotencyKey, reason,
                    truncate(AuthenticatedActor.name()));
            if (maybe.isEmpty()) {
                return ResponseEntity.status(404).body(Map.of("error", "eval run 不存在"));
            }
            result = maybe.get();
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("runId", id.toString());
        return switch (result.status()) {
            case ACCEPTED -> {
                out.put("commandId", result.commandId().toString());
                out.put("state", "CANCELLING");
                out.put("replayed", false);
                yield ResponseEntity.status(202).body(out);
            }
            case REPLAYED -> {
                out.put("commandId", result.commandId().toString());
                out.put("state", "CANCELLING");
                out.put("replayed", true);
                yield ResponseEntity.ok(out);
            }
            case CONFLICT_TERMINAL -> {
                out.put("error", "run 已终态，取消非法迁移");
                yield ResponseEntity.status(409).body(out);
            }
            case CONFLICT_KEY -> {
                out.put("error", "幂等键已被其他 run 的取消占用");
                yield ResponseEntity.status(409).body(out);
            }
        };
    }

    // ------------------------------------------------------------------ 内部

    private static String text(Object raw) {
        return raw instanceof String s && !s.isBlank() ? s : null;
    }

    private static Long longValue(Object raw) {
        if (raw == null) {
            return null;
        }
        if (raw instanceof Number n) {
            return n.longValue();
        }
        throw new IllegalArgumentException("数值字段类型非法: " + raw);
    }

    private static Integer intValue(Object raw) {
        Long value = longValue(raw);
        return value == null ? null : value.intValue();
    }

    /** actor 截断面沿 RunCommandController 同式（审计列宽防御） */
    private static String truncate(String actor) {
        return actor.length() > 64 ? actor.substring(0, 64) : actor;
    }

    private static UUID parseId(String raw) {
        try {
            return UUID.fromString(raw);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
