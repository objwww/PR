package com.objwww.pr.control.alert.application;

import com.objwww.pr.control.alert.domain.budget.BudgetExhaustedException;
import com.objwww.pr.control.alert.domain.budget.BudgetKind;
import com.objwww.pr.control.alert.domain.budget.BudgetProbe;
import com.objwww.pr.control.alert.domain.budget.ReservationKey;
import com.objwww.pr.control.alert.domain.budget.RunBudgetLedger;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * RunBudgetGate 穷举单测（AM4 M4-08，INV-AM4-9 执行点）：
 * reserve 拒 → 零远程调用直接终止；远程成功 → 按 usage 实扣 commit；
 * 远程抛 → PROVISIONAL 等对账且异常原样上抛。账面正确性归 PG 实现（IT）。
 */
class RunBudgetGateTest {

    private final RecordingLedger ledger = new RecordingLedger();
    private final RunBudgetGate gate = new RunBudgetGate(ledger);
    private final ReservationKey key = new ReservationKey(
            UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), 1, BudgetKind.TOKEN);

    @Test
    void utG01_reserve被拒_零远程调用_抛BudgetExhausted() {
        ledger.nextProbe = BudgetProbe.rejected(500, 0);
        assertThatThrownBy(() -> gate.call(key, 50, boomRemote(), usage -> 1L))
                .isInstanceOf(BudgetExhaustedException.class)
                .hasMessageContaining("TOKEN");
        assertThat(ledger.calls).containsExactly("reserve:50"); // 无 commit/release/provisional
        assertThat(ledger.remoteCalls).isZero(); // INV-AM4-9：耗尽路径零远程
    }

    @Test
    void utG02_remote成功_按服务端usage实扣commit_返回结果() {
        ledger.nextProbe = BudgetProbe.allowed(0, 100);
        String result = gate.call(key, 50, () -> "payload", usage -> 7L);
        assertThat(result).isEqualTo("payload");
        assertThat(ledger.calls).containsExactly("reserve:50", "commit:7"); // 实扣 7 ≠ 估算 50
    }

    @Test
    void utG03_remote抛异常_PROVISIONAL等对账_异常原样上抛() {
        ledger.nextProbe = BudgetProbe.allowed(0, 100);
        RuntimeException boom = new IllegalStateException("远端炸了");
        assertThatThrownBy(() -> gate.call(key, 50, (Supplier<String>) () -> { throw boom; },
                usage -> 1L))
                .isSameAs(boom);
        assertThat(ledger.calls).containsExactly("reserve:50", "provisional");
    }

    @Test
    void utG04_估算量直接作为reserve入参() {
        ledger.nextProbe = BudgetProbe.allowed(0, 100);
        gate.call(key, 123, () -> "x", usage -> 1L);
        assertThat(ledger.calls).containsExactly("reserve:123", "commit:1");
    }

    private Supplier<String> boomRemote() {
        return () -> {
            ledger.remoteCalls++;
            throw new AssertionError("预算耗尽后不得发起远程调用");
        };
    }

    /** 记录调用序列的 fake 账本（只测 gate 编排语义，账面算术归 PG IT） */
    private static final class RecordingLedger implements RunBudgetLedger {
        final List<String> calls = new ArrayList<>();
        int remoteCalls;
        BudgetProbe nextProbe = BudgetProbe.allowed(0, 100);

        @Override
        public void ensureLimit(UUID runId, BudgetKind kind, long limitUnits) {
            calls.add("ensureLimit");
        }

        @Override
        public BudgetProbe reserve(ReservationKey key, long units) {
            calls.add("reserve:" + units);
            return nextProbe;
        }

        @Override
        public void commit(ReservationKey key, long actualUnits) {
            calls.add("commit:" + actualUnits);
        }

        @Override
        public void release(ReservationKey key) {
            calls.add("release");
        }

        @Override
        public void provisional(ReservationKey key) {
            calls.add("provisional");
        }

        @Override
        public void markUnmatched(ReservationKey key) {
            calls.add("unmatched");
        }

        @Override
        public List<ReservationKey> findStaleReservations(Instant olderThan) {
            calls.add("stale");
            return List.of();
        }
    }
}
