package com.objwww.pr.control.alert.interfaces;

import com.objwww.pr.control.alert.application.IncidentQueryService;
import com.objwww.pr.control.alert.domain.repository.IncidentQueryReader;
import com.objwww.pr.control.infrastructure.auth.AuthenticatedActor;
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
 * UI-1 告警只读查询投影 API（/api/v1/**；全 GET 零写面，ROLE_OPERATOR 归
 * SecurityFilterChain 矩阵）。DTO 为 record（IncidentQueryService 内）；
 * 400/404 应答沿用 {"error": ...} 惯例。
 *
 * <ul>
 *   <li>GET /api/v1/incidents——列表（status/severity/service/q 过滤 + 键集游标，
 *       limit 默认 50 上限 200，total=过滤后总数）；</li>
 *   <li>GET /api/v1/incidents/{incidentId}——详情 + labels/annotations 全文
 *       + alert_event 时间线 + 当前 run 徽标；</li>
 *   <li>GET /api/v1/incidents/facets——facet 计数（severity 维不过滤自身参数）；</li>
 *   <li>GET /api/v1/incidents/summary——统计条（mttr 诚实 null）；</li>
 *   <li>GET /api/v1/overview/summary——总览聚合（告警侧 SQL + cases/duty/通知
 *       经各自既有端口装配，口径不新造）。</li>
 * </ul>
 */
@RestController
@Profile("docker")
@RequestMapping(path = "/api/v1", produces = MediaType.APPLICATION_JSON_VALUE)
public class IncidentQueryController {

    private final IncidentQueryService query;

    public IncidentQueryController(IncidentQueryService query) {
        this.query = query;
    }

    @GetMapping("/incidents")
    public ResponseEntity<?> incidents(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String severity,
            @RequestParam(required = false) String service,
            @RequestParam(required = false) String q,
            @RequestParam(required = false) String cursor,
            @RequestParam(required = false, defaultValue = "50") int limit) {
        try {
            return ResponseEntity.ok(query.list(status, severity, service, q, cursor,
                    Math.clamp(limit, 1, 200)));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @GetMapping("/incidents/facets")
    public ResponseEntity<?> facets(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String service,
            @RequestParam(required = false) String q) {
        try {
            return ResponseEntity.ok(query.facets(status, service, q));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @GetMapping("/incidents/summary")
    public IncidentQueryReader.IncidentSummary summary() {
        return query.summary();
    }

    @GetMapping("/incidents/{incidentId}")
    public ResponseEntity<?> detail(@PathVariable String incidentId) {
        UUID id;
        try {
            id = UUID.fromString(incidentId);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", "incidentId 非法"));
        }
        return query.detail(id)
                .<ResponseEntity<?>>map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.status(404)
                        .body(Map.of("error", "incident 不存在")));
    }

    @GetMapping("/overview/summary")
    public IncidentQueryService.OverviewResponse overview() {
        return query.overview(AuthenticatedActor.name());
    }
}
