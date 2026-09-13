package com.objwww.pr.control.release.interfaces;

import com.objwww.pr.control.infrastructure.auth.AuthenticatedActor;
import com.objwww.pr.control.release.application.SkillCuratorService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Skill 溠头人工发起 API（CL-09，告警-Agent闭环修复 v1 §7.1）：已认证操作者对
 * 已封存调查轨迹发起确定性 TEMPLATE 提炼——复用 SkillCuratorService（来源契约/
 * sourceDigest 幂等/S02 未复核隔离），只补 HTTP 入口不建平行业务层。
 *
 * <p>mode 首期仅 TEMPLATE（确定性提炼，同步返回）；LLM_CURATE（模型归纳+持久
 * curation job）经独立预算与评测后再启用——请求该模式返回 501 如实未就绪，
 * 不以静态样例伪装。curated_by 取认证主体（不收自报身份）；humanReviewed 是
 * 调用方声明的人工复核依据（未复核 → S02 REJECTED 隔离，不当可信正向原料），
 * 不是"已验证"的证明。Idempotency-Key 随审计日志留痕（幂等锚=sourceDigest）。
 */
@RestController
@Profile("docker")
public class SkillCurationController {

    private static final Logger log = LoggerFactory.getLogger(SkillCurationController.class);

    private final SkillCuratorService curator;

    public SkillCurationController(SkillCuratorService curator) {
        this.curator = curator;
    }

    @PostMapping(path = "/api/skill-curations", consumes = "application/json")
    public ResponseEntity<Map<String, Object>> curate(
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestBody Map<String, Object> body) {
        String mode = textOf(body.get("mode"), "TEMPLATE");
        if (!"TEMPLATE".equals(mode)) {
            return ResponseEntity.status(501).body(Map.of(
                    "error", "mode " + mode + " 未就绪（首期仅 TEMPLATE；"
                            + "LLM_CURATE 经独立预算与评测后启用）",
                    "mode", mode));
        }
        String runIdRaw = textOf(body.get("runId"), null);
        String name = textOf(body.get("name"), null);
        if (runIdRaw == null || name == null) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "runId 与 name 必填"));
        }
        UUID runId;
        try {
            runId = UUID.fromString(runIdRaw);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "runId 非合法 UUID: " + runIdRaw));
        }
        boolean humanReviewed = Boolean.TRUE.equals(body.get("humanReviewed"));
        String operator = AuthenticatedActor.name();
        try {
            SkillCuratorService.Verdict verdict = curator.curate(
                    new SkillCuratorService.Curation(runId, name,
                            textOf(body.get("alertname"), null),
                            textOf(body.get("service"), null),
                            humanReviewed, operator));
            Map<String, Object> resp = new LinkedHashMap<>();
            resp.put("mode", "TEMPLATE");
            resp.put("curatedBy", operator);
            if (verdict.refusal() != null) {
                resp.put("refusal", verdict.refusal());
                log.info("Skill curation 拒绝 run={} by={} mode=TEMPLATE reason={}",
                        runId, operator, verdict.refusal());
                return ResponseEntity.unprocessableEntity().body(resp);
            }
            resp.put("candidateId", verdict.candidate().id().toString());
            resp.put("status", verdict.candidate().status());
            resp.put("humanReviewed", humanReviewed);
            log.info("Skill curation 提炼 run={} by={} mode=TEMPLATE → {} {}",
                    runId, operator, verdict.candidate().name(),
                    verdict.candidate().status());
            return ResponseEntity.ok(resp);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    private static String textOf(Object value, String fallback) {
        if (value instanceof String s && !s.isBlank()) {
            return s;
        }
        return fallback;
    }
}
