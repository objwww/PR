package com.objwww.pr.control.it;

import com.objwww.pr.control.alert.application.RunBudgetGate;
import com.objwww.pr.control.alert.domain.budget.BudgetExhaustedException;
import com.objwww.pr.control.alert.domain.budget.BudgetKind;
import com.objwww.pr.control.alert.domain.budget.ReservationKey;
import com.objwww.pr.control.infrastructure.persistence.PostgresRunBudgetLedger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * EX-A1 验收（L1，真 PG）：预算门多维准入 + PostgresRunBudgetLedger 状态机
 * （docs/告警-EXA1-预算接线.md §7，itE1~itE5）。
 *
 * <p>钉五件事（全部真 V13 两表落账，行级可核）：
 * <ul>
 *   <li>itE1 多维一次准入 all-or-nothing：任一维拒 → 已预留维 entry 态=RELEASED
 *       可核撤销、账面零残留、被拒维零 entry、远程零调用；</li>
 *   <li>itE2 两任务争最后一_unit：一得一拒（赢家 COMMITTED，输家零行不落账）；</li>
 *   <li>itE3 预留成功 + 记录写失败（releaseOn 命中=确证未发出）→ 全额 RELEASED；</li>
 *   <li>itE4 服务端 usage 缺失 → entry UNMATCHED（不猜零，预留量留在账面）；</li>
 *   <li>itE5 物理重试新 callSeq → 两笔独立 COMMITTED，实扣累计（幂等键不吞重试成本）。</li>
 * </ul>
 *
 * <p>语义编排面（agent 三族异常分账、doom 消费点）在 L0：MetricsAgentTest /
 * RunBudgetGateTest（exa1-l0-local.log）；本类是真 PG 账本上的准入/撤销/落账验收。
 */
class ExA1BudgetAdmissionIT extends PostgresITBase {

    private RunBudgetGate gate;

    @BeforeEach
    void setUp() {
        gate = new RunBudgetGate(new PostgresRunBudgetLedger(controlJdbc, controlTx));
    }

    // ------------------------------------------------ itE1 多维 all-or-nothing 可核撤销

    @Test
    @DisplayName("itE1 多维准入：TOOL_CALL 过 + TOKEN 拒 → TOOL_CALL entry RELEASED、账面零残留、远程零调用")
    void multiDimRefusalReleasesAdmittedDims() {
        UUID runId = UUID.randomUUID();
        gate.openRun(runId, Map.of(BudgetKind.TOOL_CALL, 10L, BudgetKind.TOKEN, 0L));

        UUID taskId = UUID.randomUUID();
        UUID attemptId = UUID.randomUUID();
        LinkedHashMap<BudgetKind, Long> estimates = new LinkedHashMap<>();
        estimates.put(BudgetKind.TOOL_CALL, 1L);
        estimates.put(BudgetKind.TOKEN, 5L);
        AtomicInteger remoteCalls = new AtomicInteger();

        assertThatThrownBy(() -> gate.call(estimates,
                new ReservationKey(runId, taskId, attemptId, 1L, BudgetKind.TOOL_CALL),
                () -> {
                    remoteCalls.incrementAndGet();
                    return "ok";
                },
                result -> Map.of(BudgetKind.TOOL_CALL, RunBudgetGate.Usage.of(1L),
                        BudgetKind.TOKEN, RunBudgetGate.Usage.of(5L)),
                e -> false))
                .isInstanceOf(BudgetExhaustedException.class)
                .hasMessageContaining("TOKEN");

        assertThat(remoteCalls.get()).as("耗尽路径零远程调用（INV-AM4-9）").isZero();
        // 已预留维显式撤销在真 PG 可核：行在、态=RELEASED、账面回零
        assertThat(entryState(runId, taskId, 1L)).isEqualTo("RELEASED");
        assertThat(consumedUnits(runId, BudgetKind.TOOL_CALL)).isZero();
        assertThat(consumedUnits(runId, BudgetKind.TOKEN)).isZero();
        assertThat(entryCount(runId)).as("被拒维不落 entry").isEqualTo(1);
    }

    // ------------------------------------------------ itE2 最后一_unit 一得一拒

    @Test
    @DisplayName("itE2 两任务争 TOOL_CALL 限额 1：先到者预留→COMMITTED，后到者拒绝零行")
    void lastUnitOneWinsOneRefused() {
        UUID runId = UUID.randomUUID();
        gate.openRun(runId, Map.of(BudgetKind.TOOL_CALL, 1L));

        UUID winner = UUID.randomUUID();
        UUID loser = UUID.randomUUID();
        UUID attemptId = UUID.randomUUID();

        gate.call(Map.of(BudgetKind.TOOL_CALL, 1L),
                new ReservationKey(runId, winner, attemptId, 1L, BudgetKind.TOOL_CALL),
                () -> "ok", r -> Map.of(BudgetKind.TOOL_CALL, RunBudgetGate.Usage.of(1L)),
                e -> false);

        assertThatThrownBy(() -> gate.call(Map.of(BudgetKind.TOOL_CALL, 1L),
                new ReservationKey(runId, loser, attemptId, 1L, BudgetKind.TOOL_CALL),
                () -> "ok", r -> Map.of(BudgetKind.TOOL_CALL, RunBudgetGate.Usage.of(1L)),
                e -> false))
                .isInstanceOf(BudgetExhaustedException.class);

        assertThat(consumedUnits(runId, BudgetKind.TOOL_CALL)).isEqualTo(1L);
        assertThat(entryState(runId, winner, 1L)).isEqualTo("COMMITTED");
        assertThat(entryCount(runId, loser))
                .as("输家在预留段即拒，零 entry 行").isZero();
    }

    // ------------------------------------------------ itE3 记录写失败 → 显式撤销

    @Test
    @DisplayName("itE3 预留成功后记录写失败（确证未发出族命中 releaseOn）→ 全额 RELEASED、账面回零")
    void recordWriteFailureReleasesReservation() {
        UUID runId = UUID.randomUUID();
        gate.openRun(runId, Map.of(BudgetKind.TOOL_CALL, 10L));

        UUID taskId = UUID.randomUUID();
        UUID attemptId = UUID.randomUUID();

        assertThatThrownBy(() -> gate.call(Map.of(BudgetKind.TOOL_CALL, 1L),
                new ReservationKey(runId, taskId, attemptId, 1L, BudgetKind.TOOL_CALL),
                () -> {
                    throw new IllegalStateException("rca_tool_call 记录写失败（模拟）");
                },
                r -> Map.of(BudgetKind.TOOL_CALL, RunBudgetGate.Usage.of(1L)),
                // agent 同判别面：记录写失败确证未发出（ToolModelVisible 之外全命中此处模拟）
                e -> true))
                .isInstanceOf(IllegalStateException.class);

        assertThat(entryState(runId, taskId, 1L)).isEqualTo("RELEASED");
        assertThat(consumedUnits(runId, BudgetKind.TOOL_CALL)).isZero();
    }

    // ------------------------------------------------ itE4 usage 缺失 → UNMATCHED 不猜零

    @Test
    @DisplayName("itE4 服务端 usage 缺失 → entry UNMATCHED、预留量留账面（不伪造零）")
    void missingUsageMarksUnmatchedKeepingBookUnits() {
        UUID runId = UUID.randomUUID();
        gate.openRun(runId, Map.of(BudgetKind.TOOL_CALL, 10L));

        UUID taskId = UUID.randomUUID();
        gate.call(Map.of(BudgetKind.TOOL_CALL, 1L),
                new ReservationKey(runId, taskId, UUID.randomUUID(), 1L, BudgetKind.TOOL_CALL),
                () -> "ok", r -> Map.of(BudgetKind.TOOL_CALL, RunBudgetGate.Usage.unmatched()),
                e -> false);

        assertThat(entryState(runId, taskId, 1L)).isEqualTo("UNMATCHED");
        assertThat(consumedUnits(runId, BudgetKind.TOOL_CALL))
                .as("UNMATCHED 不退款也不实扣：预留量留账待对账").isEqualTo(1L);
    }

    // ------------------------------------------------ itE5 物理重试另记成本

    @Test
    @DisplayName("itE5 物理重试新 callSeq → 两笔独立 COMMITTED、实扣累计 2")
    void physicalRetryBooksSecondCommittedEntry() {
        UUID runId = UUID.randomUUID();
        gate.openRun(runId, Map.of(BudgetKind.TOOL_CALL, 10L));

        UUID taskId = UUID.randomUUID();
        UUID attemptId = UUID.randomUUID();

        gate.call(Map.of(BudgetKind.TOOL_CALL, 1L),
                new ReservationKey(runId, taskId, attemptId, 1L, BudgetKind.TOOL_CALL),
                () -> "ok", r -> Map.of(BudgetKind.TOOL_CALL, RunBudgetGate.Usage.of(1L)),
                e -> false);
        gate.call(Map.of(BudgetKind.TOOL_CALL, 1L),
                new ReservationKey(runId, taskId, attemptId, 2L, BudgetKind.TOOL_CALL),
                () -> "ok", r -> Map.of(BudgetKind.TOOL_CALL, RunBudgetGate.Usage.of(1L)),
                e -> false);

        assertThat(entryCount(runId)).isEqualTo(2);
        assertThat(entryState(runId, taskId, 1L)).isEqualTo("COMMITTED");
        assertThat(entryState(runId, taskId, 2L)).isEqualTo("COMMITTED");
        assertThat(consumedUnits(runId, BudgetKind.TOOL_CALL)).isEqualTo(2L);
    }

    // ------------------------------------------------------------------ 账面读回（admin 视角）

    /** run 全部 entry 数（不分数） */
    private long entryCount(UUID runId) {
        return adminJdbc.sql("SELECT count(*) FROM run_budget_entry WHERE run_id = :run")
                .param("run", runId).query(Long.class).single();
    }

    /** 指定任务的 TOOL_CALL entry 数 */
    private long entryCount(UUID runId, UUID taskId) {
        return adminJdbc.sql("""
                SELECT count(*) FROM run_budget_entry
                 WHERE run_id = :run AND task_id = :task AND budget_kind = 'TOOL_CALL'
                """).param("run", runId).param("task", taskId).query(Long.class).single();
    }

    /** 指定任务 callSeq 的 TOOL_CALL entry 态 */
    private String entryState(UUID runId, UUID taskId, long callSeq) {
        return adminJdbc.sql("""
                SELECT state FROM run_budget_entry
                 WHERE run_id = :run AND task_id = :task AND call_seq = :seq
                   AND budget_kind = 'TOOL_CALL'
                """).param("run", runId).param("task", taskId).param("seq", callSeq)
                .query(String.class).single();
    }

    /** run 某维账面已扣量 */
    private long consumedUnits(UUID runId, BudgetKind kind) {
        return adminJdbc.sql("""
                SELECT consumed_units FROM run_budget_state
                 WHERE run_id = :run AND budget_kind = :kind
                """).param("run", runId).param("kind", kind.name())
                .query(Long.class).single();
    }
}
