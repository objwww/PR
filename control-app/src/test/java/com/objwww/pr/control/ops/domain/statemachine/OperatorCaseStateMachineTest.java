package com.objwww.pr.control.ops.domain.statemachine;

import com.objwww.pr.control.ops.domain.model.CaseAction;
import com.objwww.pr.control.ops.domain.model.CaseStatus;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

/**
 * OperatorCase 状态机穷举（M5-11；ISA 18.2 式 action 驱动）：
 * 状态全集 = OPEN/ACKED/RESOLVED（与前端 mocks/cases.js status 值核对）；
 * 全部 (state, action) 组合穷举对齐迁移矩阵——矩阵即契约（TransitionTable 同纪律）。
 */
class OperatorCaseStateMachineTest {

    // ------------------------------------------------------------------ 合法迁移逐条

    @Test
    void legalTransitionsProduceExpectedStatusAndStableRuleNames() {
        assertThat(OperatorCaseStateMachine.apply(null, CaseAction.CREATE))
                .isEqualTo(new OperatorCaseStateMachine.Transition(CaseStatus.OPEN, "T00_CREATE"));
        assertThat(OperatorCaseStateMachine.apply(CaseStatus.OPEN, CaseAction.CLAIM))
                .isEqualTo(new OperatorCaseStateMachine.Transition(CaseStatus.ACKED, "T10_CLAIM"));
        assertThat(OperatorCaseStateMachine.apply(CaseStatus.OPEN, CaseAction.ACK))
                .isEqualTo(new OperatorCaseStateMachine.Transition(CaseStatus.ACKED, "T20_ACK"));
        assertThat(OperatorCaseStateMachine.apply(CaseStatus.OPEN, CaseAction.RESOLVE))
                .isEqualTo(new OperatorCaseStateMachine.Transition(CaseStatus.RESOLVED, "T30_RESOLVE"));
        assertThat(OperatorCaseStateMachine.apply(CaseStatus.ACKED, CaseAction.RESOLVE))
                .isEqualTo(new OperatorCaseStateMachine.Transition(CaseStatus.RESOLVED, "T31_RESOLVE"));
        assertThat(OperatorCaseStateMachine.apply(CaseStatus.OPEN, CaseAction.ASSIGN))
                .isEqualTo(new OperatorCaseStateMachine.Transition(CaseStatus.OPEN, "T40_ASSIGN"));
        assertThat(OperatorCaseStateMachine.apply(CaseStatus.ACKED, CaseAction.ASSIGN))
                .isEqualTo(new OperatorCaseStateMachine.Transition(CaseStatus.ACKED, "T41_ASSIGN"));
        // MERGE 任意态合法（同 fingerprint 再发生 = revision+1 不新建单；RESOLVED 只记复发不复活）
        assertThat(OperatorCaseStateMachine.apply(CaseStatus.OPEN, CaseAction.MERGE))
                .isEqualTo(new OperatorCaseStateMachine.Transition(CaseStatus.OPEN, "T50_MERGE"));
        assertThat(OperatorCaseStateMachine.apply(CaseStatus.ACKED, CaseAction.MERGE))
                .isEqualTo(new OperatorCaseStateMachine.Transition(CaseStatus.ACKED, "T51_MERGE"));
        assertThat(OperatorCaseStateMachine.apply(CaseStatus.RESOLVED, CaseAction.MERGE))
                .isEqualTo(new OperatorCaseStateMachine.Transition(CaseStatus.RESOLVED, "T52_MERGE"));
        // SLA 升级不改状态（OPEN/ACKED 可升级；升级面 = 审计行 + revision+1）
        assertThat(OperatorCaseStateMachine.apply(CaseStatus.OPEN, CaseAction.ESCALATE))
                .isEqualTo(new OperatorCaseStateMachine.Transition(CaseStatus.OPEN, "T60_ESCALATE"));
        assertThat(OperatorCaseStateMachine.apply(CaseStatus.ACKED, CaseAction.ESCALATE))
                .isEqualTo(new OperatorCaseStateMachine.Transition(CaseStatus.ACKED, "T61_ESCALATE"));
    }

    // ------------------------------------------------------------------ 非法迁移穷举拒绝

    @Test
    void allIllegalStateActionPairsAreRejectedExhaustively() {
        List<String> unexpectedLegal = new ArrayList<>();
        for (CaseStatus status : CaseStatus.values()) {
            for (CaseAction action : CaseAction.values()) {
                boolean legal = switch (action) {
                    case CREATE -> false; // CREATE 只接受 null 态（上案单测）
                    case CLAIM -> status == CaseStatus.OPEN;
                    case ACK -> status == CaseStatus.OPEN;
                    case RESOLVE -> status != CaseStatus.RESOLVED;
                    case ASSIGN -> status != CaseStatus.RESOLVED;
                    case MERGE -> true;
                    case ESCALATE -> status != CaseStatus.RESOLVED;
                };
                if (legal) {
                    continue;
                }
                try {
                    OperatorCaseStateMachine.apply(status, action);
                    unexpectedLegal.add(status + "×" + action);
                } catch (IllegalCaseActionException expected) {
                    assertThat(expected.currentStatus()).isEqualTo(status);
                    assertThat(expected.action()).isEqualTo(action);
                }
            }
        }
        assertThat(unexpectedLegal).as("矩阵外组合必须全部拒绝").isEmpty();
    }

    @Test
    void createFromNonNullStateIsRejected() {
        assertThatExceptionOfType(IllegalCaseActionException.class).isThrownBy(
                () -> OperatorCaseStateMachine.apply(CaseStatus.OPEN, CaseAction.CREATE));
        assertThatExceptionOfType(IllegalCaseActionException.class).isThrownBy(
                () -> OperatorCaseStateMachine.apply(CaseStatus.RESOLVED, CaseAction.CREATE));
    }

    @Test
    void nonCreateActionFromNullStateIsRejected() {
        assertThatExceptionOfType(IllegalCaseActionException.class).isThrownBy(
                () -> OperatorCaseStateMachine.apply(null, CaseAction.CLAIM));
    }
}
