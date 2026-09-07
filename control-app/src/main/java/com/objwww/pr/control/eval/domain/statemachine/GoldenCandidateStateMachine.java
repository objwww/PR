package com.objwww.pr.control.eval.domain.statemachine;

import com.objwww.pr.control.alert.domain.statemachine.TransitionTable;
import com.objwww.pr.control.eval.domain.model.GoldenCandidateState;

/**
 * Golden Candidate 状态机（M5-03，INV-AM5-2"发布/拒绝/撤回状态机无旁路"）：
 * DRAFT→{REVIEW, WITHDRAWN}；REVIEW→{PUBLISHED, REJECTED, WITHDRAWN}；
 * PUBLISHED/REJECTED/WITHDRAWN 终态无出边。矩阵唯一权威，UT 反射穷举对齐。
 * 复用 alert 域 TransitionTable 通用件（本模块内共享内核）。
 */
public final class GoldenCandidateStateMachine {

    private static final TransitionTable<GoldenCandidateState> TABLE =
            TransitionTable.<GoldenCandidateState>forEnum(GoldenCandidateState.class)
                    .allow(GoldenCandidateState.DRAFT, GoldenCandidateState.REVIEW,
                            GoldenCandidateState.WITHDRAWN)
                    .allow(GoldenCandidateState.REVIEW, GoldenCandidateState.PUBLISHED,
                            GoldenCandidateState.REJECTED, GoldenCandidateState.WITHDRAWN)
                    .build();

    private GoldenCandidateStateMachine() {
    }

    public static boolean allowed(GoldenCandidateState from, GoldenCandidateState to) {
        return TABLE.allowed(from, to);
    }

    public static void requireTransition(GoldenCandidateState from, GoldenCandidateState to) {
        TABLE.requireTransition(from, to);
    }
}
