package com.objwww.pr.control.alert.domain.budget;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * UT-AM4-08：RunBudget 扣减纯逻辑——四类计数上限穷举（STEP/TOOL_CALL/EVIDENCE/SUBTASK）
 * + TIME 固定 deadline 语义 + long 溢出防绕过。
 */
class RunBudgetTest {

    private static RunBudget budget(long steps, long toolCalls, long evidences, long subtasks) {
        return new RunBudget(steps, toolCalls, evidences, subtasks, Long.MAX_VALUE);
    }

    @Test
    void consumeWithinLimitSucceeds() {
        RunBudget budget = budget(10, 5, 100, 3);
        budget.consume(RunBudget.Kind.STEP, 3);
        budget.consume(RunBudget.Kind.TOOL_CALL, 5);
        budget.consume(RunBudget.Kind.EVIDENCE, 1);
        budget.consume(RunBudget.Kind.SUBTASK, 3);

        assertThat(budget.consumed(RunBudget.Kind.STEP)).isEqualTo(3);
        assertThat(budget.remaining(RunBudget.Kind.STEP)).isEqualTo(7);
        assertThat(budget.remaining(RunBudget.Kind.TOOL_CALL)).isZero();
        assertThat(budget.remaining(RunBudget.Kind.SUBTASK)).isZero();
    }

    @Test
    void consumeExactlyToLimitSucceeds() {
        RunBudget budget = budget(2, 1, 1, 1);
        budget.consume(RunBudget.Kind.STEP, 2);
        assertThat(budget.remaining(RunBudget.Kind.STEP)).isZero();
    }

    /** 四类维度穷举：越过上限即抛 BudgetExhaustedException */
    @Test
    void exceedingAnyKindThrowsExhaustively() {
        for (RunBudget.Kind kind : RunBudget.Kind.values()) {
            RunBudget budget = budget(2, 2, 2, 2);
            budget.consume(kind, 2); // 恰好耗尽
            assertThatThrownBy(() -> budget.consume(kind, 1))
                    .as("%s 透支必须抛", kind)
                    .isInstanceOf(BudgetExhaustedException.class)
                    .hasMessageContaining(kind.name());
        }
    }

    @Test
    void kindSetIsExactlyFourCountingDimensions() {
        // TIME 不在计数维度内（TIME 是固定 deadline，见 checkDeadline）
        assertThat(RunBudget.Kind.values()).containsExactlyInAnyOrder(
                RunBudget.Kind.STEP, RunBudget.Kind.TOOL_CALL,
                RunBudget.Kind.EVIDENCE, RunBudget.Kind.SUBTASK);
    }

    @Test
    void failedConsumeLeavesNoPartialState() {
        RunBudget budget = budget(5, 5, 5, 5);
        budget.consume(RunBudget.Kind.TOOL_CALL, 4);
        assertThatThrownBy(() -> budget.consume(RunBudget.Kind.TOOL_CALL, 2))
                .isInstanceOf(BudgetExhaustedException.class);
        assertThat(budget.consumed(RunBudget.Kind.TOOL_CALL)).isEqualTo(4);
        assertThatCode(() -> budget.consume(RunBudget.Kind.TOOL_CALL, 1))
                .doesNotThrowAnyException();
    }

    @Test
    void kindsAreIndependent() {
        RunBudget budget = budget(1, 0, 0, 0);
        budget.consume(RunBudget.Kind.STEP, 1);
        assertThatThrownBy(() -> budget.consume(RunBudget.Kind.SUBTASK, 1))
                .isInstanceOf(BudgetExhaustedException.class);
        assertThat(budget.remaining(RunBudget.Kind.EVIDENCE)).isZero();
    }

    @Test
    void invalidAmountsAndNegativeLimitsRejected() {
        RunBudget budget = budget(1, 1, 1, 1);
        assertThatThrownBy(() -> budget.consume(RunBudget.Kind.STEP, 0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> budget.consume(RunBudget.Kind.STEP, -1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RunBudget(1, 1, 1, -1, Long.MAX_VALUE))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void zeroLimitIsImmediatelyExhausted() {
        RunBudget budget = budget(0, 0, 0, 0);
        for (RunBudget.Kind kind : RunBudget.Kind.values()) {
            assertThatThrownBy(() -> budget.consume(kind, 1))
                    .as("零上限 %s 第一次扣减即抛", kind)
                    .isInstanceOf(BudgetExhaustedException.class);
        }
    }

    /** long 溢出防绕过：used + amount 溢出必须判越界（抛 BudgetExhaustedException 而非放行） */
    @Test
    void overflowIsTreatedAsExhaustionNotBypass() {
        RunBudget budget = new RunBudget(Long.MAX_VALUE, 1, 1, 1, Long.MAX_VALUE);
        budget.consume(RunBudget.Kind.STEP, Long.MAX_VALUE - 1);
        assertThatThrownBy(() -> budget.consume(RunBudget.Kind.STEP, Long.MAX_VALUE))
                .as("used + amount 溢出不得绕过上限")
                .isInstanceOf(BudgetExhaustedException.class)
                .hasMessageContaining("STEP");
        // 失败扣减不记账
        assertThat(budget.consumed(RunBudget.Kind.STEP)).isEqualTo(Long.MAX_VALUE - 1);
    }

    /** TIME deadline 语义：now > deadline 超期；now == deadline 仍在窗口内 */
    @Test
    void deadlineSemantics() {
        RunBudget budget = new RunBudget(10, 10, 10, 10, 1_000_000L);
        assertThatCode(() -> budget.checkDeadline(999_999L)).doesNotThrowAnyException();
        assertThatCode(() -> budget.checkDeadline(1_000_000L))
                .as("now == deadline 仍在窗口内")
                .doesNotThrowAnyException();
        assertThatThrownBy(() -> budget.checkDeadline(1_000_001L))
                .as("now > deadline 超期")
                .isInstanceOf(BudgetExhaustedException.class)
                .hasMessageContaining("TIME")
                .hasMessageContaining("1000000");
    }

    @Test
    void deadlineIsNotAffectedByConsumes() {
        // deadline 是固定语义：计数扣减不改变 TIME 判定
        RunBudget budget = new RunBudget(10, 10, 10, 10, 100L);
        budget.consume(RunBudget.Kind.STEP, 10);
        assertThatCode(() -> budget.checkDeadline(100L)).doesNotThrowAnyException();
        assertThatThrownBy(() -> budget.checkDeadline(101L))
                .isInstanceOf(BudgetExhaustedException.class);
        assertThat(budget.deadlineEpochMillis()).isEqualTo(100L);
    }
}
