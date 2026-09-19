package com.objwww.pr.control.drill.application;

import com.objwww.pr.control.drill.domain.model.DrillJob;
import com.objwww.pr.control.drill.domain.model.FlagdRestoreRecord;
import com.objwww.pr.control.drill.domain.model.FlagdState;
import com.objwww.pr.control.drill.domain.repository.FlagdRestoreLedger;
import com.objwww.pr.control.eval.application.AlertProbe;
import com.objwww.pr.control.eval.application.ChaosAdminClient;
import com.objwww.pr.control.eval.domain.GoldenScenarioRegistry;
import com.objwww.pr.shared.Digest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.HttpClientErrorException;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * DR-04 复合恢复/核验端口（§7.5 DR-04 卡）：按模板 driver 分派——flagd 面复用
 * V95 台账协议条件恢复（RESTORED→RECOVERED / CONFLICT→FAILED 不覆盖不重试 /
 * UNKNOWN→下拍重试且台账保持可恢复面）；arena 面凭注入同式的固定身份查会话
 * （ACTIVE→off CAS / RECOVERING、CLOSED、404→RECOVERED 幂等收口 / CAS 竞争、
 * 传输失败→UNKNOWN）；核验 = 模板症状码全部 resolved（resolved→VERIFIED /
 * 残留 firing、探针异常→PENDING）；配置缺陷闸门一律 FAILED（确定不可恢复）。
 */
class CompositeDrillRecoveryTest {

    private static final Instant BASE = Instant.parse("2026-09-11T00:00:00Z");

    // ------------------------------------------------------------------ 假件

    private static final class StubFlagdPort implements FlagdAdminPort {
        final Map<String, String> variants = new LinkedHashMap<>();
        final Map<String, Integer> generations = new LinkedHashMap<>();
        final List<String> writes = new ArrayList<>();
        boolean failRead;

        void seed(String flag, String variant, int generation) {
            variants.put(flag, variant);
            generations.put(flag, generation);
        }

        @Override
        public FlagdState read(String flag) {
            if (failRead) {
                throw new IllegalStateException("flagd-admin 不可达");
            }
            return new FlagdState(variants.get(flag),
                    generations.containsKey(flag) ? "g" + generations.get(flag) : null);
        }

        @Override
        public String write(String flag, String variant) {
            variants.put(flag, variant);
            generations.merge(flag, 1, Integer::sum);
            writes.add(flag + "=" + variant);
            return variant;
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
            FlagdRestoreRecord r = byId.get(id);
            if (r == null || !r.state().isRestorable()) {
                return false;
            }
            byId.put(id, new FlagdRestoreRecord(r.id(), r.flag(), r.scenarioId(),
                    r.roundNo(), r.originalVariant(), r.originalGeneration(),
                    r.appliedVariant(), r.appliedGeneration(), r.baselineVariant(),
                    r.deadlineAt(), to, reason, r.createdAt(), now));
            return true;
        }

        @Override
        public List<FlagdRestoreRecord> findRestorablePastDeadline(Instant now) {
            return List.of();
        }
    }

    /** chaos 管理面桩：status 可摆 ACTIVE/CLOSED/404/传输异常；deactivate 记录调用体 */
    private static final class StubChaosAdmin implements ChaosAdminClient {
        String sessionState = "ACTIVE";
        long generation = 7;
        boolean statusNotFound;
        boolean failStatus;
        boolean deactivateAccepted = true;
        Map<String, Object> lastDeactivateBody;

        @Override
        public Activation activate(String faultType, Map<String, Object> body) {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean deactivate(String faultType, Map<String, Object> body) {
            lastDeactivateBody = body;
            return deactivateAccepted;
        }

        @Override
        public SessionStatus status(String scenarioId) {
            if (failStatus) {
                throw new IllegalStateException("chaos-admin 不可达");
            }
            if (statusNotFound) {
                throw HttpClientErrorException.create(HttpStatus.NOT_FOUND, "未知场景",
                        HttpHeaders.EMPTY, new byte[0], null);
            }
            return new SessionStatus(sessionState, generation);
        }
    }

    private static final class StubProbe implements AlertProbe {
        Boolean resolved = true;
        boolean failProbe;

        @Override
        public boolean awaitAllFiring(String scenarioId, int maxWaitSeconds) {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean awaitAllResolved(String scenarioId, int maxWaitSeconds) {
            if (failProbe) {
                throw new IllegalStateException("prometheus 不可达");
            }
            return resolved;
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

    private static final String REGISTRY_YAML = """
            registry_version: 2
            schema_version: 1
            scenarios:
              - scenario_id: S1
                name: paymentFailure=50%
                driver: FlagdScenarioDriver
                target: payment
                expected_root_cause: {component: payment, fault_type: FLAG,
                                      reason_code: FLAG_X}
                expected_symptom_codes: [checkout]
                injection: {flag: paymentFailure, variant: "50%",
                            baseline_variant: "off"}
                timing: {preheat_seconds: 60, hold_seconds: 600,
                         max_firing_wait_seconds: 1500,
                         max_resolved_wait_seconds: 2100,
                         cleanup_timeout_seconds: 120}
              - scenario_id: S3
                name: F1 幂等失效
                driver: ArenaChaosScenarioDriver
                chaos_family: F1
                target: order-arena
                expected_root_cause: {component: order-arena,
                                      fault_type: IDEMPOTENCY,
                                      reason_code: IDEM_X}
                expected_symptom_codes: [ArenaDuplicateOrders]
                timing: {preheat_seconds: 60, hold_seconds: 600,
                         max_firing_wait_seconds: 300,
                         max_resolved_wait_seconds: 600,
                         cleanup_timeout_seconds: 120}
            """;

    private static DrillTemplateCatalog catalog() {
        return DrillTemplateCatalog.load("""
                registry_version: 2
                templates:
                  - scenario_id: S1
                    name: paymentFailure=50%
                    driver: FlagdScenarioDriver
                    symptom_codes: [checkout]
                    timing: {preheat_seconds: 60, hold_seconds: 600,
                             max_firing_wait_seconds: 1500,
                             max_resolved_wait_seconds: 2100,
                             cleanup_timeout_seconds: 120}
                    params:
                      duration_seconds: {default: 600, min: 60, max: 600}
                      traffic_scales: [RECIPE]
                      linked_eval_version_allowed: true
                    execution:
                      ready: true
                  - scenario_id: S3
                    name: F1 幂等失效
                    driver: ArenaChaosScenarioDriver
                    chaos_family: F1
                    symptom_codes: [ArenaDuplicateOrders]
                    timing: {preheat_seconds: 60, hold_seconds: 600,
                             max_firing_wait_seconds: 300,
                             max_resolved_wait_seconds: 600,
                             cleanup_timeout_seconds: 120}
                    params:
                      duration_seconds: {default: 600, min: 60, max: 600}
                      traffic_scales: [RECIPE]
                      linked_eval_version_allowed: true
                    execution:
                      ready: true
                """);
    }

    private StubFlagdPort flagdPort;
    private InMemoryLedger ledger;
    private StubChaosAdmin chaos;
    private StubProbe probe;
    private CompositeDrillRecovery recovery;

    @BeforeEach
    void setUp() {
        flagdPort = new StubFlagdPort();
        ledger = new InMemoryLedger();
        chaos = new StubChaosAdmin();
        probe = new StubProbe();
        recovery = new CompositeDrillRecovery(catalog(),
                GoldenScenarioRegistry.load(REGISTRY_YAML),
                new ArenaChaosDrillRecovery(chaos),
                new FlagdDrillRecovery(flagdPort, ledger, () -> BASE), probe);
    }

    private static DrillJob job(String scenarioId) {
        return new DrillJob(UUID.randomUUID(), scenarioId, scenarioId + " 场景",
                "0".repeat(64), "arena-195", "operator", DrillJob.State.RECOVERING,
                null, null, "{}", "1".repeat(64), "key-" + UUID.randomUUID(),
                null, null, "drill-worker-1", BASE, 0, null, null, BASE, BASE, null);
    }

    private FlagdRestoreRecord ledgerRecord() {
        FlagdRestoreRecord r = new FlagdRestoreRecord(UUID.randomUUID(),
                "paymentFailure", "S1", 1, "off", "g1", "50%", "g2", "off",
                BASE.plusSeconds(4380), FlagdRestoreRecord.State.OPEN, null, BASE, BASE);
        ledger.recordActivation(r);
        return r;
    }

    // ------------------------------------------------------------------ flagd 面

    @Test
    @DisplayName("flagd 恢复：当前值仍属本次写入 → 条件写回实际原值 RECOVERED，"
            + "台账收口 RESTORED")
    void flagdRestoredWhenValueOwned() {
        flagdPort.seed("paymentFailure", "50%", 2); // 对齐台账写入值/代际 g2
        FlagdRestoreRecord r = ledgerRecord();
        DrillRecoveryPort.RecoverOutcome outcome = recovery.recover(job("S1"));
        assertThat(outcome.kind()).isEqualTo(DrillRecoveryPort.RecoverKind.RECOVERED);
        assertThat(flagdPort.writes).containsExactly("paymentFailure=off");
        assertThat(ledger.byId.get(r.id()).state())
                .isEqualTo(FlagdRestoreRecord.State.RESTORED);
    }

    @Test
    @DisplayName("flagd 恢复：他者已改写 → FAILED（CONFLICT 不覆盖、不重试），"
            + "台账收口 CONFLICT，现值保留")
    void flagdConflictFailsHonestly() {
        flagdPort.seed("paymentFailure", "90%", 9); // 他者改写
        FlagdRestoreRecord r = ledgerRecord();
        DrillRecoveryPort.RecoverOutcome outcome = recovery.recover(job("S1"));
        assertThat(outcome.kind()).isEqualTo(DrillRecoveryPort.RecoverKind.FAILED);
        assertThat(outcome.reason()).contains("flag_restore_conflict");
        assertThat(flagdPort.writes).isEmpty();
        assertThat(flagdPort.variants.get("paymentFailure")).isEqualTo("90%");
        assertThat(ledger.byId.get(r.id()).state())
                .isEqualTo(FlagdRestoreRecord.State.CONFLICT);
    }

    @Test
    @DisplayName("flagd 恢复：他者已写回到恢复目标值 → RECOVERED 幂等收口（不重复写），"
            + "台账收口 RESTORED")
    void flagdAlreadyAtTargetRecoveredIdempotent() {
        flagdPort.seed("paymentFailure", "off", 9); // 他者已写回目标值（非本次代际）
        FlagdRestoreRecord r = ledgerRecord();
        DrillRecoveryPort.RecoverOutcome outcome = recovery.recover(job("S1"));
        assertThat(outcome.kind()).isEqualTo(DrillRecoveryPort.RecoverKind.RECOVERED);
        assertThat(outcome.reason()).contains("幂等收口");
        assertThat(flagdPort.writes).isEmpty();
        assertThat(ledger.byId.get(r.id()).state())
                .isEqualTo(FlagdRestoreRecord.State.RESTORED);
    }

    @Test
    @DisplayName("flagd 恢复：读不到当前值 → UNKNOWN 不盲写，台账保持可恢复面下拍重试")
    void flagdUnknownStaysRestorable() {
        flagdPort.failRead = true;
        FlagdRestoreRecord r = ledgerRecord();
        DrillRecoveryPort.RecoverOutcome outcome = recovery.recover(job("S1"));
        assertThat(outcome.kind()).isEqualTo(DrillRecoveryPort.RecoverKind.UNKNOWN);
        assertThat(flagdPort.writes).isEmpty();
        assertThat(ledger.byId.get(r.id()).state().isRestorable()).isTrue();
    }

    // ------------------------------------------------------------------ arena 面

    @Test
    @DisplayName("arena 恢复：会话 ACTIVE → off CAS（固定身份 + 实时代际）受理 = RECOVERED")
    void arenaActiveSessionDeactivated() {
        DrillJob job = job("S3");
        DrillRecoveryPort.RecoverOutcome outcome = recovery.recover(job);
        assertThat(outcome.kind()).isEqualTo(DrillRecoveryPort.RecoverKind.RECOVERED);
        String sid = "chaos-eval-d" + job.id().toString().replace("-", "") + "-s3-r1";
        assertThat(chaos.lastDeactivateBody).isNotNull();
        assertThat(chaos.lastDeactivateBody.get("scenarioId")).isEqualTo(sid);
        assertThat(chaos.lastDeactivateBody.get("expectedGeneration")).isEqualTo(7L);
    }

    @Test
    @DisplayName("arena 恢复：off CAS 未中（代际竞争）→ UNKNOWN 下拍重试；"
            + "会话 CLOSED / RECOVERING / 404 不存在 → RECOVERED 幂等收口")
    void arenaIdempotentAndRetryable() {
        chaos.deactivateAccepted = false;
        DrillRecoveryPort.RecoverOutcome casMiss = recovery.recover(job("S3"));
        assertThat(casMiss.kind()).isEqualTo(DrillRecoveryPort.RecoverKind.UNKNOWN);
        assertThat(casMiss.reason()).contains("chaos_cas_rejected");

        chaos.sessionState = "CLOSED";
        assertThat(recovery.recover(job("S3")).kind())
                .isEqualTo(DrillRecoveryPort.RecoverKind.RECOVERED);
        chaos.sessionState = "RECOVERING";
        assertThat(recovery.recover(job("S3")).kind())
                .isEqualTo(DrillRecoveryPort.RecoverKind.RECOVERED);

        chaos.statusNotFound = true;
        DrillRecoveryPort.RecoverOutcome absent = recovery.recover(job("S3"));
        assertThat(absent.kind()).isEqualTo(DrillRecoveryPort.RecoverKind.RECOVERED);
        assertThat(absent.reason()).contains("chaos_session_absent");

        chaos.statusNotFound = false;
        chaos.failStatus = true;
        assertThat(recovery.recover(job("S3")).kind())
                .isEqualTo(DrillRecoveryPort.RecoverKind.UNKNOWN);
    }

    // ------------------------------------------------------------------ 核验面

    @Test
    @DisplayName("核验：症状码全部 resolved → VERIFIED；残留 firing / 探针异常 → PENDING"
            + "（下拍重试，不落 FAILED）")
    void verifyThreeStates() {
        assertThat(recovery.verify(job("S1")).kind())
                .isEqualTo(DrillRecoveryPort.VerifyKind.VERIFIED);
        probe.resolved = false;
        DrillRecoveryPort.VerifyOutcome firing = recovery.verify(job("S1"));
        assertThat(firing.kind()).isEqualTo(DrillRecoveryPort.VerifyKind.PENDING);
        assertThat(firing.reason()).contains("alerts_still_firing");
        probe.failProbe = true;
        assertThat(recovery.verify(job("S1")).kind())
                .isEqualTo(DrillRecoveryPort.VerifyKind.PENDING);
    }

    @Test
    @DisplayName("配置缺陷闸门：场景未登记 → FAILED（确定不可恢复，不进入任何网络面）")
    void unknownScenarioFails() {
        DrillRecoveryPort.RecoverOutcome outcome = recovery.recover(job("S9"));
        assertThat(outcome.kind()).isEqualTo(DrillRecoveryPort.RecoverKind.FAILED);
        assertThat(outcome.reason()).contains("SCENARIO_KNOWN");
        assertThat(chaos.lastDeactivateBody).isNull();
        assertThat(flagdPort.writes).isEmpty();
    }
}
