package com.objwww.pr.control.eval.interfaces;

import com.objwww.pr.control.eval.application.EvalReviewService;
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

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * EV-08 人工评审 API（/api/eval/reviews/** + run 级任务生成/进度；方案 §3.6/§5.3
 * "队列、领取、CAS提交——独立评审记录与 rubric 版本，不覆盖原机器评分"）：
 * <ul>
 *   <li>POST /api/eval/runs/{runId}/review-assignments——任务生成（ensure 语义
 *       幂等：每案例补足 assignmentsPerCase 份开放任务，重复调用补 0）；
 *       body {caseExecutionIds?, assignmentsPerCase?（默认 1，双人评审 = 2，
 *       上限 4）}；run 未知 404；201 {created, openTotal}；</li>
 *   <li>POST /api/eval/reviews/assignments/{id}/claim——领取（服务器 CAS +
 *       有界租约 30min，超时惰性回收可重领）：领到 200（claimed=true）；同人
 *       持有效租约重领 200（replayed=true，幂等）；他人持有效租约/已提交/
 *       同人对同案例已有另一份进行中任务 409；未知 404；</li>
 *   <li>POST /api/eval/reviews/assignments/{id}/submit——提交评分：body
 *       {rubricVersion（必带且须在注册表）, verdict（CORRECT/PARTIAL/INCORRECT/
 *       UNDECIDABLE/INSUFFICIENT_SOURCE）, score?, labels?, reason（必填）,
 *       evidenceRefs?, expectedRevision（必带 CAS 锚）}；201 {verdictId}；
 *       revision 漂移（租约被回收重领）/状态非法/越权 409；提交后不可改——
 *       更正与重评分 = 新任务 + 新行（insert-only）；</li>
 *   <li>GET /api/eval/reviews/assignments——任务列表（runId 必填；scope=mine（默认）
 *       /all；status 按有效状态过滤——租约超时的 IN_PROGRESS 如实 PENDING；
 *       键集游标，limit 默认 50 上限 200）；run 未知 404；</li>
 *   <li>GET /api/eval/reviews/assignments/{id}——单例工作区：任务 + 盲评案例投影
 *       （HOLDOUT 案例身份 RLS 不可见 → blind=true，GT 字段恒 null，只给症状与
 *       系统输出）+ 本案例结论历史（审计闭环）；未知 404；</li>
 *   <li>GET /api/eval/runs/{runId}/review-progress——评审进度分桶（已评/待评/
 *       进行/分歧计数，原始计数如实零）；run 未知 404；</li>
 *   <li>GET /api/eval/reviews/disagreements——分歧清单（runId 必填；同一案例
 *       ≥2 名评审最新结论不一致）。</li>
 * </ul>
 * 验签归 SecurityFilterChain（/api/eval/** = ROLE_OPERATOR）；actor 唯一来源 =
 * 认证主体（AuthenticatedActor），请求体不自报。
 */
@RestController
@Profile("docker")
@RequestMapping(path = "/api/eval", produces = MediaType.APPLICATION_JSON_VALUE)
public class EvalReviewController {

    private final EvalReviewService service;

    public EvalReviewController(EvalReviewService service) {
        this.service = service;
    }

    // ------------------------------------------------------------------ 任务生成

    @PostMapping(path = "/runs/{runId}/review-assignments",
            consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> generate(@PathVariable String runId,
                                      @RequestBody(required = false) Map<String, Object> body) {
        UUID id = parseId(runId);
        if (id == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "runId 非法"));
        }
        Map<String, Object> safeBody = body == null ? Map.of() : body;
        int perCase = 1;
        try {
            Integer raw = intValue(safeBody.get("assignmentsPerCase"));
            if (raw != null) {
                perCase = raw;
            }
            var result = service.generate(id, uuidList(safeBody.get("caseExecutionIds")),
                    perCase, truncate(AuthenticatedActor.name()));
            return result.<ResponseEntity<?>>map(r -> ResponseEntity.status(201).body(r))
                    .orElseGet(() -> ResponseEntity.status(404)
                            .body(Map.of("error", "eval run 不存在")));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    // ------------------------------------------------------------------ 领取 / 提交

    @PostMapping("/reviews/assignments/{assignmentId}/claim")
    public ResponseEntity<?> claim(@PathVariable String assignmentId) {
        UUID id = parseId(assignmentId);
        if (id == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "assignmentId 非法"));
        }
        var maybe = service.claim(id, truncate(AuthenticatedActor.name()));
        if (maybe.isEmpty()) {
            return ResponseEntity.status(404).body(Map.of("error", "评审任务不存在"));
        }
        EvalReviewService.ClaimResult result = maybe.get();
        Map<String, Object> out = new LinkedHashMap<>();
        return switch (result.status()) {
            case CLAIMED -> {
                out.put("claimed", true);
                out.put("replayed", false);
                out.put("assignment", result.assignment());
                yield ResponseEntity.ok(out);
            }
            case REPLAYED -> {
                out.put("claimed", true);
                out.put("replayed", true);
                out.put("assignment", result.assignment());
                yield ResponseEntity.ok(out);
            }
            case CONFLICT_HELD -> {
                out.put("error", "任务被他人持有（有效租约）");
                out.put("assignment", result.assignment());
                yield ResponseEntity.status(409).body(out);
            }
            case CONFLICT_SUBMITTED -> {
                out.put("error", "任务已提交，不可再领取——重评分请生成新任务");
                yield ResponseEntity.status(409).body(out);
            }
            case CONFLICT_SELF_ACTIVE -> {
                out.put("error", "你已持有本案例另一份进行中任务");
                yield ResponseEntity.status(409).body(out);
            }
        };
    }

    @PostMapping(path = "/reviews/assignments/{assignmentId}/submit",
            consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> submit(@PathVariable String assignmentId,
                                    @RequestBody(required = false) Map<String, Object> body) {
        UUID id = parseId(assignmentId);
        if (id == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "assignmentId 非法"));
        }
        Map<String, Object> safeBody = body == null ? Map.of() : body;
        EvalReviewService.SubmitResult result;
        try {
            var maybe = service.submit(id, truncate(AuthenticatedActor.name()),
                    text(safeBody.get("rubricVersion")), text(safeBody.get("verdict")),
                    intValue(safeBody.get("score")), stringList(safeBody.get("labels")),
                    text(safeBody.get("reason")), stringList(safeBody.get("evidenceRefs")),
                    intValue(safeBody.get("expectedRevision")));
            if (maybe.isEmpty()) {
                return ResponseEntity.status(404).body(Map.of("error", "评审任务不存在"));
            }
            result = maybe.get();
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
        Map<String, Object> out = new LinkedHashMap<>();
        return switch (result.status()) {
            case SUBMITTED -> {
                out.put("verdictId", result.verdictId().toString());
                out.put("revision", result.currentRevision());
                yield ResponseEntity.status(201).body(out);
            }
            case CONFLICT_STATE -> {
                out.put("error", "任务不在进行中（已提交或未领取）——提交后不可改");
                out.put("currentRevision", result.currentRevision());
                yield ResponseEntity.status(409).body(out);
            }
            case CONFLICT_REVISION -> {
                out.put("error", "revision 漂移（租约可能被回收重领）——请重新领取");
                out.put("currentRevision", result.currentRevision());
                yield ResponseEntity.status(409).body(out);
            }
            case CONFLICT_ACTOR -> {
                out.put("error", "非任务持有者，不可提交");
                yield ResponseEntity.status(409).body(out);
            }
            case CONFLICT_DUPLICATE -> {
                out.put("error", "任务已有结论行——提交后不可改，重评分请生成新任务");
                yield ResponseEntity.status(409).body(out);
            }
        };
    }

    // ------------------------------------------------------------------ 读面

    @GetMapping("/reviews/assignments")
    public ResponseEntity<?> assignments(@RequestParam(required = false) String runId,
                                         @RequestParam(required = false) String scope,
                                         @RequestParam(required = false) String status,
                                         @RequestParam(required = false) String cursor,
                                         @RequestParam(required = false, defaultValue = "50")
                                         int limit) {
        UUID id = runId == null ? null : parseId(runId);
        if (id == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "runId 必填且为 UUID"));
        }
        try {
            return service.listAssignments(id, scope, status,
                            truncate(AuthenticatedActor.name()), cursor,
                            Math.clamp(limit, 1, 200))
                    .<ResponseEntity<?>>map(ResponseEntity::ok)
                    .orElseGet(() -> ResponseEntity.status(404)
                            .body(Map.of("error", "eval run 不存在")));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @GetMapping("/reviews/assignments/{assignmentId}")
    public ResponseEntity<?> workspace(@PathVariable String assignmentId) {
        UUID id = parseId(assignmentId);
        if (id == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "assignmentId 非法"));
        }
        return service.workspace(id)
                .<ResponseEntity<?>>map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.status(404)
                        .body(Map.of("error", "评审任务不存在")));
    }

    @GetMapping("/runs/{runId}/review-progress")
    public ResponseEntity<?> progress(@PathVariable String runId) {
        UUID id = parseId(runId);
        if (id == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "runId 非法"));
        }
        return service.progress(id)
                .<ResponseEntity<?>>map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.status(404)
                        .body(Map.of("error", "eval run 不存在")));
    }

    @GetMapping("/reviews/disagreements")
    public ResponseEntity<?> disagreements(@RequestParam(required = false) String runId) {
        UUID id = runId == null ? null : parseId(runId);
        if (id == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "runId 必填且为 UUID"));
        }
        return service.disagreements(id)
                .<ResponseEntity<?>>map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.status(404)
                        .body(Map.of("error", "eval run 不存在")));
    }

    // ------------------------------------------------------------------ 内部

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

    @SuppressWarnings("unchecked")
    private static List<String> stringList(Object raw) {
        if (raw == null) {
            return List.of();
        }
        if (raw instanceof List<?> list) {
            List<String> out = new ArrayList<>(list.size());
            for (Object item : list) {
                if (!(item instanceof String s)) {
                    throw new IllegalArgumentException("字符串数组字段含非字符串元素: " + raw);
                }
                out.add(s);
            }
            return out;
        }
        throw new IllegalArgumentException("期形为字符串数组: " + raw);
    }

    private static List<UUID> uuidList(Object raw) {
        if (raw == null) {
            return null;
        }
        if (raw instanceof List<?> list) {
            List<UUID> out = new ArrayList<>(list.size());
            for (Object item : list) {
                UUID id = item instanceof String s ? parseId(s) : null;
                if (id == null) {
                    throw new IllegalArgumentException("caseExecutionIds 含非法 UUID: " + item);
                }
                out.add(id);
            }
            return out;
        }
        throw new IllegalArgumentException("caseExecutionIds 期形为 UUID 数组");
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
