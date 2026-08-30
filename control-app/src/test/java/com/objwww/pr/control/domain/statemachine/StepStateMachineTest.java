package com.objwww.pr.control.domain.statemachine;

import com.objwww.pr.shared.IllegalTransitionException;
import com.objwww.pr.shared.StepState;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

class StepStateMachineTest {

    @Test
    void happyPathAndWaitingBackflow() {
        assertDoesNotThrow(() -> {
            StepState s = StepStateMachine.transition(StepState.READY, StepState.RUNNING);
            s = StepStateMachine.transition(s, StepState.WAITING);
            s = StepStateMachine.transition(s, StepState.READY);
            s = StepStateMachine.transition(s, StepState.RUNNING);
            StepStateMachine.transition(s, StepState.SUCCEEDED);
        });
    }

    @Test
    void illegalTransitionsThrow() {
        // 跳态
        assertThrows(IllegalTransitionException.class,
                () -> StepStateMachine.transition(StepState.READY, StepState.SUCCEEDED));
        // 终态出迁
        assertThrows(IllegalTransitionException.class,
                () -> StepStateMachine.transition(StepState.SUCCEEDED, StepState.RUNNING));
        assertThrows(IllegalTransitionException.class,
                () -> StepStateMachine.transition(StepState.SUPERSEDED, StepState.READY));
        // WAITING 不能直接完成
        assertThrows(IllegalTransitionException.class,
                () -> StepStateMachine.transition(StepState.WAITING, StepState.SUCCEEDED));
    }
}
