package com.objwww.pr.control.drill.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.objwww.pr.control.drill.domain.model.DrillJob;
import com.objwww.pr.control.drill.domain.model.FlagdRestoreRecord;
import com.objwww.pr.control.drill.domain.model.FlagdState;
import com.objwww.pr.control.drill.domain.repository.FlagdRestoreLedger;
import com.objwww.pr.control.eval.application.AlertProbe;
import com.objwww.pr.control.eval.application.ArenaChaosScenarioDriver;
import com.objwww.pr.control.eval.application.ArenaTrafficClient;
import com.objwww.pr.control.eval.application.ChaosAdminClient;
import com.objwww.pr.control.eval.application.FlagdScenarioDriver;
import com.objwww.pr.control.eval.domain.GoldenScenarioRegistry;
import com.objwww.pr.shared.Digest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * DR-03 复合注入端口（§7.5 DR-03 卡）：arena 模板 PERFORMED 链（完整 recipe +
 * 回执稳定身份）、NOT_PERFORMED 各卡因（环境白名单/模板未 ready/场景未登记/缺
 * injection 参数/token fail-closed/4xx 拒绝/driver 漂移/未知驱动）、UNKNOWN
 * 链（on 超时/5xx 无法判定，reason 带固定身份对账锚）、W1 中断与流量失败路径
 * （ActivationException = 会话已激活 → PERFORMED 不丢现场）、复合路由按 kind
 * 分派不错串。假件全内存，不打真网（真栈行为归 195 门）。
 */
class CompositeDrillInjectionTest {

    private static final Instant BASE = Instant.parse("2026-09-11T00:00:00Z");
    private static final List<String> ENVS = List.of("arena-195");
    private static final ObjectMapper JSON = new ObjectMapper();

    // ------------------------------------------------------------------ 假件

    private static final class FakeChaosAdminClient implements ChaosAdminClient {
        int onCalls;
        Map<String, Object> lastOnBody;
        RuntimeException failOn;

        @Override
        public Activation activate(String faultType, Map<String, Object> body) {
            if (failOn != null) {
                throw failOn;
            }
            onCalls++;
            lastOnBody = body;
            return new Activation("sess-fixed",
                    String.valueOf(body.get("scenarioId")), 7L, "fp-fixed");
        }

        @Override
        public boolean deactivate(String faultType, Map<String, Object> body) {
            throw new UnsupportedOperationException();
        }

        @Override
        public SessionStatus status(String scenarioId) {
            throw new UnsupportedOperationException();
        }
    }

    private static final class FakeAlertProbe implements AlertProbe {
        @Override
        public boolean awaitAllFiring(String scenarioId, int maxWaitSeconds) {
            return true;
        }

        @Override
        public boolean awaitAllResolved(String scenarioId, int maxWaitSeconds) {
            return true;
        }

        @Override
        public boolean awaitSessionClosed(String scenarioId, int cleanupTimeoutSeconds) {
            return true;
        }

        @Override
        public Digest ruleDigest(String alertname) {
            return Digest.sha256Of("rule=" + alertname);
        }
    }

    private static final class FakeTraffic implements ArenaTrafficClient {
        final List<String> orders = new ArrayList<>();
        int failOnCall = -1; // 1-based；-1 = 不失败

        @Override
        public void createOrder(String intentId, String correlationId, String sku) {
            orders.add(intentId + "/" + correlationId);
            if (orders.size() == failOnCall) {
                throw new ResourceAccessException("arena 创单超时");
            }
        }
    }

    private static final class FakeFlagAdminClient
            implements FlagdScenarioDriver.FlagAdminClient {
        final Map<String, String> variants = new LinkedHashMap<>();
        final List<String> writes = new ArrayList<>();
        RuntimeException failOnWrite;
        int generation;

        @Override
        public String setDefaultVariant(String flag, String variant) {
            if (failOnWrite != null) {
                throw failOnWrite;
            }
            variants.put(flag, variant);
            generation++;
            writes.add(flag + "=" + variant);
            return variant;
        }

        @Override
        public FlagdState readDefaultVariant(String flag) {
            return new FlagdState(variants.get(flag),
                    generation == 0 ? null : "g" + generation);
        }
    }

    private static final class InMemoryLedger implements FlagdRestoreLedger {
        final Map<UUID, FlagdRestoreRecord> byId = new LinkedHashMap<>();

        @Override
        public void recordActivation(FlagdRestoreRecord record) {
            byId.put(record.id(), record);
        }

        @Override
        public Optional<FlagdRestoreRecord> findRestorableByFlag(String flag) {
            return byId.values().stream()
                    .filter(r -> r.flag().equals(flag) && r.state().isRestorable())
                    .reduce((a, b) -> b);
        }

        @Override
        public boolean close(UUID id, FlagdRestoreRecord.State to, String reason,
                             Instant now) {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<FlagdRestoreRecord> findRestorablePastDeadline(Instant now) {
            throw new UnsupportedOperationException();
        }
    }

    // ------------------------------------------------------------------ 装配

    private FakeChaosAdminClient chaos;
    private FakeTraffic traffic;
    private FakeFlagAdminClient flagClient;
    private InMemoryLedger ledger;
    private ArenaChaosDrillInjection arena;
    private FlagdDrillInjection flagd;
    private CompositeDrillInjection composite;

    @BeforeEach
    void setUp() {
        chaos = new FakeChaosAdminClient();
        traffic = new FakeTraffic();
        flagClient = new FakeFlagAdminClient();
        ledger = new InMemoryLedger();
        arena = new ArenaChaosDrillInjection(chaos, new FakeAlertProbe(), traffic,
                "ds-drill");
        flagd = new FlagdDrillInjection(new FlagdScenarioDriver(flagClient,
                new FakeAlertProbe(), ledger, Clock.fixed(BASE, ZoneOffset.UTC)));
        composite = new CompositeDrillInjection(catalog(), registry(), ENVS,
                arena, flagd);
    }

    private static DrillTemplateCatalog catalog() {
        return DrillTemplateCatalog.load("""
                registry_version: 2
                templates:
                  - scenario_id: S3
                    name: F1 幂等失效
                    driver: ArenaChaosScenarioDriver
                    chaos_family: F1
                    target: order-arena
                    symptom_codes: [ArenaDuplicateOrders]
                    timing: {preheat_seconds: 1, hold_seconds: 2,
                             max_firing_wait_seconds: 3,
                             max_resolved_wait_seconds: 4,
                             cleanup_timeout_seconds: 5}
                    params:
                      duration_seconds: {default: 600, min: 60, max: 600}
                      traffic_scales: [RECIPE]
                      linked_eval_version_allowed: true
                    execution:
                      ready: true
                  - scenario_id: S1
                    name: paymentFailure=50%
                    driver: FlagdScenarioDriver
                    target: payment
                    symptom_codes: [checkout]
                    timing: {preheat_seconds: 1, hold_seconds: 2,
                             max_firing_wait_seconds: 3,
                             max_resolved_wait_seconds: 4,
                             cleanup_timeout_seconds: 5}
                    params:
                      duration_seconds: {default: 600, min: 60, max: 600}
                      traffic_scales: [RECIPE]
                      linked_eval_version_allowed: true
                    execution:
                      ready: true
                  - scenario_id: S2
                    name: flagd paymentUnreachable
                    driver: FlagdScenarioDriver
                    target: payment
                    symptom_codes: [checkout]
                    timing: {preheat_seconds: 1, hold_seconds: 2,
                             max_firing_wait_seconds: 3,
                             max_resolved_wait_seconds: 4,
                             cleanup_timeout_seconds: 5}
                    params:
                      duration_seconds: {default: 600, min: 60, max: 600}
                      traffic_scales: [RECIPE]
                      linked_eval_version_allowed: true
                    execution:
                      ready: false
                      reason: DR-05 Flagd 恢复所有权未达标，不开放启动
                  - scenario_id: S9
                    name: flagd 缺参数场景
                    driver: FlagdScenarioDriver
                    target: payment
                    symptom_codes: [checkout]
                    timing: {preheat_seconds: 1, hold_seconds: 2,
                             max_firing_wait_seconds: 3,
                             max_resolved_wait_seconds: 4,
                             cleanup_timeout_seconds: 5}
                    params:
                      duration_seconds: {default: 600, min: 60, max: 600}
                      traffic_scales: [RECIPE]
                      linked_eval_version_allowed: true
                    execution:
                      ready: true
                  - scenario_id: T9
                    name: 未知驱动场景
                    driver: MysteryDriver
                    target: order-arena
                    symptom_codes: [X]
                    timing: {preheat_seconds: 1, hold_seconds: 2,
                             max_firing_wait_seconds: 3,
                             max_resolved_wait_seconds: 4,
                             cleanup_timeout_seconds: 5}
                    params:
                      duration_seconds: {default: 600, min: 60, max: 600}
                      traffic_scales: [RECIPE]
                      linked_eval_version_allowed: true
                    execution:
                      ready: true
                """);
    }

    private static GoldenScenarioRegistry registry() {
        return GoldenScenarioRegistry.load("""
                registry_version: 2
                schema_version: 1
                scenarios:
                  - scenario_id: S3
                    name: F1 幂等失效
                    driver: ArenaChaosScenarioDriver
                    chaos_family: F1
                    target: order-arena
                    expected_root_cause: {component: order-arena,
                                          fault_type: IDEMPOTENCY,
                                          reason_code: IDEM_X}
                    expected_symptom_codes: [ArenaDuplicateOrders]
                    timing: {preheat_seconds: 1, hold_seconds: 2,
                             max_firing_wait_seconds: 3,
                             max_resolved_wait_seconds: 4,
                             cleanup_timeout_seconds: 5}
                  - scenario_id: S1
                    name: paymentFailure=50%
                    driver: FlagdScenarioDriver
                    target: payment
                    expected_root_cause: {component: checkout,
                                          fault_type: DEPENDENCY,
                                          reason_code: PAY_50}
                    expected_symptom_codes: [checkout]
                    injection: {flag: paymentFailure, variant: "50%",
                                baseline_variant: "off"}
                    timing: {preheat_seconds: 1, hold_seconds: 2,
                             max_firing_wait_seconds: 3,
                             max_resolved_wait_seconds: 4,
                             cleanup_timeout_seconds: 5}
                  - scenario_id: S2
                    name: flagd paymentUnreachable
                    driver: FlagdScenarioDriver
                    target: payment
                    expected_root_cause: {component: checkout,
                                          fault_type: DEPENDENCY,
                                          reason_code: PAY_DOWN}
                    expected_symptom_codes: [checkout]
                    injection: {flag: paymentUnreachable, variant: "true",
                                baseline_variant: "false"}
                    timing: {preheat_seconds: 1, hold_seconds: 2,
                             max_firing_wait_seconds: 3,
                             max_resolved_wait_seconds: 4,
                             cleanup_timeout_seconds: 5}
                  - scenario_id: S9
                    name: flagd 缺参数场景
                    driver: FlagdScenarioDriver
                    target: payment
                    expected_root_cause: {component: checkout,
                                          fault_type: DEPENDENCY,
                                          reason_code: PAY_X}
                    expected_symptom_codes: [checkout]
                    timing: {preheat_seconds: 1, hold_seconds: 2,
                             max_firing_wait_seconds: 3,
                             max_resolved_wait_seconds: 4,
                             cleanup_timeout_seconds: 5}
                  - scenario_id: T9
                    name: 未知驱动场景
                    driver: MysteryDriver
                    target: order-arena
                    expected_root_cause: {component: order-arena,
                                          fault_type: X, reason_code: X}
                    expected_symptom_codes: [X]
                    timing: {preheat_seconds: 1, hold_seconds: 2,
                             max_firing_wait_seconds: 3,
                             max_resolved_wait_seconds: 4,
                             cleanup_timeout_seconds: 5}
                """);
    }

    private static DrillJob job(String scenarioId, String env) {
        return DrillJob.queued(UUID.randomUUID(), scenarioId, scenarioId + " 场景",
                "0".repeat(64), env, "operator", "{}", "1".repeat(64),
                "key-" + UUID.randomUUID(), BASE);
    }

    /** arena 固定身份 = chaos-eval-d{drillId hex}-{sid}-r1（DU07 对账锚） */
    private static String fixedId(DrillJob job, String scenarioId) {
        return ArenaChaosScenarioDriver.effectiveScenarioId(
                registry().byScenarioId(scenarioId), 1,
                ArenaChaosDrillInjection.runTagFor(job));
    }

    // ------------------------------------------------------------------ arena 链

    @Test
    @DisplayName("arena 模板 PERFORMED 链：完整 recipe（on→settle→F1 三单流量），"
            + "回执含稳定身份（scenarioId/generation/actionDigest/drillId）")
    void arenaPerformedChain() throws Exception {
        DrillJob job = job("S3", "arena-195");
        DrillInjectionPort.Outcome outcome = composite.inject(job);
        assertThat(outcome.kind()).isEqualTo(DrillInjectionPort.Kind.PERFORMED);
        String fixedId = fixedId(job, "S3");
        JsonNode receipt = JSON.readTree(outcome.receiptJson());
        assertThat(receipt.path("scenarioId").asText()).isEqualTo(fixedId);
        assertThat(receipt.path("generation").asLong()).isEqualTo(7L);
        assertThat(receipt.path("actionDigest").asText()).isNotBlank();
        assertThat(receipt.path("drillId").asText()).isEqualTo(job.id().toString());
        assertThat(receipt.path("driver").asText())
                .isEqualTo("ArenaChaosScenarioDriver");
        assertThat(receipt.has("trafficNote")).isFalse();
        assertThat(chaos.onCalls).isEqualTo(1);
        assertThat(chaos.lastOnBody.get("scenarioId")).isEqualTo(fixedId);
        assertThat(traffic.orders).hasSize(3); // F1 = 同 intent 三单
        assertThat(flagClient.writes).isEmpty(); // 不串 flagd 面
    }

    @Test
    @DisplayName("arena on 超时（响应丢失）→ UNKNOWN，reason 带固定身份对账锚，零流量")
    void arenaOnTimeoutUnknown() {
        DrillJob job = job("S3", "arena-195");
        chaos.failOn = new ResourceAccessException("read timed out");
        DrillInjectionPort.Outcome outcome = composite.inject(job);
        assertThat(outcome.kind()).isEqualTo(DrillInjectionPort.Kind.UNKNOWN);
        assertThat(outcome.reason()).contains("ACTION_UNKNOWN")
                .contains(fixedId(job, "S3"));
        assertThat(outcome.receiptJson()).isNull();
        assertThat(traffic.orders).isEmpty(); // on 未回执，无流量阶段
        assertThat(chaos.onCalls).isZero();
    }

    @Test
    @DisplayName("arena on 5xx → UNKNOWN（服务器可能已应用，无法判定，不吞成零副作用）")
    void arenaOn5xxUnknown() {
        DrillJob job = job("S3", "arena-195");
        chaos.failOn = new HttpServerErrorException(HttpStatus.BAD_GATEWAY);
        DrillInjectionPort.Outcome outcome = composite.inject(job);
        assertThat(outcome.kind()).isEqualTo(DrillInjectionPort.Kind.UNKNOWN);
        assertThat(outcome.reason()).contains(fixedId(job, "S3"));
        assertThat(traffic.orders).isEmpty();
    }

    @Test
    @DisplayName("arena on 4xx（管理面明确拒绝）→ NOT_PERFORMED 确定零副作用")
    void arenaOn4xxNotPerformed() {
        DrillJob job = job("S3", "arena-195");
        chaos.failOn = new HttpClientErrorException(HttpStatus.BAD_REQUEST);
        DrillInjectionPort.Outcome outcome = composite.inject(job);
        assertThat(outcome.kind()).isEqualTo(DrillInjectionPort.Kind.NOT_PERFORMED);
        assertThat(outcome.reason()).contains("400").contains("确定零副作用");
        assertThat(traffic.orders).isEmpty();
    }

    @Test
    @DisplayName("CHAOS_ADMIN_TOKEN fail-closed（未发请求的前置拒绝）→ NOT_PERFORMED")
    void arenaTokenFailClosedNotPerformed() {
        DrillJob job = job("S3", "arena-195");
        chaos.failOn = new IllegalStateException(
                "CHAOS_ADMIN_TOKEN 未注入（INV-AM3-3 fail-closed），拒绝注入");
        DrillInjectionPort.Outcome outcome = composite.inject(job);
        assertThat(outcome.kind()).isEqualTo(DrillInjectionPort.Kind.NOT_PERFORMED);
        assertThat(outcome.reason()).contains("前置校验").contains("CHAOS_ADMIN_TOKEN");
        assertThat(traffic.orders).isEmpty();
    }

    @Test
    @DisplayName("arena 流量阶段失败（会话已激活）→ PERFORMED 不丢现场：回执同身份 + "
            + "trafficNote 如实记录（DU08）")
    void arenaTrafficFailurePerformedWithNote() throws Exception {
        DrillJob job = job("S3", "arena-195");
        traffic.failOnCall = 2; // F1 三单中第二单失败
        DrillInjectionPort.Outcome outcome = composite.inject(job);
        assertThat(outcome.kind()).isEqualTo(DrillInjectionPort.Kind.PERFORMED);
        JsonNode receipt = JSON.readTree(outcome.receiptJson());
        assertThat(receipt.path("scenarioId").asText()).isEqualTo(fixedId(job, "S3"));
        assertThat(receipt.path("trafficNote").asText()).contains("流量注入失败");
        assertThat(traffic.orders).hasSize(2); // 已发订单可追溯
        assertThat(chaos.onCalls).isEqualTo(1);
    }

    @Test
    @DisplayName("settle 等待被中断（W1 路径）→ 零流量 + 回执身份随异常移交 → "
            + "PERFORMED 带中断 trafficNote，不丢已激活会话")
    void arenaInterruptDuringSettlePerformed() throws Exception {
        DrillJob job = job("S3", "arena-195");
        DrillInjectionPort.Outcome outcome;
        Thread.currentThread().interrupt();
        try {
            outcome = composite.inject(job);
            assertThat(Thread.currentThread().isInterrupted()).isTrue(); // 中断标记保留
        } finally {
            Thread.interrupted(); // 清中断标记，不污染同线程后续测试
        }
        assertThat(outcome.kind()).isEqualTo(DrillInjectionPort.Kind.PERFORMED);
        JsonNode receipt = JSON.readTree(outcome.receiptJson());
        assertThat(receipt.path("scenarioId").asText()).isEqualTo(fixedId(job, "S3"));
        assertThat(receipt.path("trafficNote").asText()).contains("中断");
        assertThat(traffic.orders).isEmpty(); // 中断后零流量
        assertThat(chaos.onCalls).isEqualTo(1); // 会话已在管理面激活
    }

    // ------------------------------------------------------------------ 闸门卡因

    @Test
    @DisplayName("目标环境不在部署白名单 → NOT_PERFORMED（DU04 篡改面拒绝），管理面零调用")
    void envNotWhitelistedNotPerformed() {
        DrillInjectionPort.Outcome outcome = composite.inject(job("S3", "arena-999"));
        assertThat(outcome.kind()).isEqualTo(DrillInjectionPort.Kind.NOT_PERFORMED);
        assertThat(outcome.reason()).contains("TARGET_ENV_WHITELIST")
                .contains("arena-999");
        assertThat(chaos.onCalls).isZero();
        assertThat(flagClient.writes).isEmpty();
    }

    @Test
    @DisplayName("flagd 模板 ready=false → 如实 NOT_PERFORMED 带模板原因，不绕过、不写 flag")
    void flagdNotReadyRefusedHonestly() {
        DrillInjectionPort.Outcome outcome = composite.inject(job("S2", "arena-195"));
        assertThat(outcome.kind()).isEqualTo(DrillInjectionPort.Kind.NOT_PERFORMED);
        assertThat(outcome.reason()).contains("EXECUTION_READY")
                .contains("DR-05 Flagd 恢复所有权未达标");
        assertThat(flagClient.writes).isEmpty();
        assertThat(chaos.onCalls).isZero();
    }

    @Test
    @DisplayName("场景未登记于模板目录 → NOT_PERFORMED，管理面零调用")
    void scenarioUnknownNotPerformed() {
        DrillInjectionPort.Outcome outcome = composite.inject(job("GX", "arena-195"));
        assertThat(outcome.kind()).isEqualTo(DrillInjectionPort.Kind.NOT_PERFORMED);
        assertThat(outcome.reason()).contains("SCENARIO_KNOWN").contains("GX");
        assertThat(chaos.onCalls).isZero();
        assertThat(flagClient.writes).isEmpty();
    }

    @Test
    @DisplayName("目录与注册表 driver 漂移（配置缺陷）→ NOT_PERFORMED 拒绝执行不猜")
    void driverDriftNotPerformed() {
        DrillTemplateCatalog driftCatalog = DrillTemplateCatalog.load("""
                registry_version: 2
                templates:
                  - scenario_id: D1
                    name: 漂移场景
                    driver: ArenaChaosScenarioDriver
                    timing: {preheat_seconds: 1, hold_seconds: 2,
                             max_firing_wait_seconds: 3,
                             max_resolved_wait_seconds: 4,
                             cleanup_timeout_seconds: 5}
                    params:
                      duration_seconds: {default: 600, min: 60, max: 600}
                      traffic_scales: [RECIPE]
                      linked_eval_version_allowed: true
                    execution:
                      ready: true
                """);
        GoldenScenarioRegistry driftRegistry = GoldenScenarioRegistry.load("""
                registry_version: 2
                schema_version: 1
                scenarios:
                  - scenario_id: D1
                    name: 漂移场景
                    driver: FlagdScenarioDriver
                    expected_root_cause: {component: c, fault_type: f,
                                          reason_code: r}
                    timing: {preheat_seconds: 1, hold_seconds: 2,
                             max_firing_wait_seconds: 3,
                             max_resolved_wait_seconds: 4,
                             cleanup_timeout_seconds: 5}
                """);
        CompositeDrillInjection c = new CompositeDrillInjection(driftCatalog,
                driftRegistry, ENVS, arena, flagd);
        DrillInjectionPort.Outcome outcome = c.inject(job("D1", "arena-195"));
        assertThat(outcome.kind()).isEqualTo(DrillInjectionPort.Kind.NOT_PERFORMED);
        assertThat(outcome.reason()).contains("漂移");
        assertThat(chaos.onCalls).isZero();
        assertThat(flagClient.writes).isEmpty();
    }

    // ------------------------------------------------------------------ flagd 链

    @Test
    @DisplayName("flagd 模板 PERFORMED 链：defaultVariant 单键切换 + 恢复台账 OPEN 落账"
            + "（DR-05 面），回执身份 = 模板 scenarioId")
    void flagdPerformedChain() throws Exception {
        DrillJob job = job("S1", "arena-195");
        DrillInjectionPort.Outcome outcome = composite.inject(job);
        assertThat(outcome.kind()).isEqualTo(DrillInjectionPort.Kind.PERFORMED);
        assertThat(flagClient.writes).containsExactly("paymentFailure=50%");
        JsonNode receipt = JSON.readTree(outcome.receiptJson());
        assertThat(receipt.path("scenarioId").asText()).isEqualTo("S1");
        assertThat(receipt.path("driver").asText()).isEqualTo("FlagdScenarioDriver");
        assertThat(receipt.path("drillId").asText()).isEqualTo(job.id().toString());
        assertThat(chaos.onCalls).isZero(); // 不串 arena 面
        // DR-05 台账：写入值/原值/代际/截止已落 OPEN（条件恢复的可恢复面）
        assertThat(ledger.byId.values()).singleElement().satisfies(r -> {
            assertThat(r.flag()).isEqualTo("paymentFailure");
            assertThat(r.appliedVariant()).isEqualTo("50%");
            assertThat(r.baselineVariant()).isEqualTo("off");
            assertThat(r.state()).isEqualTo(FlagdRestoreRecord.State.OPEN);
            assertThat(r.deadlineAt()).isAfter(BASE);
        });
    }

    @Test
    @DisplayName("flagd 写入超时（响应丢失）→ UNKNOWN 不吞，对账锚 = scenarioId/flag")
    void flagdWriteTimeoutUnknown() {
        DrillJob job = job("S1", "arena-195");
        flagClient.failOnWrite = new ResourceAccessException("connect timed out");
        DrillInjectionPort.Outcome outcome = composite.inject(job);
        assertThat(outcome.kind()).isEqualTo(DrillInjectionPort.Kind.UNKNOWN);
        assertThat(outcome.reason()).contains("ACTION_UNKNOWN")
                .contains("S1/flag=paymentFailure");
        assertThat(flagClient.writes).isEmpty(); // 传输面失败，写入未确认
        assertThat(ledger.byId).isEmpty(); // 写未确认不落台账（不编造原值面）
    }

    @Test
    @DisplayName("flagd 场景缺 injection 参数 → NOT_PERFORMED（任何写入之前拒绝）")
    void flagdMissingInjectionNotPerformed() {
        DrillInjectionPort.Outcome outcome = composite.inject(job("S9", "arena-195"));
        assertThat(outcome.kind()).isEqualTo(DrillInjectionPort.Kind.NOT_PERFORMED);
        assertThat(outcome.reason()).contains("INJECTION_PARAMS").contains("S9");
        assertThat(flagClient.writes).isEmpty();
    }

    @Test
    @DisplayName("复合路由：未知 driver → NOT_PERFORMED，两个管理面都零调用")
    void unknownDriverNotPerformed() {
        DrillInjectionPort.Outcome outcome = composite.inject(job("T9", "arena-195"));
        assertThat(outcome.kind()).isEqualTo(DrillInjectionPort.Kind.NOT_PERFORMED);
        assertThat(outcome.reason()).contains("DRIVER_KNOWN").contains("MysteryDriver");
        assertThat(chaos.onCalls).isZero();
        assertThat(flagClient.writes).isEmpty();
    }
}
