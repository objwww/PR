package com.objwww.pr.control.eval.interfaces;

import com.objwww.pr.control.alert.domain.model.TypedRootCause;
import com.objwww.pr.control.eval.application.RegressionCaseAdmissionService;
import com.objwww.pr.control.eval.domain.model.CaseVersion;
import com.objwww.pr.control.eval.domain.model.DatasetVersion;
import com.objwww.pr.control.eval.domain.model.PartitionClass;
import com.objwww.pr.control.eval.domain.model.RegressionCandidate;
import com.objwww.pr.control.eval.domain.model.RegressionReview;
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
 * OP-01 回归案例准入 API（/api/eval/regression-candidates；权限沿既有
 * /api/eval/** OPERATOR 矩阵，写面同走 CSRF）：
 * <ul>
 *   <li>POST /api/eval/regression-candidates——body {reportId, feedbackId?,
 *       caseKey, scenarioFamilyId}；终态 run+STRUCTURE_VALIDATED 报告+非零证据
 *       轨迹才受理（FO03），受理 202 {candidateId, state=PENDING_REVIEW}；同源同
 *       caseKey 幂等重放 200（replayed=true，FO02）；来源契约不满足 409 携
 *       refusal（FO01）；</li>
 *   <li>GET /api/eval/regression-candidates?state=——按状态列候选（含 reviews）；</li>
 *   <li>POST /{id}/reviews——body {verdict(ACCEPTED_FOR_CANDIDATE/REJECTED/
 *       NEEDS_EVIDENCE), reason}；意见 append-only（(candidate,reviewer) 幂等），
 *       候选状态=意见集纯函数：全同→该结论、相异→DISPUTED（FO26/FO27）；</li>
 *   <li>POST /{id}/materialize——body {datasetName, datasetVersion, partition,
 *       expectedRootCause{component,faultType,reasonCode}, expectedSymptomCodes[]}；
 *       仅 ACCEPTED 候选可入集（409 其余状态）；GT 由人工显式提供（反馈/审核
 *       结论不作 GT）；写入不可变 DatasetVersion/CaseVersion，同
 *       (dataset,caseKey) 重放收敛（FO04）。</li>
 * </ul>
 * actor 唯一来源=认证主体（提议人/审核者均不自报）。
 */
@RestController
@Profile("docker")
@RequestMapping(path = "/api/eval", produces = MediaType.APPLICATION_JSON_VALUE)
public class RegressionCaseController {

    private final RegressionCaseAdmissionService service;

    public RegressionCaseController(RegressionCaseAdmissionService service) {
        this.service = service;
    }

    @PostMapping(path = "/regression-candidates",
            consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> propose(@RequestBody Map<String, Object> body) {
        UUID reportId = parseId(text(body.get("reportId")));
        UUID feedbackId = body.get("feedbackId") == null ? null
                : parseId(text(body.get("feedbackId")));
        if (reportId == null || text(body.get("caseKey")) == null
                || text(body.get("scenarioFamilyId")) == null) {
            return ResponseEntity.badRequest().body(Map.of("error",
                    "reportId/caseKey/scenarioFamilyId 必填"));
        }
        RegressionCaseAdmissionService.ProposeResult result;
        try {
            result = service.propose(new RegressionCaseAdmissionService.Proposal(
                    reportId, feedbackId, text(body.get("caseKey")),
                    text(body.get("scenarioFamilyId")),
                    truncate(AuthenticatedActor.name())));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
        if (result.refusal() != null) {
            return ResponseEntity.status(409).body(Map.of(
                    "error", "来源契约不满足", "refusal", result.refusal()));
        }
        Map<String, Object> out = view(result.candidate());
        out.put("replayed", result.replayed());
        return result.replayed()
                ? ResponseEntity.ok(out)
                : ResponseEntity.status(202).body(out);
    }

    @GetMapping(path = "/regression-candidates")
    public ResponseEntity<?> list(@RequestParam(required = false) String state) {
        List<RegressionCandidate> rows = service.byState(state == null || state.isBlank()
                ? RegressionCandidate.ST_PENDING_REVIEW : state.trim());
        List<Map<String, Object>> out = new ArrayList<>();
        for (RegressionCandidate row : rows) {
            Map<String, Object> view = view(row);
            view.put("reviews", reviewsView(row.id()));
            out.add(view);
        }
        return ResponseEntity.ok(Map.of("candidates", out));
    }

    @PostMapping(path = "/regression-candidates/{id}/reviews",
            consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> review(@PathVariable String id,
            @RequestBody Map<String, Object> body) {
        UUID candidateId = parseId(id);
        String verdict = text(body.get("verdict"));
        String reason = text(body.get("reason"));
        if (candidateId == null || verdict == null || reason == null) {
            return ResponseEntity.badRequest().body(Map.of("error",
                    "verdict/reason 必填"));
        }
        RegressionCandidate candidate;
        try {
            candidate = service.review(candidateId, truncate(AuthenticatedActor.name()),
                    verdict.trim().toUpperCase(), reason);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(404).body(Map.of("error", e.getMessage()));
        } catch (IllegalStateException e) {
            return ResponseEntity.status(409).body(Map.of("error", e.getMessage()));
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("candidateId", candidate.id().toString());
        out.put("state", candidate.state());
        out.put("reviews", reviewsView(candidate.id()));
        return ResponseEntity.status(202).body(out);
    }

    @PostMapping(path = "/regression-candidates/{id}/materialize",
            consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> materialize(@PathVariable String id,
            @RequestBody Map<String, Object> body) {
        UUID candidateId = parseId(id);
        String datasetName = text(body.get("datasetName"));
        String datasetVersion = text(body.get("datasetVersion"));
        String partition = text(body.get("partition"));
        if (candidateId == null || datasetName == null || datasetVersion == null
                || partition == null || !(body.get("expectedRootCause") instanceof Map)) {
            return ResponseEntity.badRequest().body(Map.of("error",
                    "datasetName/datasetVersion/partition/expectedRootCause 必填"));
        }
        TypedRootCause rootCause;
        try {
            Map<?, ?> raw = (Map<?, ?>) body.get("expectedRootCause");
            rootCause = new TypedRootCause(text(raw.get("component")),
                    text(raw.get("faultType")), text(raw.get("reasonCode")));
        } catch (IllegalArgumentException | NullPointerException e) {
            return ResponseEntity.badRequest().body(Map.of("error",
                    "expectedRootCause{component,faultType,reasonCode} 非法: "
                            + e.getMessage()));
        }
        RegressionCaseAdmissionService.MaterializeResult result;
        try {
            result = service.materialize(candidateId,
                    new RegressionCaseAdmissionService.MaterializeCommand(
                            datasetName, datasetVersion,
                            PartitionClass.valueOf(partition.trim().toUpperCase()),
                            rootCause, textList(body.get("expectedSymptomCodes")),
                            truncate(AuthenticatedActor.name())));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(404).body(Map.of("error", e.getMessage()));
        } catch (IllegalStateException e) {
            return ResponseEntity.status(409).body(Map.of("error", e.getMessage()));
        }
        CaseVersion caseRow = result.caseVersion();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("datasetId", result.dataset().id().toString());
        out.put("datasetName", result.dataset().name());
        out.put("datasetVersion", result.dataset().version());
        out.put("partition", result.dataset().partitionClass().name());
        out.put("caseKey", caseRow.caseKey());
        out.put("scenarioFamilyId", caseRow.scenarioFamilyId());
        out.put("replayed", result.replayed());
        return ResponseEntity.status(result.replayed() ? 200 : 201).body(out);
    }

    // ------------------------------------------------------------------ 内部

    private List<Map<String, Object>> reviewsView(UUID candidateId) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (RegressionReview review : service.reviewsOf(candidateId)) {
            Map<String, Object> view = new LinkedHashMap<>();
            view.put("reviewer", review.reviewer());
            view.put("verdict", review.verdict());
            view.put("reason", review.reason());
            view.put("createdAt", review.createdAt().toString());
            out.add(view);
        }
        return out;
    }

    private static Map<String, Object> view(RegressionCandidate candidate) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("candidateId", candidate.id().toString());
        out.put("sourceRunId", candidate.sourceRunId().toString());
        out.put("sourceReportId", candidate.sourceReportId().toString());
        out.put("sourceFeedbackId", candidate.sourceFeedbackId() == null
                ? null : candidate.sourceFeedbackId().toString());
        out.put("sourceDigest", candidate.sourceDigest());
        out.put("caseKey", candidate.caseKey());
        out.put("scenarioFamilyId", candidate.scenarioFamilyId());
        out.put("state", candidate.state());
        out.put("createdBy", candidate.createdBy());
        out.put("createdAt", candidate.createdAt().toString());
        return out;
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

    /** 体来源字段可为 null（path 变量恒非空，body 字段缺省即 null）——先判空再解析 */
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
