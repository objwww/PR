package com.objwww.pr.control.drill.application;

import com.objwww.pr.control.drill.domain.model.FlagdRestoreRecord;
import com.objwww.pr.control.drill.domain.model.FlagdState;
import com.objwww.pr.control.drill.domain.repository.FlagdRestoreLedger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * DR-05 截止清扫（§7.5「worker 重启后清扫」）：清扫器无状态可重入——超 deadline 的
 * 可恢复面记录逐条条件恢复：值未动→RESTORED、他者改写→CONFLICT 不覆盖、读不到→
 * 不关账留下轮重试；已收口记录不再扫描（重启重跑幂等）。
 */
class FlagdRestoreSweeperTest {

    private static final Instant BASE = Instant.parse("2026-09-11T00:00:00Z");
    private static final Instant DEADLINE = BASE.plusSeconds(4380);

    private static final class StubPort implements FlagdAdminPort {
        final Map<String, String> variants = new LinkedHashMap<>();
        final Map<String, Integer> generations = new LinkedHashMap<>();
        final List<String> writes = new ArrayList<>();
        boolean failRead;

        void seed(String flag, String variant) {
            variants.put(flag, variant);
            generations.put(flag, 1);
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
            return byId.values().stream()
                    .filter(r -> r.state().isRestorable() && r.deadlineAt().isBefore(now))
                    .toList();
        }
    }

    private StubPort port;
    private InMemoryLedger ledger;
    private AtomicReference<Instant> now;
    private FlagdRestoreSweeper sweeper;

    @BeforeEach
    void setUp() {
        port = new StubPort();
        ledger = new InMemoryLedger();
        now = new AtomicReference<>(BASE.plusSeconds(5000)); // 已超 deadline
        sweeper = new FlagdRestoreSweeper(port, ledger, now::get);
    }

    private FlagdRestoreRecord record(FlagdRestoreRecord.State state, Instant deadline) {
        return new FlagdRestoreRecord(UUID.randomUUID(), "paymentFailure", "S1", 1,
                "off", "g1", "50%", "g2", "off", deadline, state, null, BASE, BASE);
    }

    @Test
    @DisplayName("超 deadline 且值仍属本次写入 → 写回原值并收口 RESTORED")
    void restoresPastDeadlineWhenValueOwned() {
        port.seed("paymentFailure", "50%");
        port.generations.put("paymentFailure", 2); // 对齐 appliedGeneration g2
        FlagdRestoreRecord r = record(FlagdRestoreRecord.State.OPEN, DEADLINE);
        ledger.recordActivation(r);
        assertThat(sweeper.sweep()).isEqualTo(1);
        assertThat(port.writes).containsExactly("paymentFailure=off");
        assertThat(ledger.byId.get(r.id()).state())
                .isEqualTo(FlagdRestoreRecord.State.RESTORED);
        assertThat(ledger.byId.get(r.id()).stateReason()).contains("deadline_sweep");
    }

    @Test
    @DisplayName("超 deadline 但他者已改写 → CONFLICT 不覆盖，保留现值")
    void conflictsWhenOthersRewrote() {
        port.seed("paymentFailure", "90%"); // 他者改写（代际不符）
        FlagdRestoreRecord r = record(FlagdRestoreRecord.State.OPEN, DEADLINE);
        ledger.recordActivation(r);
        assertThat(sweeper.sweep()).isEqualTo(1);
        assertThat(port.writes).isEmpty(); // 不覆盖
        assertThat(port.variants.get("paymentFailure")).isEqualTo("90%");
        assertThat(ledger.byId.get(r.id()).state())
                .isEqualTo(FlagdRestoreRecord.State.CONFLICT);
    }

    @Test
    @DisplayName("读不到当前值 → 不盲写不关账，留可恢复面下轮重试（恢复后重试成功）")
    void unknownStaysRestorableAndRetries() {
        port.failRead = true;
        FlagdRestoreRecord r = record(FlagdRestoreRecord.State.OPEN, DEADLINE);
        ledger.recordActivation(r);
        assertThat(sweeper.sweep()).isEqualTo(0); // 不关账
        assertThat(port.writes).isEmpty();
        assertThat(ledger.byId.get(r.id()).state())
                .isEqualTo(FlagdRestoreRecord.State.OPEN);
        // worker 重启/链路恢复后重试（重启语义：清扫器无状态，重跑即对账）
        port.failRead = false;
        port.seed("paymentFailure", "50%");
        port.generations.put("paymentFailure", 2);
        assertThat(sweeper.sweep()).isEqualTo(1);
        assertThat(ledger.byId.get(r.id()).state())
                .isEqualTo(FlagdRestoreRecord.State.RESTORED);
    }

    @Test
    @DisplayName("未到 deadline 的记录不动；已收口（RESTORED）记录不再扫描（重跑幂等）")
    void skipsFreshAndTerminalRecords() {
        port.seed("paymentFailure", "50%");
        port.generations.put("paymentFailure", 2);
        FlagdRestoreRecord fresh = record(FlagdRestoreRecord.State.OPEN,
                now.get().plusSeconds(600));
        FlagdRestoreRecord done = record(FlagdRestoreRecord.State.RESTORED, DEADLINE);
        ledger.recordActivation(fresh);
        ledger.recordActivation(done);
        assertThat(sweeper.sweep()).isEqualTo(0);
        assertThat(port.writes).isEmpty();
        assertThat(ledger.byId.get(fresh.id()).state())
                .isEqualTo(FlagdRestoreRecord.State.OPEN);
    }
}
