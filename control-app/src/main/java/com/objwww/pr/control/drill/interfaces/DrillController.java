package com.objwww.pr.control.drill.interfaces;

import com.objwww.pr.control.drill.application.DrillJobService;
import com.objwww.pr.control.drill.domain.model.DrillLaunchPlan;
import com.objwww.pr.control.infrastructure.auth.AuthenticatedActor;
import org.springframework.context.annotation.Profile;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * DR-02 故障演练 API（/api/drills；方案 §7.2 页面契约的服务端面）：
 * <ul>
 *   <li>GET /api/drills——列表投影（场景/靶场/发起人/状态/时间/outcome）+
 *       summary（active/recoveryFailed 真计数）+ 键集游标（limit 默认 50 上限 200）；</li>
 *   <li>GET /api/drills/templates——真实场景目录（公开面 DTO：id/名称/类型/故障源/
 *       症状/时间参数/参数白名单/可执行性如实标记；GT 面不属于本端点）；</li>
 *   <li>GET /api/drills/{drillId}——详情：八阶段时间线（enteredAt 只取真实事件）+
 *       冻结参数审计 + 关联告警/Run（未回填 = null 如实"尚未关联"）；未知 id 404；</li>
 *   <li>POST /api/drills/preview——服务端预检（真实可查信号 OK/FAIL，查不了的项
 *       如实 UNKNOWN）+ 后端计算 TTL/窗口（前端不各算一套）；</li>
 *   <li>POST /api/drills——body {idempotencyKey, scenarioId, targetEnv,
 *       durationSeconds?, trafficScale?, linkedEvalVersion?}；受理前服务端预检
 *       重执行（FAIL → 409 带检查清单）；新受理 202 {drillId, state=ACCEPTED}；
 *       同键同计划重放 200（replayed=true）；同键异计划 409；环境互斥 409
 *       （带 occupantDrillId）；</li>
 *   <li>POST /api/drills/{drillId}/stop——body {idempotencyKey}；受理 202
 *       （state=CANCELLING/RECOVERING——受理只表示取消中/恢复中，核验完成才
 *       CLOSED）；同 stop 键重放 200；异键重复停止 200 幂等无新副作用；终态/
 *       恢复异常占位 409；未知 id 404。</li>
 * </ul>
 * 验签归 SecurityFilterChain（/api/drills/** = ROLE_OPERATOR，读写在 HTTP 面同权；
 * DB 面 control_app 对 drill_job 状态机推进零开口——权限双因子收口）。
 * actor 唯一来源 = 认证主体（AuthenticatedActor），请求体不自报 operator。
 * 浏览器只走本认证后 /api 面：CHAOS_ADMIN_TOKEN 与管理面直连不进本控制器（§7.3）。
 */
@RestController
@Profile("docker")
@RequestMapping(path = "/api/drills", produces = MediaType.APPLICATION_JSON_VALUE)
public class DrillController {

    private final DrillJobService service;

    public DrillController(DrillJobService service) {
        this.service = service;
    }

    @GetMapping
    public ResponseEntity<?> list(@RequestParam(required = false) String state,
                                  @RequestParam(required = false) String cursor,
                                  @RequestParam(required = false, defaultValue = "50")
                                  int limit) {
        try {
            return ResponseEntity.ok(service.list(state, cursor,
                    Math.clamp(limit, 1, 200)));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @GetMapping("/templates")
    public DrillJobService.TemplateListResponse templates() {
        return service.templates();
    }

    @GetMapping("/{drillId}")
    public ResponseEntity<?> detail(@PathVariable String drillId) {
        UUID id = parseId(drillId);
        if (id == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "drillId 非法"));
        }
        return service.detail(id)
                .<ResponseEntity<?>>map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.status(404)
                        .body(Map.of("error", "演练作业不存在")));
    }

    @PostMapping(path = "/preview", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> preview(@RequestBody Map<String, Object> body) {
        try {
            return ResponseEntity.ok(service.preview(plan(body)));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> create(@RequestBody Map<String, Object> body) {
        DrillJobService.CreateResult result;
        try {
            result = service.create(plan(body), text(body.get("idempotencyKey")),
                    truncate(AuthenticatedActor.name()));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
        Map<String, Object> out = new LinkedHashMap<>();
        return switch (result.status()) {
            case ACCEPTED -> {
                out.put("drillId", result.drillId().toString());
                out.put("state", "ACCEPTED");
                out.put("replayed", false);
                yield ResponseEntity.status(202).body(out);
            }
            case REPLAYED -> {
                out.put("drillId", result.drillId().toString());
                out.put("state", "ACCEPTED");
                out.put("replayed", true);
                yield ResponseEntity.ok(out);
            }
            case CONFLICT_KEY -> {
                out.put("error", "幂等键已被不同计划占用");
                out.put("existingDrillId", result.drillId().toString());
                yield ResponseEntity.status(409).body(out);
            }
            case CONFLICT_ENV -> {
                out.put("error", "同靶场已有活动演练（§7.3 互斥）");
                out.put("occupantDrillId",
                        result.drillId() == null ? null : result.drillId().toString());
                yield ResponseEntity.status(409).body(out);
            }
            case PRECHECK_FAILED -> {
                out.put("error", "服务端预检未通过（旧预览不保证现在仍可启动）");
                out.put("checks", result.precheck().checks());
                yield ResponseEntity.status(409).body(out);
            }
        };
    }

    @PostMapping(path = "/{drillId}/stop", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> stop(@PathVariable String drillId,
                                  @RequestBody(required = false)
                                  Map<String, Object> body) {
        UUID id = parseId(drillId);
        if (id == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "drillId 非法"));
        }
        Map<String, Object> safeBody = body == null ? Map.of() : body;
        DrillJobService.StopResult result;
        try {
            var maybe = service.stop(id, text(safeBody.get("idempotencyKey")),
                    truncate(AuthenticatedActor.name()));
            if (maybe.isEmpty()) {
                return ResponseEntity.status(404).body(Map.of("error", "演练作业不存在"));
            }
            result = maybe.get();
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("drillId", id.toString());
        return switch (result.status()) {
            case ACCEPTED_RECOVERING -> {
                // 受理只表示"恢复中"——核验完成才 CLOSED（§7.4，DU12）
                out.put("state", "RECOVERING");
                out.put("replayed", false);
                yield ResponseEntity.status(202).body(out);
            }
            case ACCEPTED_CANCELLING -> {
                out.put("state", "CANCELLING");
                out.put("replayed", false);
                yield ResponseEntity.status(202).body(out);
            }
            case REPLAYED -> {
                // 重放如实返回停止路径语义：注入前 = CANCELLING，注入可能发生起 = RECOVERING
                out.put("state", switch (result.state()) {
                    case QUEUED, PRECHECK -> "CANCELLING";
                    default -> "RECOVERING";
                });
                out.put("replayed", true);
                yield ResponseEntity.ok(out);
            }
            case ALREADY_REQUESTED -> {
                // 异键重复停止：幂等无新副作用（DU14），如实返回当前相位
                out.put("state", result.state().name());
                out.put("alreadyRequested", true);
                yield ResponseEntity.ok(out);
            }
            case CONFLICT_TERMINAL -> {
                out.put("error", "作业已终态（" + result.state() + "），停止非法");
                yield ResponseEntity.status(409).body(out);
            }
            case CONFLICT_RECOVERY_FAILED -> {
                out.put("error", "作业处于恢复异常占位（RECOVERY_FAILED）：需处理恢复而非停止");
                yield ResponseEntity.status(409).body(out);
            }
            case CONFLICT_KEY -> {
                out.put("error", "stop 幂等键已被其他作业占用");
                yield ResponseEntity.status(409).body(out);
            }
        };
    }

    // ------------------------------------------------------------------ 内部

    private static DrillLaunchPlan plan(Map<String, Object> body) {
        return new DrillLaunchPlan(
                text(body.get("scenarioId")), text(body.get("targetEnv")),
                intValue(body.get("durationSeconds")), text(body.get("trafficScale")),
                text(body.get("linkedEvalVersion")));
    }

    private static String text(Object raw) {
        return raw instanceof String s && !s.isBlank() ? s : null;
    }

    private static Integer intValue(Object raw) {
        if (raw == null) {
            return null;
        }
        if (raw instanceof Number n) {
            return n.intValue();
        }
        throw new IllegalArgumentException("数值字段类型非法: " + raw);
    }

    /** actor 截断面沿 EvalCommandController 同式（审计列宽防御） */
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
