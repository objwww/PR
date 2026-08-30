package com.objwww.pr.control.domain.ai;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * T07 预算校验：请求 maxTokens 超单次硬上限即拒；实际 completion 用量超预算即违约。
 */
class ModelBudgetGuardTest {

    private final ModelBudgetGuard guard = new ModelBudgetGuard(1_000);

    private ModelRequest request(int maxTokens) {
        return new ModelRequest("review this diff", maxTokens, Duration.ofSeconds(30));
    }

    @Test
    void rejectsRequestOverHardCap() {
        assertThrows(ModelBudgetExceededException.class, () -> guard.validate(request(1_001)));
    }

    @Test
    void acceptsRequestAtHardCap() {
        assertDoesNotThrow(() -> guard.validate(request(1_000)));
    }

    @Test
    void rejectsActualCompletionOverBudget() {
        TokenUsage over = new TokenUsage(100, 501, 601);
        assertThrows(ModelBudgetExceededException.class,
                () -> guard.checkUsage(request(500), over));
    }

    @Test
    void acceptsUsageWithinBudget() {
        assertDoesNotThrow(() -> guard.checkUsage(request(500), new TokenUsage(100, 500, 600)));
    }

    @Test
    void requestRejectsBlankPromptAndNonPositiveBudget() {
        assertThrows(IllegalArgumentException.class,
                () -> new ModelRequest(" ", 100, Duration.ofSeconds(1)));
        assertThrows(IllegalArgumentException.class,
                () -> new ModelRequest("p", 0, Duration.ofSeconds(1)));
        assertThrows(IllegalArgumentException.class,
                () -> new ModelRequest("p", 100, Duration.ZERO));
    }

    @Test
    void defaultHardCapIs32k() {
        assertEquals(32_000, new ModelBudgetGuard().hardMaxTokens());
    }
}
