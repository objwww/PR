package com.objwww.pr.control.alert.domain.statemachine;

import com.objwww.pr.control.alert.domain.model.InboxState;

/**
 * alert_inbox 七态机（V3 webhook_inbox 同构 + PA-A3 隔离区）：
 * RECEIVED→{PROCESSING, IGNORED}（空组直接 IGNORED，EX-A10）；
 * PROCESSING→{PROCESSED, RETRY_WAIT, IGNORED, DEAD_LETTER}；
 * RETRY_WAIT→{PROCESSING, DEAD_LETTER}（attempt 耗尽）；
 * RECEIVED→QUARANTINED（PA-A3：扫描命中告警的隔离放行边——隔离行本身初始态直插
 * QUARANTINED 不经本边，本边供人工复核后放行重驱使用，入口随 AM8 管理面）；
 * QUARANTINED→RECEIVED（仅人工放行）；
 * PROCESSED/IGNORED/DEAD_LETTER/QUARANTINED 终态（租约过期的 PROCESSING 由回收置回
 * RECEIVED——即 PROCESSING→RECEIVED 也合法，仅回收路径可用）。
 */
public final class InboxStateMachine {

    private static final TransitionTable<InboxState> TABLE =
            TransitionTable.<InboxState>forEnum(InboxState.class)
                    .allow(InboxState.RECEIVED, InboxState.PROCESSING, InboxState.IGNORED,
                            InboxState.QUARANTINED)
                    .allow(InboxState.PROCESSING, InboxState.PROCESSED, InboxState.RETRY_WAIT,
                            InboxState.IGNORED, InboxState.DEAD_LETTER, InboxState.RECEIVED)
                    .allow(InboxState.RETRY_WAIT, InboxState.PROCESSING, InboxState.DEAD_LETTER)
                    .allow(InboxState.QUARANTINED, InboxState.RECEIVED)
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
