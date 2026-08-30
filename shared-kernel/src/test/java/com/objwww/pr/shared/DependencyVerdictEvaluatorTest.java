package com.objwww.pr.shared;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * UT-04：v2.2 E3 依赖终态归类表 4 前置终态 × 3 dependency_mode 全组合。
 */
class DependencyVerdictEvaluatorTest {

    private final DependencyVerdictEvaluator evaluator = new DependencyVerdictEvaluator();

    @ParameterizedTest(name = "{0} + {1} -> {2}")
    @CsvSource({
            // CONFIRMED：全模式放行
            "CONFIRMED,       REQUIRE_CONFIRMED, PROCEED",
            "CONFIRMED,       REQUIRE_TERMINAL,  PROCEED",
            "CONFIRMED,       OPTIONAL,          PROCEED",
            // SUPERSEDED：REQUIRE_* 级联；OPTIONAL 不级联可放行
            "SUPERSEDED,      REQUIRE_CONFIRMED, CASCADE_SUPERSEDE",
            "SUPERSEDED,      REQUIRE_TERMINAL,  CASCADE_SUPERSEDE",
            "SUPERSEDED,      OPTIONAL,          PROCEED",
            // FAILED_TERMINAL：REQUIRE_CONFIRMED 前置不可达 → 本命令 SUPERSEDED；其余放行
            "FAILED_TERMINAL, REQUIRE_CONFIRMED, SUPERSEDE_SELF",
            "FAILED_TERMINAL, REQUIRE_TERMINAL,  PROCEED",
            "FAILED_TERMINAL, OPTIONAL,          PROCEED",
            // MANUAL：全模式等待人工判定（阻塞，保序 > 可用性）
            "MANUAL,          REQUIRE_CONFIRMED, WAIT_MANUAL",
            "MANUAL,          REQUIRE_TERMINAL,  WAIT_MANUAL",
            "MANUAL,          OPTIONAL,          WAIT_MANUAL",
    })
    void verdictTable(OutboxState prerequisite, DependencyMode mode, DependencyVerdict expected) {
        assertEquals(expected, evaluator.evaluate(prerequisite, mode));
    }

    @ParameterizedTest(name = "非终态前置不可判定: {0}")
    @EnumSource(names = {"PENDING", "IN_FLIGHT", "RECONCILING", "RETRY_WAIT"})
    void nonTerminalPrerequisiteRejected(OutboxState prerequisite) {
        assertThrows(IllegalArgumentException.class,
                () -> evaluator.evaluate(prerequisite, DependencyMode.REQUIRE_CONFIRMED));
    }

    @Test
    void nullArgumentsRejected() {
        assertThrows(NullPointerException.class,
                () -> evaluator.evaluate(null, DependencyMode.REQUIRE_CONFIRMED));
        assertThrows(NullPointerException.class,
                () -> evaluator.evaluate(OutboxState.CONFIRMED, null));
    }
}
