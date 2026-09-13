package com.objwww.pr.control.alert.interfaces;

import com.objwww.pr.control.alert.application.ReportFeedbackService;
import com.objwww.pr.control.alert.domain.model.ReportFeedback;
import com.objwww.pr.control.infrastructure.auth.AuthenticatedActor;
import org.springframework.context.annotation.Profile;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * OP-04 终态报告反馈 API（POST/GET /api/rca-runs/{runId}/report/{reportId}/feedback）：
 * 与活跃 Run 命令面（/commands 的 Cancel/Hint）分离——只对已终态 run 的已发布
 * 报告开放评价（FO22）。权限沿既有 /api/rca-runs/** OPERATOR 矩阵，浏览器会话
 * 写面走 CSRF（同命令面）；author 唯一来源=认证主体（请求体不自报）。
 *
 * <p>FO23 幂等：同 (author,idempotencyKey) 同载荷重放 200（replayed=true），
 * 异载荷 409；FO25 更正：supersedesId 指向本人前序，同前序并发更正一胜一拒
 * （败者 409 携 SUPERSEDED_BY）；FO29 版本对账：expectedReportDigest 不符 409。
 */
@RestController
@Profile("docker")
@RequestMapping(path = "/api/rca-runs", produces = MediaType.APPLICATION_JSON_VALUE)
public class ReportFeedbackController {

    private final ReportFeedbackService service;

    public ReportFeedbackController(ReportFeedbackService service) {
        this.service = service;
    }

    @PostMapping(path = "/{runId}/report/{reportId}/feedback",
            consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> submit(@PathVariable String runId,
            @PathVariable String reportId,
            @RequestBody Map<String, Object> body) {
        UUID report = parseId(reportId);
        if (report == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "reportId 非法"));
        }
        ReportFeedbackService.SubmitResult result;
        try {
            result = service.submit(report, text(body.get("expectedReportDigest")),
                    parseVerdict(text(body.get("verdict"))), text(body.get("reason")),
                    textList(body.get("evidenceRefs")),
                    body.get("supersedesId") == null ? null
                            : parseId(String.valueOf(body.get("supersedesId"))),
                    text(body.get("idempotencyKey")),
                    truncate(AuthenticatedActor.name()));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(404).body(Map.of("error", e.getMessage()));
        } catch (IllegalStateException e) {
            return ResponseEntity.status(409).body(Map.of("error", e.getMessage()));
        }
        if (result.conflict() != null) {
            return ResponseEntity.status(409).body(Map.of(
                    "error", "该反馈的前序已被并发更正占位", "conflict", result.conflict()));
        }
        Map<String, Object> out = view(result.stored());
        if (result.replayed()) {
            out.put("replayed", true);
            return ResponseEntity.ok(out);
        }
        out.put("replayed", false);
        return ResponseEntity.status(201).body(out);
    }

    @GetMapping(path = "/{runId}/report/{reportId}/feedback")
    public ResponseEntity<?> list(@PathVariable String runId,
            @PathVariable String reportId) {
        UUID report = parseId(reportId);
        if (report == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "reportId 非法"));
        }
        List<Map<String, Object>> rows = new ArrayList<>();
        for (ReportFeedback feedback : service.byReport(report)) {
            rows.add(view(feedback));
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("reportId", report.toString());
        out.put("feedbacks", rows);
        return ResponseEntity.ok(out);
    }

    // ------------------------------------------------------------------ 内部

    private static Map<String, Object> view(ReportFeedback feedback) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", feedback.id().toString());
        out.put("reportId", feedback.reportId().toString());
        out.put("runId", feedback.runId().toString());
        out.put("reportDigest", feedback.reportDigest());
        out.put("author", feedback.author());
        out.put("verdict", feedback.verdict().name());
        out.put("reason", feedback.reason());
        out.put("evidenceRefs", feedback.evidenceRefs());
        out.put("supersedesId", feedback.supersedesId() == null
                ? null : feedback.supersedesId().toString());
        out.put("createdAt", feedback.createdAt().toString());
        return out;
    }

    private static ReportFeedback.Verdict parseVerdict(String raw) {
        if (raw == null) {
            throw new IllegalArgumentException("verdict 必填（ACCEPTED/PARTIAL/INCORRECT/INSUFFICIENT）");
        }
        return ReportFeedback.Verdict.valueOf(raw.trim().toUpperCase());
    }

    private static String text(Object raw) {
        return raw instanceof String s && !s.isBlank() ? s.trim() : null;
    }

    private static List<String> textList(Object raw) {
        if (!(raw instanceof List<?> list)) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        for (Object item : list) {
            if (item instanceof String s && !s.isBlank()) {
                out.add(s.trim());
            }
        }
        return out;
    }

    /** actor 截断面沿 RunCommandController 同式（审计列宽防御） */
    private static String truncate(String actor) {
        return actor.length() > 64 ? actor.substring(0, 64) : actor;
    }

    private static UUID parseId(String raw) {
        if (raw == null) {
            return null;
        }
        try {
            return UUID.fromString(raw);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
