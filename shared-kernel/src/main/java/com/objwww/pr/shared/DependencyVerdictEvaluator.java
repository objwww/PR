package com.objwww.pr.shared;

import java.util.Objects;

/**
 * v2.2 E3 依赖终态归类表（4 前置终态 × 3 dependency_mode 全组合）：
 *
 * <pre>
 * 前置终态 \ 模式     REQUIRE_CONFIRMED   REQUIRE_TERMINAL   OPTIONAL
 * CONFIRMED           放行                放行               放行
 * SUPERSEDED          级联 supersede      级联 supersede     不级联，可放行
 * FAILED_TERMINAL     本命令→SUPERSEDED   放行               放行
 * MANUAL              等待人工判定         等待人工判定        等待人工判定
 * </pre>
 */
public final class DependencyVerdictEvaluator {

    /**
     * @param prerequisiteState 前置命令状态，必须已到终态，否则抛 IllegalArgumentException
     */
    public DependencyVerdict evaluate(OutboxState prerequisiteState, DependencyMode mode) {
        Objects.requireNonNull(prerequisiteState, "prerequisiteState");
        Objects.requireNonNull(mode, "mode");
        if (!OutboxStateMachine.isTerminal(prerequisiteState)) {
            throw new IllegalArgumentException("前置命令尚未到终态，不可判定: " + prerequisiteState);
        }
        return switch (prerequisiteState) {
            case CONFIRMED -> DependencyVerdict.PROCEED;
            case MANUAL -> DependencyVerdict.WAIT_MANUAL;
            case SUPERSEDED -> mode == DependencyMode.OPTIONAL
                    ? DependencyVerdict.PROCEED
                    : DependencyVerdict.CASCADE_SUPERSEDE;
            case FAILED_TERMINAL -> mode == DependencyMode.REQUIRE_CONFIRMED
                    ? DependencyVerdict.SUPERSEDE_SELF
                    : DependencyVerdict.PROCEED;
            default -> throw new IllegalArgumentException("非终态: " + prerequisiteState);
        };
    }
}
