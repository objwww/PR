package com.objwww.pr.control.alert.domain.budget;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * UT-AM4-08：RunBudget 扣减纯逻辑——四类上限穷举（恰好耗尽/越界/失败不留半状态）。
 */
class RunBudgetTest {

    @Test
    void consumeWithinLimitSucceeds() {
        RunBudget budget = new RunBudget(10, 5, 100, 60_000);
        budget.consume(RunBudget.Kind.STEP, 3);
        budget.consume(RunBudget.Kind.TOOL_CALL, 5);
        budget.consume(RunBudget.Kind.EVIDENCE, 1);
        budget.consume(RunBudget.Kind.TIME_MILLIS, 999);

        assertThat(budget.consumed(RunBudget.Kind.STEP)).isEqualTo(3);
        assertThat(budget.remaining(RunBudget.Kind.STEP)).isEqualTo(7);
        assertThat(budget.remaining(RunBudget.Kind.TOOL_CALL)).isZero();
    }

    @Test
    void consumeExactlyToLimitSucceeds() {
        RunBudget budget = new RunBudget(2, 1, 1, 1);
        budget.consume(RunBudget.Kind.STEP, 2);
        assertThat(budget.remaining(RunBudget.Kind.STEP)).isZero();
    }

    /** 四类维度穷举：越过上限即抛 BudgetExhaustedException */
    @Test
    void exceedingAnyKindThrowsExhaustively() {
        for (RunBudget.Kind kind : RunBudget.Kind.values()) {
            RunBudget budget = new RunBudget(2, 2, 2, 2);
            budget.consume(kind, 2); // 恰好耗尽
            assertThatThrownBy(() -> budget.consume(kind, 1))
                    .as("%s 透支必须抛", kind)
                    .isInstanceOf(BudgetExhaustedException.class)
                    .hasMessageContaining(kind.name());
        }
    }

    @Test
    void failedConsumeLeavesNoPartialState() {
        RunBudget budget = new RunBudget(5, 5, 5, 5);
        budget.consume(RunBudget.Kind.TOOL_CALL, 4);
        assertThatThrownBy(() -> budget.consume(RunBudget.Kind.TOOL_CALL, 2))
                .isInstanceOf(BudgetExhaustedException.class);
        // 失败扣减不记账——仍是 4，之后合法的 1 仍可扣
        assertThat(budget.consumed(RunBudget.Kind.TOOL_CALL)).isEqualTo(4);
        assertThatCode(() -> budget.consume(RunBudget.Kind.TOOL_CALL, 1))
                .doesNotThrowAnyException();
    }

    @Test
    void kindsAreIndependent() {
        RunBudget budget = new RunBudget(1, 0, 0, 0);
        budget.consume(RunBudget.Kind.STEP, 1);
        assertThatThrownBy(() -> budget.consume(RunBudget.Kind.TOOL_CALL, 1))
                .isInstanceOf(BudgetExhaustedException.class);
        // STEP 耗尽不影响 TIME 维度判定（TIME 上限 0 本就即尽）
        assertThat(budget.remaining(RunBudget.Kind.EVIDENCE)).isZero();
    }

    @Test
    void invalidAmountsAndNegativeLimitsRejected() {
        RunBudget budget = new RunBudget(1, 1, 1, 1);
        assertThatThrownBy(() -> budget.consume(RunBudget.Kind.STEP, 0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> budget.consume(RunBudget.Kind.STEP, -1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RunBudget(-1, 1, 1, 1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void zeroLimitIsImmediatelyExhausted() {
        RunBudget budget = new RunBudget(0, 0, 0, 0);
        for (RunBudget.Kind kind : RunBudget.Kind.values()) {
            assertThatThrownBy(() -> budget.consume(kind, 1))
                    .as("零上限 %s 第一次扣减即抛", kind)
                    .isInstanceOf(BudgetExhaustedException.class);
        }
    }
}
