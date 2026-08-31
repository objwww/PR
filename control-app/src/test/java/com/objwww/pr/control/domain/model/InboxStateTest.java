package com.objwww.pr.control.domain.model;

import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * UT-11：InboxState 六态迁移——合法迁移全覆盖（6×6 穷举），
 * PROCESSED/IGNORED/DEAD_LETTER 终态拒绝任何迁出。
 */
class InboxStateTest {

    /** 合法迁移表（与 InboxState 类注释逐条对应；PROCESSING→PROCESSING 为崩溃回收重领） */
    private static final Map<InboxState, Set<InboxState>> LEGAL = Map.of(
            InboxState.RECEIVED, Set.of(InboxState.PROCESSING),
            InboxState.PROCESSING, Set.of(InboxState.PROCESSING, InboxState.PROCESSED,
                    InboxState.RETRY_WAIT, InboxState.DEAD_LETTER, InboxState.IGNORED),
            InboxState.RETRY_WAIT, Set.of(InboxState.PROCESSING),
            InboxState.PROCESSED, Set.of(),
            InboxState.IGNORED, Set.of(),
            InboxState.DEAD_LETTER, Set.of());

    @Test
    void legalTransitionsExhaustive() {
        for (InboxState from : InboxState.values()) {
            for (InboxState to : InboxState.values()) {
                if (LEGAL.get(from).contains(to)) {
                    assertThat(from.canTransitionTo(to)).isTrue();
                    assertDoesNotThrow(() -> from.transitionTo(to));
                    assertThat(from.transitionTo(to)).isEqualTo(to);
                } else {
                    assertThat(from.canTransitionTo(to)).isFalse();
                    assertThrows(IllegalStateException.class, () -> from.transitionTo(to));
                }
            }
        }
    }

    @Test
    void terminalsAreSealed() {
        for (InboxState terminal : new InboxState[]{
                InboxState.PROCESSED, InboxState.IGNORED, InboxState.DEAD_LETTER}) {
            assertThat(terminal.isTerminal()).isTrue();
            for (InboxState to : InboxState.values()) {
                assertThrows(IllegalStateException.class, () -> terminal.transitionTo(to));
            }
        }
    }

    @Test
    void nonTerminalsAreNotMarkedTerminal() {
        for (InboxState s : new InboxState[]{
                InboxState.RECEIVED, InboxState.PROCESSING, InboxState.RETRY_WAIT}) {
            assertThat(s.isTerminal()).isFalse();
        }
    }

    @Test
    void retryWaitReclaimPairIsLegal() {
        // UT-11 附带语义：RETRY_WAIT→PROCESSING 的一次循环必须伴随 attempt_count+1。
        // 迁移表只判状态对；计数递增由回写 SQL 保证（completeRetryWait/completeDeadLetter
        // 的 attempt_count = attempt_count+1，§4.2 失败路径），本测试锚定状态对合法。
        assertThat(InboxState.RETRY_WAIT.canTransitionTo(InboxState.PROCESSING)).isTrue();
        // 崩溃回收重领：PROCESSING→PROCESSING（租约语义，epoch+1 由 claim SQL 完成）
        assertThat(InboxState.PROCESSING.canTransitionTo(InboxState.PROCESSING)).isTrue();
    }
}
