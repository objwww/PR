package com.objwww.pr.control.domain.service;

import com.objwww.pr.shared.ExecutionEvent;
import com.objwww.pr.shared.ExecutionEventType;
import com.objwww.pr.shared.IllegalTransitionException;
import com.objwww.pr.shared.RunState;
import com.objwww.pr.shared.StepState;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * T04 验收：fold(events) == 预期投影状态。
 */
class ProjectorTest {

    private final Projector projector = new Projector();

    private static final UUID RUN_ID = UUID.randomUUID();
    private static final UUID REVISION_ID = UUID.randomUUID();
    private static final UUID STEP_ID = UUID.randomUUID();
    private static final UUID CORRELATION = UUID.randomUUID();

    private static ExecutionEvent event(ExecutionEventType type, UUID stepId, Map<String, Object> payload) {
        return new ExecutionEvent(UUID.randomUUID(), RUN_ID, REVISION_ID, stepId, null,
                type, ExecutionLedger.SCHEMA_VERSION, null, CORRELATION, "test", payload, Instant.now());
    }

    @Test
    void foldHappyPathEqualsExpectedProjection() {
        UUID operationId = UUID.randomUUID();
        List<ExecutionEvent> events = List.of(
                event(ExecutionEventType.RUN_CREATED, null, Map.of()),
                event(ExecutionEventType.RUN_STATE_CHANGED, null, Map.of("run_state", "SNAPSHOTTING")),
                event(ExecutionEventType.RUN_STATE_CHANGED, null, Map.of("run_state", "REVIEWING")),
                event(ExecutionEventType.STEP_RESULT, STEP_ID, Map.of("step_state", "SUCCEEDED")),
                event(ExecutionEventType.PUBLICATION_REQUESTED, STEP_ID,
                        Map.of("operation_id", operationId.toString())),
                event(ExecutionEventType.PUBLICATION_CONFIRMED, STEP_ID,
                        Map.of("operation_id", operationId.toString())),
                event(ExecutionEventType.RUN_STATE_CHANGED, null, Map.of("run_state", "REVIEW_COMPLETE")),
                event(ExecutionEventType.RUN_STATE_CHANGED, null, Map.of("run_state", "COMPLETED")));

        RunProjection projection = projector.fold(events);

        assertEquals(RunState.COMPLETED, projection.runState());
        assertEquals(Map.of(STEP_ID, StepState.SUCCEEDED), projection.stepStates());
        assertEquals(java.util.Set.of(operationId), projection.publishedOperationIds());
    }

    @Test
    void foldRevisionInvalidatedSupersedesRun() {
        RunProjection projection = projector.fold(List.of(
                event(ExecutionEventType.RUN_CREATED, null, Map.of()),
                event(ExecutionEventType.RUN_STATE_CHANGED, null, Map.of("run_state", "REVIEWING")),
                event(ExecutionEventType.REVISION_INVALIDATED, null, Map.of())));

        assertEquals(RunState.SUPERSEDED, projection.runState());
    }

    @Test
    void foldKeepsTerminalRunStateOnLateInvalidation() {
        // 已完成 Run 再收到换届事件：历史事实不被改写
        RunProjection projection = projector.fold(List.of(
                event(ExecutionEventType.RUN_CREATED, null, Map.of()),
                event(ExecutionEventType.RUN_STATE_CHANGED, null, Map.of("run_state", "REVIEWING")),
                event(ExecutionEventType.RUN_STATE_CHANGED, null, Map.of("run_state", "REVIEW_COMPLETE")),
                event(ExecutionEventType.RUN_STATE_CHANGED, null, Map.of("run_state", "COMPLETED")),
                event(ExecutionEventType.REVISION_INVALIDATED, null, Map.of())));

        assertEquals(RunState.COMPLETED, projection.runState());
    }

    @Test
    void factEventsDoNotAffectProjection() {
        // PUBLICATION_OUTCOME_UNKNOWN / SEQUENCE_GAP_DETECTED / SAFETY_REJECTED / BUDGET_EXCEEDED 只记事实
        RunProjection projection = projector.fold(List.of(
                event(ExecutionEventType.RUN_CREATED, null, Map.of()),
                event(ExecutionEventType.PUBLICATION_OUTCOME_UNKNOWN, STEP_ID, Map.of()),
                event(ExecutionEventType.SEQUENCE_GAP_DETECTED, null, Map.of("expected", 3, "actual", 5)),
                event(ExecutionEventType.SAFETY_REJECTED, null, Map.of()),
                event(ExecutionEventType.BUDGET_EXCEEDED, null, Map.of())));

        assertEquals(RunState.CREATED, projection.runState());
        assertTrue(projection.stepStates().isEmpty());
        assertTrue(projection.publishedOperationIds().isEmpty());
    }

    @Test
    void foldRejectsIllegalTransitionInEventStream() {
        // 账本流自身非法（COMPLETED 后又 REVIEWING）：fold 抛异常，不产生脏投影
        List<ExecutionEvent> events = List.of(
                event(ExecutionEventType.RUN_CREATED, null, Map.of()),
                event(ExecutionEventType.RUN_STATE_CHANGED, null, Map.of("run_state", "REVIEWING")),
                event(ExecutionEventType.RUN_STATE_CHANGED, null, Map.of("run_state", "REVIEW_COMPLETE")),
                event(ExecutionEventType.RUN_STATE_CHANGED, null, Map.of("run_state", "COMPLETED")),
                event(ExecutionEventType.RUN_STATE_CHANGED, null, Map.of("run_state", "REVIEWING")));

        assertThrows(IllegalTransitionException.class, () -> projector.fold(events));
    }

    @Test
    void foldRejectsStreamWithoutRunCreated() {
        assertThrows(IllegalStateException.class, () -> projector.fold(List.of(
                event(ExecutionEventType.RUN_STATE_CHANGED, null, Map.of("run_state", "REVIEWING")))));
    }

    @Test
    void emptyStreamYieldsEmptyProjection() {
        RunProjection projection = projector.fold(List.of());
        assertNull(projection.runState());
        assertTrue(projection.stepStates().isEmpty());
        assertTrue(projection.publishedOperationIds().isEmpty());
    }

    @Test
    void rebuildReservedForM6() {
        assertThrows(UnsupportedOperationException.class, () -> projector.rebuild(RUN_ID));
    }
}
