package com.objwww.pr.control.domain.statemachine;

import com.objwww.pr.shared.AttemptStatus;
import com.objwww.pr.shared.IllegalTransitionException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AttemptStatusMachineTest {

    @Test
    void startedConvergesToAnyTerminal() {
        for (AttemptStatus terminal : new AttemptStatus[]{
                AttemptStatus.SUCCEEDED, AttemptStatus.FAILED_RETRYABLE,
                AttemptStatus.FAILED_TERMINAL, AttemptStatus.ABANDONED, AttemptStatus.STALE}) {
            assertDoesNotThrow(() -> AttemptStatusMachine.transition(AttemptStatus.STARTED, terminal));
        }
    }

    @Test
    void terminalsAreSealed() {
        assertThrows(IllegalTransitionException.class,
                () -> AttemptStatusMachine.transition(AttemptStatus.STALE, AttemptStatus.STARTED));
        assertThrows(IllegalTransitionException.class,
                () -> AttemptStatusMachine.transition(AttemptStatus.FAILED_RETRYABLE, AttemptStatus.SUCCEEDED));
        assertThrows(IllegalTransitionException.class,
                () -> AttemptStatusMachine.transition(AttemptStatus.STARTED, AttemptStatus.STARTED));
    }
}
