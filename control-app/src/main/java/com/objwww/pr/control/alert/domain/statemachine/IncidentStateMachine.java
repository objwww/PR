package com.objwww.pr.control.alert.domain.statemachine;

import com.objwww.pr.control.alert.domain.model.IncidentStatus;

/**
 * Incident 二态事实机（FIRING↔RESOLVED；执行态不混入，评审 #2）。
 *
 * <p>generation 规则（§6.7 乱序策略）：firing→resolved 保持 generation；
 * resolved→firing（同 episode 内 firing 再现）= generation+1 新 episode。
 */
public final class IncidentStateMachine {

    private static final TransitionTable<IncidentStatus> TABLE =
            TransitionTable.<IncidentStatus>forEnum(IncidentStatus.class)
                    .allow(IncidentStatus.FIRING, IncidentStatus.RESOLVED)
                    .allow(IncidentStatus.RESOLVED, IncidentStatus.FIRING)
                    .build();

    private IncidentStateMachine() {
    }

    public static boolean allowed(IncidentStatus from, IncidentStatus to) {
        return TABLE.allowed(from, to);
    }

    public static void requireTransition(IncidentStatus from, IncidentStatus to) {
        TABLE.requireTransition(from, to);
    }

    /** 迁移后的 generation（resolved→firing 递增；firing→resolved 保持） */
    public static int nextGeneration(IncidentStatus from, IncidentStatus to, int currentGeneration) {
        requireTransition(from, to);
        return to == IncidentStatus.FIRING && from == IncidentStatus.RESOLVED
                ? currentGeneration + 1
                : currentGeneration;
    }
}
