package com.objwww.pr.arenaadmin.interfaces;

import com.objwww.pr.arenaadmin.application.ChaosActivationService;
import com.objwww.pr.arenaadmin.application.ChaosActivationService.InvalidRequestException;
import com.objwww.pr.arenaadmin.infrastructure.persistence.PostgresChaosAdminStore.Activation;
import com.objwww.pr.arenaadmin.infrastructure.persistence.PostgresChaosAdminStore.BackfillResult;
import com.objwww.pr.arenaadmin.infrastructure.persistence.PostgresChaosAdminStore.GtFields;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Map;

/**
 * chaos 管理 API（M2-17，仅 eval-mgmt 私网可达，无宿主端口）：
 * <ul>
 *   <li>POST /chaos/{faultType}/on —— 激活（单事务：GT+session+event+scenario_map）</li>
 *   <li>POST /chaos/{faultType}/off —— CAS 关闭（expectedGeneration 不符 = 409）</li>
 *   <li>GET /chaos/status?scenarioId= —— 会话状态 + 注入审计摘要</li>
 *   <li>POST /chaos/scenario-map/backfill —— 事件绑定（新版本行；旧代拒绝）</li>
 * </ul>
 * 鉴权：X-Admin-Token 常量时间比较；CHAOS_ADMIN_TOKEN 未配置时 fail-closed（503，
 * 管理面禁止无凭证开放）。
 */
@RestController
@Profile("docker")
public class ChaosAdminController {

    private static final Logger log = LoggerFactory.getLogger(ChaosAdminController.class);

    private final ChaosActivationService service;
    private final byte[] expectedToken;

    public ChaosAdminController(ChaosActivationService service,
                                @Value("${app.chaos-admin.admin-token:}") String adminToken) {
        this.service = service;
        this.expectedToken = (adminToken == null ? "" : adminToken.trim())
                .getBytes(StandardCharsets.UTF_8);
    }

    // ---------- 请求体 ----------

    public record ActivationRequest(String scenarioId, String target, Integer ttlSeconds,
                                    String operator, String configDigest,
                                    GroundTruthRequest groundTruth,
                                    Map<String, String> alertLabels, String ruleDigest) {
    }

    public record GroundTruthRequest(Integer schemaVersion, String datasetVersion,
                                     String payloadDigest, String applicableScope) {
    }

    public record DeactivateRequest(String scenarioId, Long expectedGeneration) {
    }

    public record BackfillRequest(String scenarioId, String incidentId,
                                  Long incidentGeneration, String runId, String reportId) {
    }

    // ---------- 端点 ----------

    @PostMapping(path = "/chaos/{faultType}/on")
    public ResponseEntity<?> on(@PathVariable String faultType,
                                @RequestHeader(value = "X-Admin-Token", required = false)
                                String token,
                                @RequestBody ActivationRequest request) {
        ResponseEntity<?> rejected = guard(token);
        if (rejected != null) {
            return rejected;
        }
        try {
            GtFields gt = request.groundTruth() == null ? null : new GtFields(
                    request.groundTruth().schemaVersion(),
                    request.groundTruth().datasetVersion(),
                    request.groundTruth().payloadDigest(),
                    request.groundTruth().applicableScope());
            Activation activation = service.activate(faultType, request.scenarioId(),
                    request.target(), request.ttlSeconds(), request.operator(),
                    request.configDigest(), gt, request.alertLabels(), request.ruleDigest());
            log.warn("chaos 激活: {} scenario={} operator={}", faultType,
                    request.scenarioId(), request.operator());
            return ResponseEntity.status(201).body(Map.of(
                    "sessionId", activation.sessionId().toString(),
                    "scenarioId", activation.scenarioId(),
                    "generation", 0,
                    "alertFingerprint", activation.alertFingerprint()));
        } catch (InvalidRequestException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (DataIntegrityViolationException e) {
            // 场景重复 / 同型同靶已 ACTIVE（uq_chaos_scenario / uq_chaos_one_active）
            return ResponseEntity.status(409).body(Map.of(
                    "error", "scenario 已存在或同型同靶会话仍活跃"));
        }
    }

    @PostMapping(path = "/chaos/{faultType}/off")
    public ResponseEntity<?> off(@PathVariable String faultType,
                                 @RequestHeader(value = "X-Admin-Token", required = false)
                                 String token,
                                 @RequestBody DeactivateRequest request) {
        ResponseEntity<?> rejected = guard(token);
        if (rejected != null) {
            return rejected;
        }
        try {
            boolean accepted = service.deactivate(request.scenarioId(),
                    request.expectedGeneration());
            if (!accepted) {
                return ResponseEntity.status(409).body(Map.of(
                        "error", "CAS 未中：会话不存在/状态不符/generation 过期"));
            }
            log.warn("chaos 关闭: {} scenario={}（进入 RECOVERING，等待恢复收口）",
                    faultType, request.scenarioId());
            return ResponseEntity.accepted().body(Map.of(
                    "state", "RECOVERING",
                    "scenarioId", request.scenarioId()));
        } catch (InvalidRequestException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @GetMapping(path = "/chaos/status")
    public ResponseEntity<?> status(@RequestHeader(value = "X-Admin-Token", required = false)
                                    String token,
                                    @RequestParam("scenarioId") String scenarioId) {
        ResponseEntity<?> rejected = guard(token);
        if (rejected != null) {
            return rejected;
        }
        ChaosActivationService.StatusView view = service.status(scenarioId);
        if (view == null) {
            return ResponseEntity.status(404).body(Map.of("error", "未知场景"));
        }
        return ResponseEntity.ok(Map.of(
                "session", Map.of(
                        "scenarioId", view.session().scenarioId(),
                        "faultType", view.session().faultType(),
                        "state", view.session().state(),
                        "generation", view.session().generation(),
                        "ttlSeconds", view.session().ttlSeconds(),
                        "expiresAt", view.session().expiresAt().toString()),
                "audit", view.audit()));
    }

    @PostMapping(path = "/chaos/scenario-map/backfill")
    public ResponseEntity<?> backfill(@RequestHeader(value = "X-Admin-Token", required = false)
                                      String token,
                                      @RequestBody BackfillRequest request) {
        ResponseEntity<?> rejected = guard(token);
        if (rejected != null) {
            return rejected;
        }
        try {
            BackfillResult result = service.backfillIncident(request.scenarioId(),
                    request.incidentId(), request.incidentGeneration(),
                    request.runId(), request.reportId());
            if (result == null) {
                // M2-24：未知场景 / 旧 generation 迟到事件一律不串场
                return ResponseEntity.status(409).body(Map.of(
                        "error", "回填拒绝：未知场景或事件代数过期"));
            }
            return ResponseEntity.accepted().body(Map.of(
                    "mappingVersion", result.mappingVersion(),
                    "alertFingerprint", result.alertFingerprint()));
        } catch (InvalidRequestException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    // ---------- 鉴权（常量时间；无凭证 fail-closed 503，错凭证 401） ----------

    /** @return null = 放行；否则直接返回给调用方 */
    private ResponseEntity<Map<String, Object>> guard(String token) {
        if (expectedToken.length == 0) {
            return ResponseEntity.status(503).body(Map.of(
                    "error", "管理面未配置 CHAOS_ADMIN_TOKEN，拒绝一切注入操作"));
        }
        if (token == null || !MessageDigest.isEqual(expectedToken,
                token.getBytes(StandardCharsets.UTF_8))) {
            return ResponseEntity.status(401).body(Map.of("error", "unauthorized"));
        }
        return null;
    }

    @ExceptionHandler(RuntimeException.class)
    public ResponseEntity<Map<String, Object>> unexpected(RuntimeException e) {
        log.error("管理面未预期异常", e);
        return ResponseEntity.status(500).body(Map.of("error", "internal error"));
    }
}
