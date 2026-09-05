package com.objwww.pr.control.alert.domain.statemachine;

import com.objwww.pr.control.alert.domain.model.PublicationState;

/**
 * report_publication 六态机（V9 ck_publication_state 对齐；M3-09 冻结）：
 * PENDING→{READY, SUPPRESSED}（出生后就绪/出生即抑制）；
 * READY→{SENT, RETRY_WAIT, DEAD}（全渠道送达 / 429·5xx 退避 / 4xx·耗尽终态）；
 * RETRY_WAIT→{READY, DEAD}（退避到期重投 / 退避耗尽）；
 * SENT/DEAD/SUPPRESSED 三终态无出边。
 *
 * <p>UNKNOWN（对账未决）不进本机——UNKNOWN 是 outbox 行的观测态，不自动重发意味着
 * 停在 RETRY_WAIT 等人工，没有新的合法迁移（M3-23 语义）。rca_report 本体不可变，
 * 发布状态只活在 publish 侧（BA-10②）。
 */
public final class ReportPublicationStateMachine {

    private static final TransitionTable<PublicationState> TABLE =
            TransitionTable.<PublicationState>forEnum(PublicationState.class)
                    .allow(PublicationState.PENDING, PublicationState.READY,
                            PublicationState.SUPPRESSED)
                    .allow(PublicationState.READY, PublicationState.SENT,
                            PublicationState.RETRY_WAIT, PublicationState.DEAD)
                    .allow(PublicationState.RETRY_WAIT, PublicationState.READY,
                            PublicationState.DEAD)
                    .build();

    private ReportPublicationStateMachine() {
    }

    public static boolean allowed(PublicationState from, PublicationState to) {
        return TABLE.allowed(from, to);
    }

    public static void requireTransition(PublicationState from, PublicationState to) {
        TABLE.requireTransition(from, to);
    }
}
