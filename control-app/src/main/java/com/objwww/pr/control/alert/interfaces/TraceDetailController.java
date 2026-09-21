package com.objwww.pr.control.alert.interfaces;

import com.objwww.pr.control.alert.domain.repository.RcaToolSpanDetailReader;
import org.springframework.context.annotation.Profile;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 工具 span 明细 API（M-d T2，Trace 六要素补齐）：GET /api/rca-runs/{runId}/trace-details。
 *
 * <p>与 /trace 瀑布配套：瀑布 span 行保持既有 18 字段契约零扰动（RunQueryService 与
 * 既有测试不动），工具维"参数面/返回摘要/证据引用"经本端点下发，前端按 invocationId
 * 与 span.id 关联后展示。读侧真账本直出（rca_tool_invocation LEFT JOIN rca_evidence），
 * 零造数；reader 未装配或 run 无工具调用 → details 空表（前端空态口径说明，不造数）。
 * 鉴权随 /api/rca-runs/** 既有 SecurityFilterChain 规则，本类不另开门。
 */
@RestController
@Profile("docker")
public class TraceDetailController {

    private final RcaToolSpanDetailReader details;

    public TraceDetailController(RcaToolSpanDetailReader details) {
        this.details = details;
    }

    @GetMapping(path = "/api/rca-runs/{runId}/trace-details",
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Map<String, Object>> traceDetails(
            @PathVariable String runId) {
        UUID id = parseRunId(runId);
        if (id == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "runId 非法"));
        }
        List<RcaToolSpanDetailReader.ToolSpanDetail> rows =
                details == null ? List.of() : details.detailsByRun(id);
        List<Map<String, Object>> out = new ArrayList<>(rows.size());
        for (RcaToolSpanDetailReader.ToolSpanDetail d : rows) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("invocationId", d.invocationId().toString());
            row.put("taskId", d.taskId() == null ? null : d.taskId().toString());
            row.put("toolName", d.toolName());
            row.put("callSeq", d.callSeq());
            row.put("scopeSummary", d.scopeSummary());
            row.put("resultSummary", d.resultSummary());
            row.put("evidenceRef", d.evidenceRef());
            row.put("evidenceType", d.evidenceType());
            row.put("evidenceSource", d.evidenceSource());
            // BA-190（W3）：拒因具体消息随明细透出（null=无详情/旧行，如实不造）
            row.put("reasonDetail", d.reasonDetail());
            out.add(row);
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("details", out);
        return ResponseEntity.ok(body);
    }

    private static UUID parseRunId(String runId) {
        try {
            return UUID.fromString(runId);
        } catch (IllegalArgumentException | NullPointerException e) {
            return null;
        }
    }
}
