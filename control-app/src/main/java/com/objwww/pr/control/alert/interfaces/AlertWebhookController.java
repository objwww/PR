package com.objwww.pr.control.alert.interfaces;

import com.objwww.pr.control.alert.application.AlertIntakeService;
import com.objwww.pr.control.alert.application.ControlAlertRouter;
import com.objwww.pr.control.alert.application.IntakeRejectedException;
import com.objwww.pr.control.ops.duty.application.DutyDispatchService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.DataAccessException;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.UUID;

/**
 * 告警入口（§6.4）：POST /webhooks/alertmanager。
 *
 * <p>四类状态码语义（webhook.go 源码事实：仅 5xx 可恢复、Retry-After 头不被读取）：
 * <ul>
 *   <li>401 验签失败（EX-C3a 起由 SecurityFilterChain 统一承担——机器 bearer 铸
 *       ROLE_MACHINE_WEBHOOK；控制面声明组的防自噬验签仍在 ControlAlertRouter.guard）</li>
 *   <li>400/413 结构非法/超尺寸（零落库；AM 不重试 4xx）</li>
 *   <li>503 仅整组无法持久化（DB 故障；AM 仅对 5xx 整组重试）</li>
 *   <li>202 整组落库（含空组 IGNORED——202=已持久化）</li>
 * </ul>
 * M5-16：入口二次判断（ControlAlertRouter）先行于业务路——控制面声明组经三面门
 * （身份/route 白名单/monitoring_scope 白名单）ROUTED_ONCALL 直达值班通道，
 * REJECTED fail-closed；controller 不承载判定逻辑（最小侵入）。
 * 不落表 @Profile("docker") 之外的世界（沿旧线 WebhookController 惯例：默认 profile 无
 * DataSource，endpoint 随整条链路只在 docker profile 暴露；语义由 EX-A* 覆盖）。
 */
@RestController
@Profile("docker")
public class AlertWebhookController {

    private static final Logger log = LoggerFactory.getLogger(AlertWebhookController.class);

    private final AlertIntakeService intake;
    private final ControlAlertRouter controlRouter;
    private final DutyDispatchService dutyDispatch;

    public AlertWebhookController(AlertIntakeService intake,
                                  ControlAlertRouter controlRouter,
                                  DutyDispatchService dutyDispatch) {
        this.intake = intake;
        this.controlRouter = controlRouter;
        this.dutyDispatch = dutyDispatch;
    }

    @PostMapping(path = "/webhooks/alertmanager", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Map<String, Object>> receive(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestHeader(value = "Content-Encoding", required = false) String contentEncoding,
            @RequestBody byte[] body) {

        boolean gzip = contentEncoding != null
                && contentEncoding.toLowerCase().contains("gzip");

        // M5-16 入口二次判断先行：控制面声明（RCA_SYSTEM 家族/monitoring_scope）走防自噬
        // 三面门——ROUTED 直达值班通道（不进业务 intake，Incident/Run 增量恒 0，INV-AM5-4），
        // REJECTED fail-closed（伪造 label 零落库）；无声明才落业务路（401/intake 语义不变）。
        // M7-13：ROUTED 分支同步落值班台账（原子对：duty_notification + 首行 duty_delivery）；
        // 派发持久化失败 = 503（AM 整组重试，fingerprint 去重幂等）
        ControlAlertRouter.Decision control = controlRouter.guard(body, gzip, authorization);
        switch (control.outcome()) {
            case ROUTED_ONCALL:
                try {
                    var out = dutyDispatch.dispatchSystem(control.group());
                    return ResponseEntity.accepted().body(Map.of(
                            "status", "accepted", "routed", "oncall",
                            "inboxId", control.inboxId().toString(),
                            "dutyNotificationId", out.notificationId().toString()));
                } catch (org.springframework.dao.DataAccessException e) {
                    log.warn("值班台账无法持久化，返回 503: {}", e.getMostSpecificCause().getMessage());
                    return ResponseEntity.status(503).body(Map.of("error", "storage unavailable"));
                }
            case REJECTED:
                return ResponseEntity.status(control.httpStatus())
                        .body(Map.of("error", control.reason()));
            case PASS_THROUGH:
                break;
        }

        try {
            UUID inboxId = intake.store(body, gzip);
            return ResponseEntity.accepted().body(Map.of("status", "accepted", "inboxId", inboxId.toString()));
        } catch (IntakeRejectedException e) {
            return ResponseEntity.status(e.httpStatus()).body(Map.of("error", e.getMessage()));
        } catch (DataAccessException e) {
            // 503 仅 DB 故障（AM 整组重试它该重试的）
            log.warn("整组无法持久化，返回 503: {}", e.getMostSpecificCause().getMessage());
            return ResponseEntity.status(503).body(Map.of("error", "storage unavailable"));
        }
    }
}
