package com.objwww.pr.shared;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.Set;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * UT-02：Outbox 八态机——合法迁移全覆盖 + 非法全抛。
 * 合法表在此硬编码（与被测实现独立），防"代码测代码"。
 */
class OutboxStateMachineTest {

    /** 与 docs/M0-技术方案.md §4.3 + v2.2 E1/E2 + EX-02/EX-09 对齐的合法迁移集 */
    private static final Set<String> LEGAL = Set.of(
            "PENDING->IN_FLIGHT",
            "PENDING->SUPERSEDED",      // 级联（v2.1 修订二）
            "PENDING->FAILED_TERMINAL", // schema 白名单拒绝（EX-09）
            "PENDING->MANUAL",          // 熔断
            "IN_FLIGHT->CONFIRMED",
            "IN_FLIGHT->RECONCILING",   // 崩溃窗口（§4.3）
            "IN_FLIGHT->RETRY_WAIT",
            "IN_FLIGHT->SUPERSEDED",    // 422 STALE_HEAD 确定性否定（§6.3/EX-02）
            "IN_FLIGHT->FAILED_TERMINAL", // 422 参数错误等确定性失败（EX-02）
            "IN_FLIGHT->MANUAL",
            "RECONCILING->CONFIRMED",
            "RECONCILING->RETRY_WAIT",
            "RECONCILING->MANUAL",
            "RETRY_WAIT->PENDING",      // 退避到期重领
            "RETRY_WAIT->SUPERSEDED",
            "RETRY_WAIT->MANUAL"
    );

    private static String key(OutboxState from, OutboxState to) {
        return from.name() + "->" + to.name();
    }

    static Stream<OutboxState[]> legalTransitions() {
        return LEGAL.stream().map(k -> {
            String[] parts = k.split("->");
            return new OutboxState[]{OutboxState.valueOf(parts[0]), OutboxState.valueOf(parts[1])};
        });
    }

    static Stream<OutboxState[]> illegalTransitions() {
        Stream.Builder<OutboxState[]> builder = Stream.builder();
        for (OutboxState from : OutboxState.values()) {
            for (OutboxState to : OutboxState.values()) {
                if (!LEGAL.contains(key(from, to))) {
                    builder.add(new OutboxState[]{from, to});
                }
            }
        }
        return builder.build();
    }

    @ParameterizedTest(name = "合法: {0} -> {1}")
    @MethodSource("legalTransitions")
    void legalTransitionAllowed(OutboxState from, OutboxState to) {
        assertEquals(to, OutboxStateMachine.transition(from, to));
        assertTrue(OutboxStateMachine.canTransition(from, to));
    }

    @ParameterizedTest(name = "非法: {0} -> {1}")
    @MethodSource("illegalTransitions")
    void illegalTransitionThrows(OutboxState from, OutboxState to) {
        assertThrows(IllegalTransitionException.class, () -> OutboxStateMachine.transition(from, to));
    }

    @Test
    void transitionTableIsFullySpecified() {
        // 8×8 = 64 个有序对全部被两张参数化表覆盖（含自迁移，自迁移非法）
        assertEquals(64, LEGAL.size() + illegalTransitions().count());
    }

    @Test
    void terminalStatesHaveNoOutgoingEdge() {
        // M0 封死：含 MANUAL；人工补偿迁移（MANUAL→SUPERSEDED/CONFIRMED）属 M7
        for (OutboxState terminal : new OutboxState[]{
                OutboxState.CONFIRMED, OutboxState.SUPERSEDED,
                OutboxState.FAILED_TERMINAL, OutboxState.MANUAL}) {
            assertTrue(OutboxStateMachine.isTerminal(terminal));
            for (OutboxState to : OutboxState.values()) {
                assertThrows(IllegalTransitionException.class,
                        () -> OutboxStateMachine.transition(terminal, to));
            }
        }
    }

    @Test
    void typicalLifecycleDoesNotThrow() {
        // 正常链路：PENDING→IN_FLIGHT→CONFIRMED；崩溃链路：IN_FLIGHT→RECONCILING→RETRY_WAIT→PENDING
        assertDoesNotThrow(() -> {
            OutboxState s = OutboxStateMachine.transition(OutboxState.PENDING, OutboxState.IN_FLIGHT);
            s = OutboxStateMachine.transition(s, OutboxState.RECONCILING);
            s = OutboxStateMachine.transition(s, OutboxState.RETRY_WAIT);
            s = OutboxStateMachine.transition(s, OutboxState.PENDING);
            OutboxStateMachine.transition(s, OutboxState.IN_FLIGHT);
        });
    }
}
