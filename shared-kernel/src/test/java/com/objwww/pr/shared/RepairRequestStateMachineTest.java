package com.objwww.pr.shared;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

class RepairRequestStateMachineTest {

    @Test
    void exhaustivelyEnforcesSevenBySevenMatrix() {
        Set<String> allowed = Set.of(
                "PENDING->APPROVED", "PENDING->DISPATCHED", "PENDING->RETRY_WAIT",
                "PENDING->FAILED_TERMINAL", "PENDING->EXPIRED",
                "APPROVED->DISPATCHED", "APPROVED->RETRY_WAIT",
                "APPROVED->FAILED_TERMINAL", "APPROVED->EXPIRED",
                "DISPATCHED->REPAIRED", "DISPATCHED->FAILED_TERMINAL", "DISPATCHED->EXPIRED",
                "RETRY_WAIT->DISPATCHED", "RETRY_WAIT->RETRY_WAIT",
                "RETRY_WAIT->FAILED_TERMINAL", "RETRY_WAIT->EXPIRED");
        for (RepairRequestState from : RepairRequestState.values()) {
            for (RepairRequestState to : RepairRequestState.values()) {
                boolean expected = allowed.contains(from + "->" + to);
                assertEquals(expected, RepairRequestStateMachine.canTransition(from, to));
                if (expected) assertEquals(to, RepairRequestStateMachine.transition(from, to));
                else assertThrows(IllegalStateException.class,
                        () -> RepairRequestStateMachine.transition(from, to));
            }
        }
        for (RepairRequestState terminal : Set.of(RepairRequestState.REPAIRED,
                RepairRequestState.FAILED_TERMINAL, RepairRequestState.EXPIRED)) {
            for (RepairRequestState to : RepairRequestState.values()) {
                assertFalse(RepairRequestStateMachine.canTransition(terminal, to));
            }
        }
    }
}
