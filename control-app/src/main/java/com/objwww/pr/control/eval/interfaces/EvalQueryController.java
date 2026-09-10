package com.objwww.pr.control.eval.interfaces;

import com.objwww.pr.control.eval.application.EvalQueryService;
import org.springframework.context.annotation.Profile;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.UUID;

/**
 * UI-5/EV-03 评测只读查询投影 API（/api/eval/**；全 GET 零写面，ROLE_OPERATOR 归
 * SecurityFilterChain 矩阵，与 /api/v1/** 同式）。DTO 为 record（EvalQueryService 内）；
 * 400/404 应答沿用 {"error": ...} 惯例。
 *
 * <ul>
 *   <li>GET /api/eval/runs——eval_run 列表（state 过滤 + 键集游标，limit 默认 50
 *       上限 200，排序 started_at DESC）；EV-03 起携带 displayName/mode/caseCount/
 *       totalScenarios/quality（比率三件套）/facets（状态分面）/asOf；</li>
 *   <li>GET /api/eval/runs/{runId}——单对象 + caseCount + 同上分面与 asOf；
 *       未知 id → 404；</li>
 *   <li>GET /api/eval/runs/{runId}/cases——逐案例评分（verdict 过滤 + 键集游标，
 *       排序 scenario_id/round_no ASC）；EV-03 起携带 caseExecutionId（= 案例行
 *       稳定 uuid）与 rcaRunId/scoredReportId 关联链；未知 run → 404；</li>
 *   <li>GET /api/eval/datasets——数据集版本清单（case_version 计数 + 族聚合；
 *       RLS 面下只含 control_app 可见的非 HOLDOUT 行）。</li>
 *   <li>GET /api/eval/runs/{runId}/cases/{caseExecutionId}——EV-05 案例详情
 *       （双键定位；场景身份/关联链/支持反对证据引用）；未知 → 404；</li>
 *   <li>GET /api/eval/runs/{runId}/evidence-summary——EV-05 Run 证据汇总
 *       （按案例分组的引用计数/类型分桶）；未知 run → 404；</li>
 *   <li>GET /api/eval/log-compare——EV-05 受限日志比较（baselineRunId/
 *       candidateRunId/scenarioId 三参数限定，冻结 logs.query 证据签名 diff）；
 *       参数非法 400，任一 run 未知 404。</li>
 * </ul>
 * 六维分析/评分器/发布门无持久化数据，不开端点（前端空态明示）。
 */
@RestController
@Profile("docker")
@RequestMapping(path = "/api/eval", produces = MediaType.APPLICATION_JSON_VALUE)
public class EvalQueryController {

    private final EvalQueryService query;

    public EvalQueryController(EvalQueryService query) {
        this.query = query;
    }

    @GetMapping("/runs")
    public ResponseEntity<?> runs(@RequestParam(required = false) String state,
                                  @RequestParam(required = false) String cursor,
                                  @RequestParam(required = false, defaultValue = "50") int limit) {
        try {
            return ResponseEntity.ok(query.listRuns(state, cursor, Math.clamp(limit, 1, 200)));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @GetMapping("/runs/{runId}")
    public ResponseEntity<?> run(@PathVariable String runId) {
        UUID id = parseId(runId);
        if (id == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "runId 非法"));
        }
        return query.detail(id)
                .<ResponseEntity<?>>map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.status(404)
                        .body(Map.of("error", "eval run 不存在")));
    }

    @GetMapping("/runs/{runId}/cases")
    public ResponseEntity<?> cases(@PathVariable String runId,
                                   @RequestParam(required = false) String verdict,
                                   @RequestParam(required = false) String cursor,
                                   @RequestParam(required = false, defaultValue = "50") int limit) {
        UUID id = parseId(runId);
        if (id == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "runId 非法"));
        }
        try {
            return query.listCases(id, verdict, cursor, Math.clamp(limit, 1, 200))
                    .<ResponseEntity<?>>map(ResponseEntity::ok)
                    .orElseGet(() -> ResponseEntity.status(404)
                            .body(Map.of("error", "eval run 不存在")));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @GetMapping("/datasets")
    public EvalQueryService.DatasetListResponse datasets() {
        return query.datasets();
    }

    /** EV-05 案例详情（双键定位；未知 run 或 case → 404） */
    @GetMapping("/runs/{runId}/cases/{caseExecutionId}")
    public ResponseEntity<?> caseDetail(@PathVariable String runId,
                                        @PathVariable String caseExecutionId) {
        UUID id = parseId(runId);
        UUID caseId = parseId(caseExecutionId);
        if (id == null || caseId == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "runId/caseExecutionId 非法"));
        }
        return query.caseDetail(id, caseId)
                .<ResponseEntity<?>>map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.status(404)
                        .body(Map.of("error", "eval case 不存在")));
    }

    /** EV-05 Run 证据汇总（按案例分组）；未知 run → 404 */
    @GetMapping("/runs/{runId}/evidence-summary")
    public ResponseEntity<?> evidenceSummary(@PathVariable String runId) {
        UUID id = parseId(runId);
        if (id == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "runId 非法"));
        }
        return query.evidenceSummary(id)
                .<ResponseEntity<?>>map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.status(404)
                        .body(Map.of("error", "eval run 不存在")));
    }

    /** EV-05 受限日志比较（冻结证据投影；只收三参数，无自由查询面） */
    @GetMapping("/log-compare")
    public ResponseEntity<?> logCompare(@RequestParam(required = false) String baselineRunId,
                                        @RequestParam(required = false) String candidateRunId,
                                        @RequestParam(required = false) String scenarioId) {
        UUID baseline = baselineRunId == null ? null : parseId(baselineRunId);
        UUID candidate = candidateRunId == null ? null : parseId(candidateRunId);
        if (baseline == null || candidate == null) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "baselineRunId/candidateRunId 必填且为 UUID"));
        }
        try {
            return query.logCompare(baseline, candidate, scenarioId)
                    .<ResponseEntity<?>>map(ResponseEntity::ok)
                    .orElseGet(() -> ResponseEntity.status(404)
                            .body(Map.of("error", "eval run 不存在")));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    private static UUID parseId(String raw) {
        try {
            return UUID.fromString(raw);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
