package com.objwww.pr.control.eval.application;

import com.objwww.pr.control.alert.domain.model.TypedRootCause;
import com.objwww.pr.control.drill.domain.model.FlagdRestoreRecord;
import com.objwww.pr.control.drill.domain.model.FlagdState;
import com.objwww.pr.control.drill.domain.repository.FlagdRestoreLedger;
import com.objwww.pr.control.eval.domain.GoldenCase;
import com.objwww.pr.shared.Digest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * DR-05 Flagd 条件恢复（方案 §7.4 Flagd 段 / §7.5 DR-05 卡）：activate 落账实际原值
 * 与写入代际；deactivate 三态——值未动→恢复（实际原值优先）、他者改写（含同值重写
 * 代际推进）→ CONFLICT 不覆盖、读不到→ UNKNOWN 不盲写；无台账两参过渡面仍条件恢复。
 */
class FlagdScenarioDriverTest {

    private static final Instant BASE = Instant.parse("2026-09-11T00:00:00Z");
    private static final GoldenCase S1 = goldenCase();

    // ------------------------------------------------------------------ 假件

    /** flagd 管理面桩：值 + 单调代际令牌（任何写入含同值重写都推进代际） */
    private static final class StubFlagAdmin implements FlagdScenarioDriver.FlagAdminClient {
        final Map<String, String> variants = new LinkedHashMap<>();
        final Map<String, Integer> generations = new LinkedHashMap<>();
        final List<String> writes = new ArrayList<>();
        boolean failRead;

        void seed(String flag, String variant) {
            variants.put(flag, variant);
            generations.put(flag, 1);
        }

        @Override
        public String setDefaultVariant(String flag, String variant) {
            variants.put(flag, variant);
            generations.merge(flag, 1, Integer::sum);
            writes.add(flag + "=" + variant);
            return variant;
        }

        /** 模拟「他者改写」：改值并推进代际，但不进 writes 日志（非本 driver 的写） */
        void externalWrite(String flag, String variant) {
            variants.put(flag, variant);
            generations.merge(flag, 1, Integer::sum);
        }

        @Override
        public FlagdState readDefaultVariant(String flag) {
            if (failRead) {
                throw new IllegalStateException("flagd-admin 不可达");
            }
            return new FlagdState(variants.get(flag),
                    generations.containsKey(flag) ? "g" + generations.get(flag) : null);
        }
    }

    private static final class StubProbe implements AlertProbe {
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
            return Digest.sha256Of(alertname);
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
                    .reduce((a, b) -> b); // 最新一条
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
            return byId.values().stream()
                    .filter(r -> r.state().isRestorable() && r.deadlineAt().isBefore(now))
                    .toList();
        }

        FlagdRestoreRecord only() {
            assertThat(byId).hasSize(1);
            return byId.values().iterator().next();
        }
    }

    private static final class MutableClock extends Clock {
        Instant now = BASE;

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    private StubFlagAdmin client;
    private InMemoryLedger ledger;
    private MutableClock clock;

    @BeforeEach
    void setUp() {
        client = new StubFlagAdmin();
        ledger = new InMemoryLedger();
        clock = new MutableClock();
    }

    private FlagdScenarioDriver driver() {
        return new FlagdScenarioDriver(client, new StubProbe(), ledger, clock);
    }

    // ------------------------------------------------------------------ activate 落账

    @Test
    @DisplayName("activate 落账：写入前实际原值/原代际 + 写入值/写入代际 + 作业级截止")
    void activatePersistsOriginalValueAndGeneration() {
        client.seed("paymentFailure", "25%");
        driver().activate(S1, 1);
        assertThat(client.writes).containsExactly("paymentFailure=50%");
        FlagdRestoreRecord r = ledger.only();
        assertThat(r.originalVariant()).isEqualTo("25%");
        assertThat(r.originalGeneration()).isEqualTo("g1");
        assertThat(r.appliedVariant()).isEqualTo("50%");
        assertThat(r.appliedGeneration()).isEqualTo("g2");
        assertThat(r.baselineVariant()).isEqualTo("off");
        assertThat(r.state()).isEqualTo(FlagdRestoreRecord.State.OPEN);
        assertThat(r.deadlineAt()).isEqualTo(BASE.plusSeconds(60 + 600 + 1500 + 2100 + 120));
    }

    // ------------------------------------------------------------------ 条件恢复三态

    @Test
    @DisplayName("值未动 → 条件恢复写回实际原值（非模板 baseline），收口 RESTORED")
    void deactivateRestoresActualOriginalWhenUntouched() {
        client.seed("paymentFailure", "25%"); // 实际原值 ≠ 模板 baseline(off)
        ScenarioDriver.ActivationReceipt receipt = driver().activate(S1, 1);
        ScenarioDriver.RecoveryReceipt recovery = driver().deactivate(S1, receipt);
        assertThat(recovery.criteriaMet()).isTrue();
        assertThat(recovery.unmetCriteria()).isEmpty();
        assertThat(client.writes).containsExactly("paymentFailure=50%",
                "paymentFailure=25%"); // 实际原值优先（restoreTarget）
        assertThat(ledger.only().state()).isEqualTo(FlagdRestoreRecord.State.RESTORED);
    }

    @Test
    @DisplayName("他者改写 → CONFLICT：不覆盖新值、不宣称恢复成功")
    void deactivateConflictsWhenOthersRewrote() {
        client.seed("paymentFailure", "off");
        ScenarioDriver.ActivationReceipt receipt = driver().activate(S1, 1);
        client.externalWrite("paymentFailure", "90%"); // 演练期间他者改写
        ScenarioDriver.RecoveryReceipt recovery = driver().deactivate(S1, receipt);
        assertThat(recovery.criteriaMet()).isFalse();
        assertThat(recovery.unmetCriteria()).anySatisfy(u ->
                assertThat(u).startsWith("flag_restore_conflict:").contains("90%"));
        assertThat(client.writes).containsExactly("paymentFailure=50%"); // 无恢复写
        assertThat(client.variants.get("paymentFailure")).isEqualTo("90%"); // 未被覆盖
        assertThat(ledger.only().state()).isEqualTo(FlagdRestoreRecord.State.CONFLICT);
    }

    @Test
    @DisplayName("他者同值重写（代际推进）→ 仍判 CONFLICT 不覆盖（版本判据生效）")
    void deactivateConflictsOnSameValueRewrite() {
        client.seed("paymentFailure", "off");
        ScenarioDriver.ActivationReceipt receipt = driver().activate(S1, 1);
        client.externalWrite("paymentFailure", "50%"); // 同值重写，代际 g2→g3
        ScenarioDriver.RecoveryReceipt recovery = driver().deactivate(S1, receipt);
        assertThat(recovery.criteriaMet()).isFalse();
        assertThat(recovery.unmetCriteria()).anySatisfy(u ->
                assertThat(u).startsWith("flag_restore_conflict:"));
        assertThat(client.writes).containsExactly("paymentFailure=50%");
        assertThat(ledger.only().state()).isEqualTo(FlagdRestoreRecord.State.CONFLICT);
    }

    @Test
    @DisplayName("读不到当前值 → UNKNOWN 不盲写，台账留可恢复面待截止清扫重试")
    void deactivateUnknownWhenUnreadable() {
        client.seed("paymentFailure", "off");
        ScenarioDriver.ActivationReceipt receipt = driver().activate(S1, 1);
        client.failRead = true;
        ScenarioDriver.RecoveryReceipt recovery = driver().deactivate(S1, receipt);
        assertThat(recovery.criteriaMet()).isFalse();
        assertThat(recovery.unmetCriteria()).anySatisfy(u ->
                assertThat(u).startsWith("flag_state_unknown:"));
        assertThat(client.writes).containsExactly("paymentFailure=50%"); // 未盲写
        assertThat(ledger.only().state()).isEqualTo(FlagdRestoreRecord.State.UNKNOWN);
    }

    @Test
    @DisplayName("无台账两参过渡面（EvalRunnerConfig 旧装配）：恢复仍条件化，他者改写不覆盖")
    void legacyConstructorStillRestoresConditionally() {
        FlagdScenarioDriver legacy = new FlagdScenarioDriver(client, new StubProbe());
        client.seed("paymentFailure", "off");
        ScenarioDriver.ActivationReceipt receipt = legacy.activate(S1, 1);
        assertThat(ledger.byId).isEmpty(); // 无台账如实不落
        client.externalWrite("paymentFailure", "75%");
        ScenarioDriver.RecoveryReceipt recovery = legacy.deactivate(S1, receipt);
        assertThat(recovery.criteriaMet()).isFalse();
        assertThat(recovery.unmetCriteria()).anySatisfy(u ->
                assertThat(u).startsWith("flag_restore_conflict:"));
        assertThat(client.writes).containsExactly("paymentFailure=50%"); // 仅激活写，无恢复覆盖
    }

    private static GoldenCase goldenCase() {
        return new GoldenCase("S1", "paymentFailure=50%", "FlagdScenarioDriver", null,
                "payment",
                new TypedRootCause("payment", "BUSINESS_ERROR_RATE",
                        "PAYMENT_CHARGE_FAILURE"),
                List.of("checkout"), Map.of(),
                new GoldenCase.Injection("paymentFailure", "50%", "off"),
                new GoldenCase.Timing(60, 600, 1500, 2100, 120));
    }
}
