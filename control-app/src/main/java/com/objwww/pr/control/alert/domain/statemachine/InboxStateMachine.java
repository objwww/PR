package com.objwww.pr.control.alert.domain.statemachine;

import com.objwww.pr.control.alert.domain.model.InboxState;

/**
 * alert_inbox 六态机（V3 webhook_inbox 同构）：
 * RECEIVED→{PROCESSING, IGNORED}（空组直接 IGNORED，EX-A10）；
 * PROCESSING→{PROCESSED, RETRY_WAIT, IGNORED, DEAD_LETTER}；
 * RETRY_WAIT→{PROCESSING, DEAD_LETTER}（attempt 耗尽）；
 * PROCESSED/IGNORED/DEAD_LETTER 终态（租约过期的 PROCESSING 由回收置回 RECEIVED——
 * 即 PROCESSING→RECEIVED 也合法，仅回收路径可用）。
 */
public final class InboxStateMachine {

    private static final TransitionTable<InboxState> TABLE =
            TransitionTable.<InboxState>forEnum(InboxState.class)
                    .allow(InboxState.RECEIVED, InboxState.PROCESSING, InboxState.IGNORED)
                    .allow(InboxState.PROCESSING, InboxState.PROCESSED, InboxState.RETRY_WAIT,
                            InboxState.IGNORED, InboxState.DEAD_LETTER, InboxState.RECEIVED)
                    .allow(InboxState.RETRY_WAIT, InboxState.PROCESSING, InboxState.DEAD_LETTER)
                    .build();

    private InboxStateMachine() {
    }

    public static boolean allowed(InboxState from, InboxState to) {
        return TABLE.allowed(from, to);
    }

    public static void requireTransition(InboxState from, InboxState to) {
        TABLE.requireTransition(from, to);
    }
}
