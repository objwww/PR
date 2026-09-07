package com.objwww.pr.control.eval.domain.statemachine;

import com.objwww.pr.control.eval.domain.model.GoldenCandidateState;
import com.objwww.pr.shared.IllegalTransitionException;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.function.BiPredicate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Golden Candidate 状态机反射穷举（M5-03；INV-AM5-2"发布/拒绝/撤回状态机无旁路"）。
 * 期望矩阵抄自落码方案 §M5-03①（DRAFT→REVIEW→PUBLISHED/REJECTED/WITHDRAWN），
 * 代码漂移在此红。范式沿 AlertStateMachineTest。
 */
class GoldenCandidateStateMachineTest {

    private static final Map<GoldenCandidateState, Set<GoldenCandidateState>> EXPECTED = Map.of(
            GoldenCandidateState.DRAFT, Set.of(GoldenCandidateState.REVIEW,
                    GoldenCandidateState.WITHDRAWN),
            GoldenCandidateState.REVIEW, Set.of(GoldenCandidateState.PUBLISHED,
                    GoldenCandidateState.REJECTED, GoldenCandidateState.WITHDRAWN));

    @Test
    void machineIsExhaustivelyAlignedWithFrozenMatrix() {
        assertExhaustive(GoldenCandidateState.class, EXPECTED, GoldenCandidateStateMachine::allowed);
        assertIllegalThrows(GoldenCandidateState.class, EXPECTED,
                GoldenCandidateStateMachine::requireTransition);
    }

    @Test
    void terminalStatesHaveNoOutgoingEdgesEvenForDangerousBypasses() {
        // 最危险的三条旁路锚点：终态复活
        assertThatThrownBy(() -> GoldenCandidateStateMachine.requireTransition(
                GoldenCandidateState.PUBLISHED, GoldenCandidateState.REVIEW))
                .as("PUBLISHED 复活 = 旁路").isInstanceOf(IllegalTransitionException.class);
        assertThatThrownBy(() -> GoldenCandidateStateMachine.requireTransition(
                GoldenCandidateState.REJECTED, GoldenCandidateState.REVIEW))
                .as("REJECTED 复活 = 旁路（拒绝后须新建候选）")
                .isInstanceOf(IllegalTransitionException.class);
        assertThatThrownBy(() -> GoldenCandidateStateMachine.requireTransition(
                GoldenCandidateState.WITHDRAWN, GoldenCandidateState.DRAFT))
                .as("WITHDRAWN 复活 = 旁路").isInstanceOf(IllegalTransitionException.class);
        // 跳级：DRAFT 不得直达 PUBLISHED/REJECTED（未评审不得出结论）
        assertThatThrownBy(() -> GoldenCandidateStateMachine.requireTransition(
                GoldenCandidateState.DRAFT, GoldenCandidateState.PUBLISHED))
                .isInstanceOf(IllegalTransitionException.class);
        assertThat(GoldenCandidateStateMachine.allowed(
                GoldenCandidateState.DRAFT, GoldenCandidateState.REJECTED)).isFalse();
    }

    private static <S extends Enum<S>> void assertExhaustive(Class<S> type,
            Map<S, Set<S>> expected, BiPredicate<S, S> machine) {
        for (S from : type.getEnumConstants()) {
            for (S to : type.getEnumConstants()) {
                boolean want = expected.getOrDefault(from, Set.of()).contains(to);
                assertThat(machine.test(from, to))
                        .as("%s -> %s 应为 %s", from, to, want ? "允许" : "拒绝")
                        .isEqualTo(want);
            }
        }
    }

    private static <S extends Enum<S>> void assertIllegalThrows(Class<S> type,
            Map<S, Set<S>> expected, BiConsumer<S, S> require) {
        for (S from : type.getEnumConstants()) {
            for (S to : type.getEnumConstants()) {
                if (!expected.getOrDefault(from, Set.of()).contains(to)) {
                    assertThatThrownBy(() -> require.accept(from, to))
                            .as("%s -> %s 必须抛 IllegalTransitionException", from, to)
                            .isInstanceOf(IllegalTransitionException.class);
                }
            }
        }
    }
}
