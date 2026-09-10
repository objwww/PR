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
import java.util.Map;
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

    // ------------------------------------------------------------------ EX-A1 多维准入（P1-02）

    private final ReservationKey baseKey = new ReservationKey(
            UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), 7, BudgetKind.TOOL_CALL);

    @Test
    void utG05_multiDim_token不足则整体拒_已预留维显式撤销_零触网() {
        // P1-02 场景①：工具额度够但 token 不够 → 拒；TOOL_CALL 已预留 → release 显式撤销
        java.util.Map<BudgetKind, Long> estimates = new java.util.LinkedHashMap<>();
        estimates.put(BudgetKind.TOOL_CALL, 1L);
        estimates.put(BudgetKind.TOKEN, 1L);
        ledger.nextProbe = BudgetProbe.allowed(0, 100);   // TOOL_CALL 过
        ledger.probesByKind.put(BudgetKind.TOKEN, BudgetProbe.rejected(500, 0));

        assertThatThrownBy(() -> gate.call(estimates, baseKey, () -> {
                    ledger.remoteCalls++;
                    return "payload";
                },
                r -> java.util.Map.of(BudgetKind.TOOL_CALL, RunBudgetGate.Usage.of(1L),
                        BudgetKind.TOKEN, RunBudgetGate.Usage.of(1L)),
                e -> false))
                .isInstanceOf(BudgetExhaustedException.class)
                .hasMessageContaining("TOKEN");

        assertThat(ledger.calls).containsExactly(
                "reserve:1", "reserve:1", "release");   // 最后一 release = TOOL_CALL 撤销
        assertThat(ledger.remoteCalls).as("准入失败零远程").isZero();
    }

    @Test
    void utG06_releaseOn命中_全维撤销_不落provisional() {
        // 记录写失败=确证未发出 → 全维 RELEASED（P1-02 场景③）
        java.util.Map<BudgetKind, Long> estimates = new java.util.LinkedHashMap<>();
        estimates.put(BudgetKind.TOOL_CALL, 1L);
        estimates.put(BudgetKind.TOKEN, 10L);
        ledger.nextProbe = BudgetProbe.allowed(0, 100);
        RuntimeException recordWriteFailed = new IllegalStateException("调用记录写失败");

        assertThatThrownBy(() -> gate.call(estimates, baseKey, () -> {
                    throw recordWriteFailed;
                },
                r -> java.util.Map.of(), e -> !(e instanceof IllegalArgumentException)))
                .isSameAs(recordWriteFailed);

        assertThat(ledger.calls).containsExactly(
                "reserve:1", "reserve:10", "release", "release");
        assertThat(ledger.calls.contains("provisional"))
                .as("确证未发出不得保守占用").isFalse();
    }

    @Test
    void utG07_usage缺失_markUnmatched_不猜零() {
        // P1-02 场景④：模型返回结果但缺 usage → UNMATCHED 落账（不 commit 零、不退款）
        ledger.nextProbe = BudgetProbe.allowed(0, 100);
        String result = gate.call(java.util.Map.of(BudgetKind.TOKEN, 50L), baseKey,
                () -> "model-output",
                r -> java.util.Map.of(BudgetKind.TOKEN, RunBudgetGate.Usage.unmatched()),
                e -> false);

        assertThat(result).isEqualTo("model-output");
        assertThat(ledger.calls).containsExactly("reserve:50", "unmatched");
    }

    @Test
    void utG08_openRun_逐维ensureLimit() {
        gate.openRun(UUID.randomUUID(), java.util.Map.of(
                BudgetKind.TOOL_CALL, 4L, BudgetKind.STEP, 8L));
        assertThat(ledger.calls).containsExactly("ensureLimit", "ensureLimit");
    }

    @Test
    void utG09_multiDim全部通过_按维实扣() {
        java.util.Map<BudgetKind, Long> estimates = new java.util.LinkedHashMap<>();
        estimates.put(BudgetKind.TOOL_CALL, 1L);
        estimates.put(BudgetKind.TOKEN, 50L);
        ledger.nextProbe = BudgetProbe.allowed(0, 100);

        gate.call(estimates, baseKey, () -> "ok",
                r -> java.util.Map.of(BudgetKind.TOOL_CALL, RunBudgetGate.Usage.of(1L),
                        BudgetKind.TOKEN, RunBudgetGate.Usage.of(37L)),
                e -> false);

        assertThat(ledger.calls).containsExactly(
                "reserve:1", "reserve:50", "commit:1", "commit:37");
    }

    @Test
    void utG10_multiDim空维集_显式拒绝() {
        assertThatThrownBy(() -> gate.call(java.util.Map.of(), baseKey,
                () -> "x", r -> java.util.Map.of(), e -> false))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("至少一维");
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
        final Map<BudgetKind, BudgetProbe> probesByKind = new java.util.EnumMap<>(BudgetKind.class);
        @Override
        public void ensureLimit(UUID runId, BudgetKind kind, long limitUnits) {
            calls.add("ensureLimit");
        }

        @Override
        public BudgetProbe reserve(ReservationKey key, long units) {
            calls.add("reserve:" + units);
            BudgetProbe perKind = probesByKind.get(key.budgetKind());
            return perKind != null ? perKind : nextProbe;
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
