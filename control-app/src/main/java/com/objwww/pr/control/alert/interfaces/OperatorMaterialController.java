package com.objwww.pr.control.alert.interfaces;

import com.objwww.pr.control.alert.application.OperatorMaterialService;
import com.objwww.pr.control.alert.domain.model.OperatorMaterial;
import com.objwww.pr.control.alert.domain.repository.OperatorMaterialRepository;
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

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * 人工补充材料入口（MC31/32，/api/v1/** 归 ROLE_OPERATOR 矩阵）：
 * <ul>
 *   <li>POST /api/v1/incidents/{incidentId}/materials——提交补充材料；操作者身份
 *       取认证主体（{@link AuthenticatedActor}），请求体不带身份自报；CAS 冲突
 *       409 携带当前版本；incident 已 RESOLVED → 200 LATE（明确终态不挂起）；</li>
 *   <li>GET /api/v1/incidents/{incidentId}/materials——台账 + 当前版本。</li>
 * </ul>
 * 400/404/409 应答沿用 {"error": ...} 惯例。
 */
@RestController
@Profile("docker")
@RequestMapping(path = "/api/v1", produces = MediaType.APPLICATION_JSON_VALUE)
public class OperatorMaterialController {

    private final OperatorMaterialService service;
    private final OperatorMaterialRepository materials;

    public OperatorMaterialController(OperatorMaterialService service,
            OperatorMaterialRepository materials) {
        this.service = service;
        this.materials = materials;
    }

    public record MaterialRequest(UUID runId, OperatorMaterial.Kind kind,
            String sourceRef, String content, int baseRevision) {
    }

    @PostMapping(path = "/incidents/{incidentId}/materials",
            consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> submit(@PathVariable String incidentId,
            @RequestBody MaterialRequest request) {
        UUID id;
        try {
            id = UUID.fromString(incidentId);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", "incidentId 非法"));
        }
        if (request == null || request.kind() == null || request.content() == null) {
            return ResponseEntity.badRequest().body(Map.of("error",
                    "kind/content 必填（OBSERVATION|EVIDENCE_LINK|JUDGMENT）"));
        }
        try {
            OperatorMaterialService.Verdict verdict = service.submit(
                    new OperatorMaterialService.Submission(id, request.runId(),
                            request.kind(), request.sourceRef(), request.content(),
                            request.baseRevision()),
                    AuthenticatedActor.name());
            return switch (verdict.outcome()) {
                case CONFLICT -> ResponseEntity.status(409).body(Map.of(
                        "error", "材料集版本冲突（另有并发提交生效）",
                        "current_revision", verdict.currentRevision()));
                case ACCEPTED -> ResponseEntity.ok(Map.of(
                        "outcome", "ACCEPTED",
                        "material", view(verdict.material()),
                        "current_revision", verdict.currentRevision()));
                case LATE -> ResponseEntity.ok(Map.of(
                        "outcome", "REJECTED_LATE",
                        "material", view(verdict.material()),
                        "current_revision", verdict.currentRevision()));
            };
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @GetMapping("/incidents/{incidentId}/materials")
    public ResponseEntity<?> list(@PathVariable String incidentId) {
        UUID id;
        try {
            id = UUID.fromString(incidentId);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", "incidentId 非法"));
        }
        List<Map<String, Object>> rows = materials.findByIncident(id).stream()
                .map(OperatorMaterialController::view)
                .collect(Collectors.toList());
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("materials", rows);
        body.put("current_revision", materials.currentRevision(id));
        return ResponseEntity.ok(body);
    }

    private static Map<String, Object> view(OperatorMaterial m) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", m.id().toString());
        row.put("run_id", m.runId() == null ? null : m.runId().toString());
        row.put("operator", m.operator());
        row.put("kind", m.kind().name());
        row.put("source_ref", m.sourceRef());
        row.put("content", m.content());
        row.put("revision", m.revision());
        row.put("admission", m.admission().name());
        row.put("created_at", m.createdAt().toString());
        return row;
    }
}
