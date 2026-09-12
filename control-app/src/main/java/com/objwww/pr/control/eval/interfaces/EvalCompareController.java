package com.objwww.pr.control.eval.interfaces;

import com.objwww.pr.control.eval.application.EvalCompareService;
import com.objwww.pr.control.infrastructure.auth.AuthenticatedActor;
import org.springframework.context.annotation.Profile;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.UUID;

/**
 * EV-07 配对工作台 API（/api/eval/**；方案 §3.5/§5.3 对比行）：
 * <ul>
 *   <li>GET /api/eval/compare?baseline={runId}&candidate={runId}[&group=&cursor=&limit=]
 *       ——全读面实时对比投影：可比性维度清单（comparable=false 不出配对结论）/
 *       配对计数三件套/判定变化矩阵/簇统计/故障源分桶/PairedTrialStats 溯源块/
 *       对比质量门（eval-compare-gate-v1）/同对最新落档引用/asOf。逐例列表有界
 *       （group 过滤 + (scenarioId, roundNo) 键集游标，limit 默认 200 上限 500），
 *       每例携带 R12 差值块 delta（Δscore/Δcost/Δlatency + 各指标方向与改善/持平/
 *       退化分组；Δcost 仅双方链路完全 priced）；
 *       unpaired 列表上限 500（计数仍按全集）；</li>
 *   <li>POST /api/eval/comparisons——body {baselineRunId, candidateRunId}；同一计算
 *       的 insert-only 落档（V85 eval_comparison，重落档换新 id 不覆盖），201 携带
 *       完整对比响应 + gateRecord.recordId。落档后候选 run 的 qualityVerdict 分面
 *       接真值（PASS→OK / FAIL→VIOLATED / 其余→UNKNOWN）。</li>
 * </ul>
 * 验签归 SecurityFilterChain（/api/eval/** = ROLE_OPERATOR，读写在 HTTP 面同权；
 * DB 面 control_app 对 eval_comparison 只增读）。actor 唯一来源 = 认证主体
 * （AuthenticatedActor），请求体不自报。
 */
@RestController
@Profile("docker")
@RequestMapping(path = "/api/eval", produces = MediaType.APPLICATION_JSON_VALUE)
public class EvalCompareController {

    private final EvalCompareService service;

    public EvalCompareController(EvalCompareService service) {
        this.service = service;
    }

    @GetMapping("/compare")
    public ResponseEntity<?> compare(@RequestParam(required = false) String baseline,
                                     @RequestParam(required = false) String candidate,
                                     @RequestParam(required = false) String group,
                                     @RequestParam(required = false) String cursor,
                                     @RequestParam(required = false, defaultValue = "200")
                                     int limit) {
        UUID baselineId = baseline == null ? null : parseId(baseline);
        UUID candidateId = candidate == null ? null : parseId(candidate);
        if (baselineId == null || candidateId == null) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "baseline/candidate 必填且为 UUID"));
        }
        try {
            return service.compare(baselineId, candidateId, group, cursor,
                            Math.clamp(limit, 1, 500))
                    .<ResponseEntity<?>>map(ResponseEntity::ok)
                    .orElseGet(() -> ResponseEntity.status(404)
                            .body(Map.of("error", "eval run 不存在")));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @PostMapping(path = "/comparisons", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> record(@RequestBody(required = false) Map<String, Object> body) {
        Map<String, Object> safeBody = body == null ? Map.of() : body;
        UUID baselineId = parseId(text(safeBody.get("baselineRunId")));
        UUID candidateId = parseId(text(safeBody.get("candidateRunId")));
        if (baselineId == null || candidateId == null) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "baselineRunId/candidateRunId 必填且为 UUID"));
        }
        try {
            return service.record(baselineId, candidateId,
                            truncate(AuthenticatedActor.name()))
                    .<ResponseEntity<?>>map(r -> ResponseEntity.status(201).body(r))
                    .orElseGet(() -> ResponseEntity.status(404)
                            .body(Map.of("error", "eval run 不存在")));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    private static String text(Object raw) {
        return raw instanceof String s && !s.isBlank() ? s : null;
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
