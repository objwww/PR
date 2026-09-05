package com.objwww.pr.arena.domain.statemachine;

import com.objwww.pr.arena.domain.model.BookingStatus;
import com.objwww.pr.arena.domain.model.FulfillmentState;
import com.objwww.pr.arena.domain.model.PaymentResult;
import com.objwww.pr.arena.domain.model.RefundState;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * M2-07 四台状态机反射穷举（L1）：对每台机枚举全部 (from, to) 组合——
 * 矩阵允许的边必须 requireTransition 放行，其余组合必须全部抛 IllegalTransitionException；
 * 同时断言矩阵的合法边集与本文档化的清单精确一致（防静默加边）。
 */
class StateMachineExhaustiveTest {

    @Test
    void bookingMachineExhaustive() {
        Set<List<Object>> legal = Set.of(
                edge(BookingStatus.class, "CREATED", "ENABLED"),
                edge(BookingStatus.class, "CREATED", "DISCARDED"),
                edge(BookingStatus.class, "ENABLED", "DISCARDED"));
        assertExhaustive(BookingStateMachine.table(), BookingStatus.class, legal);
    }

    @Test
    void payMachineExhaustive() {
        Set<List<Object>> legal = Set.of(
                edge(PaymentResult.class, "INITIATED", "SUCCEEDED"),
                edge(PaymentResult.class, "INITIATED", "DECLINED"),
                edge(PaymentResult.class, "INITIATED", "UNKNOWN"),
                edge(PaymentResult.class, "UNKNOWN", "SUCCEEDED"),
                edge(PaymentResult.class, "UNKNOWN", "DECLINED"),
                edge(PaymentResult.class, "UNKNOWN", "RECONCILING"),
                edge(PaymentResult.class, "RECONCILING", "SUCCEEDED"),
                edge(PaymentResult.class, "RECONCILING", "DECLINED"));
        assertExhaustive(PayStateMachine.table(), PaymentResult.class, legal);
    }

    @Test
    void refundMachineExhaustive() {
        Set<List<Object>> legal = Set.of(
                edge(RefundState.class, "REQUESTED", "APPROVED"),
                edge(RefundState.class, "REQUESTED", "REJECTED"),
                edge(RefundState.class, "REQUESTED", "CANCELLED"),
                edge(RefundState.class, "APPROVED", "REFUNDING"),
                edge(RefundState.class, "APPROVED", "CANCELLED"),
                edge(RefundState.class, "REFUNDING", "SUCCEEDED"),
                edge(RefundState.class, "REFUNDING", "FAILED"),
                edge(RefundState.class, "FAILED", "REFUNDING"));
        assertExhaustive(RefundStateMachine.table(), RefundState.class, legal);
    }

    @Test
    void fulfillmentMachineExhaustive() {
        Set<List<Object>> legal = Set.of(
                edge(FulfillmentState.class, "CONFIRMING", "CONFIRMED"),
                edge(FulfillmentState.class, "CONFIRMING", "NO_ROOM"),
                edge(FulfillmentState.class, "CONFIRMING", "CANCELLED"),
                edge(FulfillmentState.class, "NO_ROOM", "CANCELLED"));
        assertExhaustive(FulfillmentStateMachine.table(), FulfillmentState.class, legal);
    }

    // ------------------------------------------------------------------ 通用穷举

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static <S extends Enum<S>> void assertExhaustive(TransitionTable<S> table,
                                                             Class<S> type,
                                                             Set<List<Object>> legalEdges) {
        Set<List<Object>> matrixEdges = new HashSet<>();
        for (S from : type.getEnumConstants()) {
            for (S to : type.getEnumConstants()) {
                boolean allowed = table.allowed(from, to);
                matrixEdges.add(List.of(from, to));
                if (allowed) {
                    assertThat(legalEdges).as("%s→%s 应在文档化合法边清单内", from, to)
                            .contains(List.of(from, to));
                    table.requireTransition(from, to);
                } else {
                    assertThatThrownBy(() -> table.requireTransition(from, to))
                            .as("%s→%s 应判非法", from, to)
                            .isInstanceOf(com.objwww.pr.shared.IllegalTransitionException.class);
                }
            }
        }
        assertThat(matrixEdges).as("穷举组合数 = n²").hasSize(type.getEnumConstants().length
                * type.getEnumConstants().length);
        assertThat(legalEdges).allSatisfy(e -> assertThat(matrixEdges).contains(e));
    }

    private static <S extends Enum<S>> List<Object> edge(Class<S> type, String from, String to) {
        return List.of(Enum.valueOf(type, from), Enum.valueOf(type, to));
    }

    @Test
    void machinesExposeOnlyStaticTables() {
        // 四台机无实例化面（纯函数域件）
        for (Class<?> machine : List.of(BookingStateMachine.class, PayStateMachine.class,
                RefundStateMachine.class, FulfillmentStateMachine.class)) {
            List<Method> publicMethods = new ArrayList<>();
            for (Method m : machine.getDeclaredMethods()) {
                if (java.lang.reflect.Modifier.isPublic(m.getModifiers())) {
                    publicMethods.add(m);
                }
            }
            assertThat(publicMethods).as("%s 只暴露静态方法", machine.getSimpleName())
                    .allSatisfy(m -> assertThat(java.lang.reflect.Modifier
                            .isStatic(m.getModifiers())).isTrue());
            assertThat(machine.isAnnotationPresent(
                    org.springframework.stereotype.Component.class))
                    .as("domain 零 Spring 注解（另由 ArchUnit 兜底）").isFalse();
        }
    }
}
