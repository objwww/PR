package com.objwww.pr.control.ops.domain.statemachine;

import com.objwww.pr.control.ops.domain.model.CaseAction;
import com.objwww.pr.control.ops.domain.model.CaseStatus;

import java.util.EnumMap;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * OperatorCase 状态机（M5-11；ISA 18.2 式 action 驱动——alerta isa_18_2.py 同构：
 * 迁移以动作为入参映射到新状态，转换规则名（T*）随审计/日志落账可回溯）。
 *
 * <p>迁移矩阵（唯一权威；UT 反射穷举对齐）：
 * <pre>
 *   CREATE   null      → OPEN      T00
 *   CLAIM    OPEN      → ACKED     T10（认领即认责，owner=actor）
 *   ACK      OPEN      → ACKED     T20（认领不改 owner）
 *   RESOLVE  OPEN      → RESOLVED  T30；ACKED → RESOLVED T31（结构化 reason 必填）
 *   ASSIGN   OPEN/ACKED → 原态     T40/T41（仅换 owner）
 *   MERGE    任意态    → 原态      T50/T51/T52（同 fingerprint 复发聚合；RESOLVED 只记复发不复活）
 *   ESCALATE OPEN/ACKED → 原态     T60/T61（SLA 升级不改状态，审计行 + revision+1）
 * </pre>
 * 矩阵外全部拒绝（RESOLVED 为吸收态：仅 MERGE 可落在其上）。
 */
public final class OperatorCaseStateMachine {

    /** 迁移结果：目标态 + 规则名（规则名落审计/结构化日志，转换可回溯） */
    public record Transition(CaseStatus to, String rule) {
        public Transition {
            Objects.requireNonNull(to, "to");
            Objects.requireNonNull(rule, "rule");
        }
    }

    private static final Map<CaseAction, Map<CaseStatus, Transition>> TABLE = new EnumMap<>(CaseAction.class);

    static {
        allow(CaseAction.CREATE, null, new Transition(CaseStatus.OPEN, "T00_CREATE"));
        allow(CaseAction.CLAIM, CaseStatus.OPEN, new Transition(CaseStatus.ACKED, "T10_CLAIM"));
        allow(CaseAction.ACK, CaseStatus.OPEN, new Transition(CaseStatus.ACKED, "T20_ACK"));
        allow(CaseAction.RESOLVE, CaseStatus.OPEN, new Transition(CaseStatus.RESOLVED, "T30_RESOLVE"));
        allow(CaseAction.RESOLVE, CaseStatus.ACKED, new Transition(CaseStatus.RESOLVED, "T31_RESOLVE"));
        allow(CaseAction.ASSIGN, CaseStatus.OPEN, new Transition(CaseStatus.OPEN, "T40_ASSIGN"));
        allow(CaseAction.ASSIGN, CaseStatus.ACKED, new Transition(CaseStatus.ACKED, "T41_ASSIGN"));
        allow(CaseAction.MERGE, CaseStatus.OPEN, new Transition(CaseStatus.OPEN, "T50_MERGE"));
        allow(CaseAction.MERGE, CaseStatus.ACKED, new Transition(CaseStatus.ACKED, "T51_MERGE"));
        allow(CaseAction.MERGE, CaseStatus.RESOLVED, new Transition(CaseStatus.RESOLVED, "T52_MERGE"));
        allow(CaseAction.ESCALATE, CaseStatus.OPEN, new Transition(CaseStatus.OPEN, "T60_ESCALATE"));
        allow(CaseAction.ESCALATE, CaseStatus.ACKED, new Transition(CaseStatus.ACKED, "T61_ESCALATE"));
    }

    private OperatorCaseStateMachine() {
    }

    /** action 驱动迁移；矩阵外组合抛 {@link IllegalCaseActionException} */
    public static Transition apply(CaseStatus current, CaseAction action) {
        Map<CaseStatus, Transition> row = TABLE.get(action);
        Transition transition = row == null ? null : row.get(current);
        if (transition == null) {
            throw new IllegalCaseActionException(current, action);
        }
        return transition;
    }

    private static void allow(CaseAction action, CaseStatus from, Transition transition) {
        // 行用 HashMap：CREATE 的 from=null 键在 EnumMap 会 NPE
        TABLE.computeIfAbsent(action, k -> new HashMap<>()).put(from, transition);
    }
}
