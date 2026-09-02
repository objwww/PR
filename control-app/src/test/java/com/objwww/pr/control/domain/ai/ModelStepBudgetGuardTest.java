package com.objwww.pr.control.domain.ai;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * ModelStepBudgetGuard 四预算体系：prompt 预估/物理调用/Step 总 token/deadline + inline 等待上限。
 * 各自断言边界：恰好等于上限允许、超 1 拒绝。
 */
class ModelStepBudgetGuardTest {

    private static final Instant T0 = Instant.parse("2026-01-01T00:00:00Z");

    private static ModelStepBudgetGuard guard(int maxCalls, int maxPrompt, int maxCompletion,
                                              int maxTotal, Duration deadline, Duration inlineMax) {
        return new ModelStepBudgetGuard(maxCalls, maxPrompt, maxCompletion, maxTotal,
                deadline, inlineMax, T0);
    }

    private static ModelStepBudgetGuard generous() {
        return guard(10, 1_000, 1_000, 10_000, Duration.ofSeconds(30), Duration.ofSeconds(5));
    }

    @Test
    void constructorRejectsNonPositiveBudgets() {
        assertThatThrownBy(() -> guard(0, 1, 1, 1, Duration.ofSeconds(1), Duration.ofSeconds(1)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> guard(1, 0, 1, 1, Duration.ofSeconds(1), Duration.ofSeconds(1)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> guard(1, 1, 0, 1, Duration.ofSeconds(1), Duration.ofSeconds(1)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> guard(1, 1, 1, 0, Duration.ofSeconds(1), Duration.ofSeconds(1)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void promptEstimateAtLimitAllowedOverByOneRejected() {
        ModelStepBudgetGuard g = generous();
        assertThatCode(() -> g.checkPromptEstimate(1_000)).doesNotThrowAnyException();
        assertThatThrownBy(() -> g.checkPromptEstimate(1_001))
                .isInstanceOf(ModelBudgetExceededException.class)
                .hasMessageContaining("max-prompt-tokens-per-call");
    }

    @Test
    void physicalCallBudgetExhaustsAfterExactLimit() {
        ModelStepBudgetGuard g = guard(2, 1_000, 1_000, 10_000,
                Duration.ofSeconds(30), Duration.ofSeconds(5));
        assertThat(g.hasRemainingCalls()).isTrue();
        g.recordCallStarted();
        assertThat(g.hasRemainingCalls()).isTrue();
        g.recordCallStarted();
        assertThat(g.hasRemainingCalls()).isFalse();
        assertThat(g.physicalCallsUsed()).isEqualTo(2);
    }

    @Test
    void totalTokensAccumulateAcrossSuccessfulCallsAndRejectOverLimit() {
        ModelStepBudgetGuard g = guard(10, 1_000, 1_000, 100,
                Duration.ofSeconds(30), Duration.ofSeconds(5));
        g.recordUsageAndCheck(new TokenUsage(30, 30, 60));
        g.recordUsageAndCheck(new TokenUsage(20, 20, 40)); // 累计恰好 100：允许
        assertThat(g.stepTotalTokensUsed()).isEqualTo(100);
        // 超 1 拒绝；usage 仍累计（钱已花，事实先记）
        assertThatThrownBy(() -> g.recordUsageAndCheck(new TokenUsage(1, 0, 1)))
                .isInstanceOf(ModelBudgetExceededException.class)
                .hasMessageContaining("max-total-tokens-per-step");
        assertThat(g.stepTotalTokensUsed()).isEqualTo(101);
    }

    @Test
    void deadlineRemainingDecreasesAndClampsToZero() {
        ModelStepBudgetGuard g = guard(10, 1_000, 1_000, 10_000,
                Duration.ofSeconds(30), Duration.ofSeconds(5));
        assertThat(g.remainingDeadline(T0.plusSeconds(10))).isEqualTo(Duration.ofSeconds(20));
        // 恰好到点：耗尽（ZERO，G2 判 isZero）
        assertThat(g.remainingDeadline(T0.plusSeconds(30))).isEqualTo(Duration.ZERO);
        // 越过 deadline：负值钳位为 ZERO
        assertThat(g.remainingDeadline(T0.plusSeconds(31))).isEqualTo(Duration.ZERO);
    }

    @Test
    void inlineDelayAtLimitAllowedOverByOneMsRejected() {
        ModelStepBudgetGuard g = generous();
        assertThat(g.exceedsInlineDelay(Duration.ofSeconds(5))).isFalse();
        assertThat(g.exceedsInlineDelay(Duration.ofSeconds(5).plusMillis(1))).isTrue();
    }

    @Test
    void completionBudgetExposedAsAccessor() {
        assertThat(generous().maxCompletionTokensPerCall()).isEqualTo(1_000);
    }
}
