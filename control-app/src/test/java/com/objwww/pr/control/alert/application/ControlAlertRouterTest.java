package com.objwww.pr.control.alert.application;

import com.objwww.pr.control.alert.domain.model.AlertInbox;
import com.objwww.pr.control.alert.domain.model.InboxDecision;
import com.objwww.pr.control.alert.domain.model.InboxState;
import com.objwww.pr.control.alert.support.AlertInMemoryStores;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * M5-16 防自噬路由（INV-AM5-4：安全门任一失败 fail-closed；RCA_SYSTEM 告警不创建
 * Incident/Run）。二次判断三面：身份（独立 bearer，常量时间）→ AM route 白名单 →
 * monitoring_scope 白名单——**不信 webhook body 同名 label 单独作数**（方案 §3.2）。
 * 审计面：ROUTED 行 = alert_inbox 直写 PROCESSED+SUPPRESSED（V7 预留枚举的消费者），
 * claimNext 永不领取（结构性证明：投影器碰不到）。
 */
class ControlAlertRouterTest {

    private static final String CONTROL_BEARER = "control-bearer-token";
    private static final String BUSINESS_BEARER = "test-bearer-token";
    private static final Instant FIXED = Instant.parse("2026-09-07T10:00:00Z");

    private AlertInMemoryStores stores;
    private ControlAlertRouter router;

    @BeforeEach
    void setUp() {
        stores = new AlertInMemoryStores();
        router = new ControlAlertRouter(CONTROL_BEARER,
                Set.of("rca-oncall"), Set.of("rca_system"),
                stores.inbox, () -> FIXED, AlertIntakeLimits.defaults());
    }

    private static String controlGroup(String receiver, String scope, String alertname) {
        String scopeLabel = scope == null ? "" : ", \"monitoring_scope\": \"" + scope + "\"";
        return """
                {
                  "version": "4",
                  "receiver": "%s",
                  "groupKey": "g:%s",
                  "groupLabels": {"alertname": "%s"},
                  "commonLabels": {},
                  "commonAnnotations": {},
                  "status": "firing",
                  "alerts": [
                    {
                      "status": "firing",
                      "labels": {"alertname": "%s", "fingerprint_seed": "x"%s},
                      "annotations": {},
                      "startsAt": "2026-09-07T09:00:00Z",
                      "fingerprint": "fp-control-1"
                    }
                  ],
                  "truncatedAlerts": 0
                }
                """.formatted(receiver, alertname, alertname, alertname, scopeLabel);
    }

    // ---------------- 伪造 label：业务身份 + 控制面声明 → 401 零落库 ----------------

    @Test
    void forgedScopeLabelWithBusinessIdentityIsRejectedZeroPersistence() {
        ControlAlertRouter.Decision d = router.guard(
                controlGroup("rca-oncall", "rca_system", "RCA_SYSTEM_CollectorDown")
                        .getBytes(StandardCharsets.UTF_8),
                false, "Bearer " + BUSINESS_BEARER);

        assertThat(d.outcome()).isEqualTo(ControlAlertRouter.Outcome.REJECTED);
        assertThat(d.httpStatus()).isEqualTo(401);
        assertThat(stores.inbox.all()).isEmpty();   // INV-AM5-4 fail-closed
    }

    // ---------------- 认证后 RCA_SYSTEM 组 → 值班通道 + 零 Incident/Run 面 ----------------

    @Test
    void authenticatedControlGroupRoutesToOnCallWithSuppressedAuditRow() {
        ControlAlertRouter.Decision d = router.guard(
                controlGroup("rca-oncall", "rca_system", "RCA_SYSTEM_CollectorDown")
                        .getBytes(StandardCharsets.UTF_8),
                false, "Bearer " + CONTROL_BEARER);

        assertThat(d.outcome()).isEqualTo(ControlAlertRouter.Outcome.ROUTED_ONCALL);
        assertThat(d.inboxId()).isNotNull();

        // 投递记录锚：直写 PROCESSED + SUPPRESSED（V7 预留枚举），processed_at 闭合
        assertThat(stores.inbox.all()).hasSize(1);
        AlertInbox row = stores.inbox.all().get(0);
        assertThat(row.state()).isEqualTo(InboxState.PROCESSED);
        assertThat(row.decision()).isEqualTo(InboxDecision.SUPPRESSED);
        assertThat(row.processedAt()).isEqualTo(FIXED);

        // 结构性证明"不创建 Incident/Run"：投影器领取面（claimNext）永不可达
        assertThat(stores.inbox.claimNext("owner-1", FIXED, java.time.Duration.ofMinutes(2)))
                .isEmpty();
    }

    @Test
    void scopeLabelAloneAlsoClaimsAndRoutes() {
        // 非 RCA_SYSTEM alertname 但带 monitoring_scope（Gatus 合成告警形态）
        ControlAlertRouter.Decision d = router.guard(
                controlGroup("rca-oncall", "rca_system", "Gatus_CanaryProbe")
                        .getBytes(StandardCharsets.UTF_8),
                false, "Bearer " + CONTROL_BEARER);

        assertThat(d.outcome()).isEqualTo(ControlAlertRouter.Outcome.ROUTED_ONCALL);
    }

    // ---------------- 白名单反例：身份过但 route/scope 不在白名单 → 400 ----------------

    @Test
    void receiverOutsideWhitelistIsRejected400() {
        ControlAlertRouter.Decision d = router.guard(
                controlGroup("zhongtai", "rca_system", "RCA_SYSTEM_X")
                        .getBytes(StandardCharsets.UTF_8),
                false, "Bearer " + CONTROL_BEARER);

        assertThat(d.outcome()).isEqualTo(ControlAlertRouter.Outcome.REJECTED);
        assertThat(d.httpStatus()).isEqualTo(400);
        assertThat(stores.inbox.all()).isEmpty();
    }

    @Test
    void scopeOutsideWhitelistIsRejected400() {
        ControlAlertRouter.Decision d = router.guard(
                controlGroup("rca-oncall", "prod_business", "RCA_SYSTEM_X")
                        .getBytes(StandardCharsets.UTF_8),
                false, "Bearer " + CONTROL_BEARER);

        assertThat(d.outcome()).isEqualTo(ControlAlertRouter.Outcome.REJECTED);
        assertThat(d.httpStatus()).isEqualTo(400);
        assertThat(stores.inbox.all()).isEmpty();
    }

    @Test
    void rcaSystemAlertnameWithoutScopeLabelIsRejected400() {
        // 声明了控制面家族却无可校验的 scope——fail-closed，不放行业务路
        ControlAlertRouter.Decision d = router.guard(
                controlGroup("rca-oncall", null, "RCA_SYSTEM_X")
                        .getBytes(StandardCharsets.UTF_8),
                false, "Bearer " + CONTROL_BEARER);

        assertThat(d.outcome()).isEqualTo(ControlAlertRouter.Outcome.REJECTED);
        assertThat(d.httpStatus()).isEqualTo(400);
    }

    // ---------------- 身份面：空配置密钥恒拒（fail-closed） ----------------

    @Test
    void blankConfiguredControlBearerFailsClosed() {
        ControlAlertRouter blank = new ControlAlertRouter("",
                Set.of("rca-oncall"), Set.of("rca_system"),
                stores.inbox, () -> FIXED, AlertIntakeLimits.defaults());

        ControlAlertRouter.Decision d = blank.guard(
                controlGroup("rca-oncall", "rca_system", "RCA_SYSTEM_X")
                        .getBytes(StandardCharsets.UTF_8),
                false, "Bearer anything");

        assertThat(d.outcome()).isEqualTo(ControlAlertRouter.Outcome.REJECTED);
        assertThat(d.httpStatus()).isEqualTo(401);
        assertThat(stores.inbox.all()).isEmpty();
    }

    // ---------------- 正例旁路：无控制面声明 → PASS_THROUGH（业务路不动） ----------------

    @Test
    void businessGroupWithoutClaimPassesThrough() {
        String business = """
                {
                  "version": "4", "receiver": "zhongtai", "groupKey": "g:HighErrorRate",
                  "groupLabels": {}, "commonLabels": {}, "commonAnnotations": {},
                  "status": "firing",
                  "alerts": [
                    {"status": "firing",
                     "labels": {"alertname": "HighErrorRate", "service": "checkout"},
                     "annotations": {}, "startsAt": "2026-09-07T09:00:00Z",
                     "fingerprint": "fp-biz-1"}
                  ],
                  "truncatedAlerts": 0
                }
                """;

        ControlAlertRouter.Decision d = router.guard(
                business.getBytes(StandardCharsets.UTF_8),
                false, "Bearer " + BUSINESS_BEARER);

        assertThat(d.outcome()).isEqualTo(ControlAlertRouter.Outcome.PASS_THROUGH);
        assertThat(stores.inbox.all()).isEmpty();   // 业务组照旧走 intake，路由器不落库
    }

    // ---------------- gzip 洗声明：压缩体也要过声明扫描 ----------------

    @Test
    void gzippedControlClaimIsStillRouted() throws Exception {
        byte[] gz = gzip(controlGroup("rca-oncall", "rca_system", "RCA_SYSTEM_X"));

        ControlAlertRouter.Decision d = router.guard(gz, true, "Bearer " + CONTROL_BEARER);

        assertThat(d.outcome()).isEqualTo(ControlAlertRouter.Outcome.ROUTED_ONCALL);
    }

    // ---------------- 结构垃圾 → PASS_THROUGH（intake 是结构权威） ----------------

    @Test
    void structuralJunkFallsThroughToIntakeAuthority() {
        ControlAlertRouter.Decision d = router.guard(
                "{\"receiver\":\"rca-oncall\",\"monitoring_scope_label_like\":\"x\""
                        .getBytes(StandardCharsets.UTF_8),
                false, "Bearer " + BUSINESS_BEARER);

        assertThat(d.outcome()).isEqualTo(ControlAlertRouter.Outcome.PASS_THROUGH);
        assertThat(stores.inbox.all()).isEmpty();
    }

    private static byte[] gzip(String raw) throws Exception {
        java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
        try (java.util.zip.GZIPOutputStream out = new java.util.zip.GZIPOutputStream(bos)) {
            out.write(raw.getBytes(StandardCharsets.UTF_8));
        }
        return bos.toByteArray();
    }
}
