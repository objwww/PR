package com.objwww.pr.control.alert.interfaces;

import com.objwww.pr.control.alert.application.ReportQueryService;
import com.objwww.pr.control.alert.domain.repository.RcaRunRepository;
import org.springframework.context.annotation.Profile;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.UUID;

/**
 * 报告 tab 只读 API（GET /api/rca-runs/{runId}/report；全 GET 零写面，
 * ROLE_OPERATOR 归 SecurityFilterChain 的 /api/rca-runs/** 矩阵，本类不加注解）。
 *
 * <p>状态码语义：runId 非法 400 {"error": ...}；run 不存在 404 {"error": "run 不存在"}
 * （EventQueryController 同式）；run 存在但无报告 → <b>200 {"state":"NONE"}</b>——
 * 不是 404，前端据此区分「进行中尚未出报告」与「已结束未产生报告」。
 * 投影三态（NONE/OK/REJECTED）与字段口径见 {@link ReportQueryService} 头注；
 * raw_text 不透出（读端口结构上无此字段）。
 */
@RestController
@Profile("docker")
public class ReportQueryController {

    private final ReportQueryService reports;
    private final RcaRunRepository runs;

    public ReportQueryController(ReportQueryService reports, RcaRunRepository runs) {
        this.reports = reports;
        this.runs = runs;
    }

    @GetMapping(path = "/api/rca-runs/{runId}/report", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Map<String, Object>> report(@PathVariable String runId) {
        UUID id = parseRunId(runId);
        if (id == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "runId 非法"));
        }
        if (runs.findById(id).isEmpty()) {
            return ResponseEntity.status(404).body(Map.of("error", "run 不存在"));
        }
        return ResponseEntity.ok(reports.report(id));
    }

    private static UUID parseRunId(String runId) {
        try {
            return UUID.fromString(runId);
        } catch (IllegalArgumentException | NullPointerException e) {
            return null;
        }
    }
}
