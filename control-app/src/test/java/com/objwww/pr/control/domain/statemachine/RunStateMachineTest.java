package com.objwww.pr.control.domain.statemachine;

import com.objwww.pr.shared.IllegalTransitionException;
import com.objwww.pr.shared.RunState;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

class RunStateMachineTest {

    @Test
    void m0HappyPath() {
        assertDoesNotThrow(() -> {
            RunState s = RunStateMachine.transition(RunState.CREATED, RunState.SNAPSHOTTING);
            s = RunStateMachine.transition(s, RunState.REVIEWING);
            s = RunStateMachine.transition(s, RunState.REVIEW_COMPLETE);
            s = RunStateMachine.transition(s, RunState.READY_TO_PUBLISH);
            s = RunStateMachine.transition(s, RunState.PUBLISHING);
            RunStateMachine.transition(s, RunState.COMPLETED);
        });
    }

    @Test
    void anyNonTerminalCanBeSuperseded() {
        for (RunState s : RunState.values()) {
            if (!RunStateMachine.isTerminal(s)) {
                assertDoesNotThrow(() -> RunStateMachine.transition(s, RunState.SUPERSEDED));
            }
        }
    }

    @Test
    void terminalsAreSealed() {
        for (RunState terminal : new RunState[]{
                RunState.COMPLETED, RunState.COMPLETED_WITH_WARNINGS,
                RunState.FAILED, RunState.CANCELLED, RunState.SUPERSEDED}) {
            for (RunState to : RunState.values()) {
                assertThrows(IllegalTransitionException.class,
                        () -> RunStateMachine.transition(terminal, to));
            }
        }
    }
}
