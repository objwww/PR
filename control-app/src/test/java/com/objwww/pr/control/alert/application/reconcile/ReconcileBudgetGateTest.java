package com.objwww.pr.control.alert.application.reconcile;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 六类 Reconciler 预算统一件单测（AM4 M4-37，TDD 先行）。三态分流（评审裁定）：
 * 预算耗尽（attempts/墙钟/积压年龄，积压年龄窗口起点由调用方挂定不漂移）→
 * TERMINAL + 确定性事件（停止自循环）；速率/临时失败 → BACKOFF（指数退避封顶）；
 * 源降级 → 仅白名单只读工作可降级续跑（PROCEED_DEGRADED 带事件记录降级原因，
 * 非白名单退避）且不得突破 attempts/墙钟/积压年龄硬维（硬维判定先于降级）。
 *
 * @author wanghua
 * @date 2026-09-05
 */
class ReconcileBudgetGateTest {

    private static final String KIND = "incident-source";
    private static final long T0 = 1_790_000_000_000L;
    private static final ReconcileBudgetGate.GateConfig CFG =
            new ReconcileBudgetGate.GateConfig(5, 60 * 60_000L, 24 * 60 * 60_000L,
                    30_000L, 8 * 60_000L);
    private static final ReconcileBudgetGate.GateState FRESH =
            new ReconcileBudgetGate.GateState(0, T0, T0);

    private final ReconcileBudgetGate gate = new ReconcileBudgetGate();

    @Test
    void attemptsExhaustedIsTerminalWithEvent() {
        ReconcileBudgetGate.GateOutcome outcome = gate.evaluate(KIND, CFG,
                new ReconcileBudgetGate.GateState(5, T0, T0),
                ReconcileBudgetGate.Failure.NONE, false, true, T0 + 1_000L);

        assertThat(outcome.routing())
                .isEqualTo(ReconcileBudgetGate.Routing.TERMINAL);
        assertThat(outcome.event().reason()).contains("ATTEMPTS");
        assertThat(outcome.event().reconcilerKind()).isEqualTo(KIND);
    }

    @Test
    void wallClockExhaustedIsTerminal() {
        ReconcileBudgetGate.GateOutcome outcome = gate.evaluate(KIND, CFG, FRESH,
                ReconcileBudgetGate.Failure.NONE, false, true, T0 + 60 * 60_000L);

        assertThat(outcome.routing()).isEqualTo(ReconcileBudgetGate.Routing.TERMINAL);
        assertThat(outcome.event().reason()).contains("WALL_CLOCK");
    }

    @Test
    void backlogAgeOverLimitIsTerminalAnchoredAtWindowStart() {
        // 积压年龄超限（窗口起点由调用方挂定）→ 终态；墙钟用宽配置避免相撞
        ReconcileBudgetGate.GateConfig longWall =
                new ReconcileBudgetGate.GateConfig(5, 48 * 60 * 60_000L,
                        24 * 60 * 60_000L, 30_000L, 8 * 60_000L);
        ReconcileBudgetGate.GateState freshWall =
                new ReconcileBudgetGate.GateState(0, T0 + 24 * 60 * 60_000L, T0);
        ReconcileBudgetGate.GateOutcome over = gate.evaluate(KIND, longWall, freshWall,
                ReconcileBudgetGate.Failure.NONE, false, true, T0 + 24 * 60 * 60_000L);
        assertThat(over.routing()).isEqualTo(ReconcileBudgetGate.Routing.TERMINAL);
        assertThat(over.event().reason()).contains("BACKLOG_AGE");

        // 同一时刻，窗口起点挂得更晚（积压更年轻）→ 不终态（起点不漂移、不由组件重算）
        ReconcileBudgetGate.GateState youngBacklog =
                new ReconcileBudgetGate.GateState(0, T0 + 24 * 60 * 60_000L,
                        T0 + 23 * 60 * 60_000L);
        ReconcileBudgetGate.GateOutcome young = gate.evaluate(KIND, longWall, youngBacklog,
                ReconcileBudgetGate.Failure.NONE, false, true, T0 + 24 * 60 * 60_000L);
        assertThat(young.routing()).isNotEqualTo(ReconcileBudgetGate.Routing.TERMINAL);
    }

    @Test
    void temporaryFailureBacksOffExponentiallyAndCapped() {
        ReconcileBudgetGate.GateOutcome first = gate.evaluate(KIND, CFG,
                new ReconcileBudgetGate.GateState(0, T0, T0),
                ReconcileBudgetGate.Failure.RATE_OR_TEMPORARY, false, true, T0);
        assertThat(first.routing()).isEqualTo(ReconcileBudgetGate.Routing.BACKOFF);
        assertThat(first.nextRetryAtMillis()).isEqualTo(T0 + 30_000L);

        ReconcileBudgetGate.GateOutcome third = gate.evaluate(KIND, CFG,
                new ReconcileBudgetGate.GateState(2, T0, T0),
                ReconcileBudgetGate.Failure.RATE_OR_TEMPORARY, false, true, T0);
        assertThat(third.nextRetryAtMillis()).isEqualTo(T0 + 120_000L);

        // 指数封顶：未耗尽 attempts 前指数已越过 cap（宽 attempts 配置隔离终态分支）
        ReconcileBudgetGate.GateConfig wideAttempts =
                new ReconcileBudgetGate.GateConfig(50, 60 * 60_000L,
                        24 * 60 * 60_000L, 30_000L, 8 * 60_000L);
        ReconcileBudgetGate.GateOutcome capped = gate.evaluate(KIND, wideAttempts,
                new ReconcileBudgetGate.GateState(40, T0, T0),
                ReconcileBudgetGate.Failure.RATE_OR_TEMPORARY, false, true, T0);
        assertThat(capped.nextRetryAtMillis()).isEqualTo(T0 + 8 * 60_000L);
    }

    @Test
    void degradedWithReadOnlyWhitelistContinuesWithEvent() {
        ReconcileBudgetGate.GateOutcome outcome = gate.evaluate(KIND, CFG, FRESH,
                ReconcileBudgetGate.Failure.NONE, true, true, T0 + 1_000L);

        assertThat(outcome.routing())
                .isEqualTo(ReconcileBudgetGate.Routing.PROCEED_DEGRADED);
        assertThat(outcome.event()).isNotNull();
        assertThat(outcome.event().reason()).contains("降级");
    }

    @Test
    void degradedWithoutWhitelistBacksOffWithDegradeReasonRecorded() {
        ReconcileBudgetGate.GateOutcome outcome = gate.evaluate(KIND, CFG, FRESH,
                ReconcileBudgetGate.Failure.NONE, true, false, T0 + 1_000L);

        assertThat(outcome.routing()).isEqualTo(ReconcileBudgetGate.Routing.BACKOFF);
        assertThat(outcome.event()).isNotNull();
        assertThat(outcome.event().reason()).contains("降级");
        assertThat(outcome.event().reason()).contains("白名单");
    }

    @Test
    void degradedCannotBreachHardDimensions() {
        // 降级 + 白名单只读，但墙钟已耗尽 → 仍终态（硬维判定先于降级）
        ReconcileBudgetGate.GateOutcome outcome = gate.evaluate(KIND, CFG, FRESH,
                ReconcileBudgetGate.Failure.NONE, true, true, T0 + 60 * 60_000L);

        assertThat(outcome.routing()).isEqualTo(ReconcileBudgetGate.Routing.TERMINAL);
    }

    @Test
    void healthyStateProceedsWithoutEvent() {
        ReconcileBudgetGate.GateOutcome outcome = gate.evaluate(KIND, CFG, FRESH,
                ReconcileBudgetGate.Failure.NONE, false, true, T0 + 1_000L);

        assertThat(outcome.routing()).isEqualTo(ReconcileBudgetGate.Routing.PROCEED);
        assertThat(outcome.event()).isNull();
    }

    @Test
    void invalidConfigurationIsRejected() {
        assertThatThrownBy(() -> new ReconcileBudgetGate.GateConfig(0, 1, 1, 1, 1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ReconcileBudgetGate.GateConfig(1, 0, 1, 1, 1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ReconcileBudgetGate.GateConfig(1, 1, 1, 0, 1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ReconcileBudgetGate.GateConfig(1, 1, 1, 1, 0))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
