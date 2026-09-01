package com.objwww.pr.control.domain.service;

import com.objwww.pr.control.domain.statemachine.RunStateMachine;
import com.objwww.pr.control.domain.statemachine.StepStateMachine;
import com.objwww.pr.shared.ExecutionEvent;
import com.objwww.pr.shared.RunState;
import com.objwww.pr.shared.StepState;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * 投影折叠器（domain 服务，纯逻辑）：fold(events) → RunProjection。
 * 输入事件必须按 position 升序（由 ExecutionEventRepository.findByRunIdOrdered 保证）。
 * 状态推进过状态机校验——账本事件流本身非法时 fold 直接抛异常，不产生脏投影。
 */
public final class Projector {

    /** STEP_RESULT payload 键：目标 StepState 名 */
    public static final String KEY_STEP_STATE = "step_state";
    /** RUN_STATE_CHANGED payload 键：目标 RunState 名 */
    public static final String KEY_RUN_STATE = "run_state";
    /** PUBLICATION_CONFIRMED payload 键：outbox operation_id */
    public static final String KEY_OPERATION_ID = "operation_id";

    public RunProjection fold(List<ExecutionEvent> events) {
        Objects.requireNonNull(events, "events");
        RunState runState = null;
        Map<UUID, StepState> stepStates = new LinkedHashMap<>();
        Set<UUID> publishedOperationIds = new LinkedHashSet<>();

        for (ExecutionEvent event : events) {
            switch (event.eventType()) {
                case RUN_CREATED -> runState = RunState.CREATED;
                case RUN_STATE_CHANGED -> {
                    requireRunCreated(runState, event);
                    RunState target = RunState.valueOf(required(event, KEY_RUN_STATE));
                    if (runState == RunState.CREATED
                            && "REPAIR".equals(event.payload().get("run_mode"))
                            && (target == RunState.COMPLETED || target == RunState.FAILED)) {
                        runState = target;
                    } else {
                        runState = RunStateMachine.transition(runState, target);
                    }
                }
                case REVISION_INVALIDATED -> {
                    requireRunCreated(runState, event);
                    // 已到终态的 Run 不被换届改写（历史事实）；非终态一律 SUPERSEDED
                    if (!RunStateMachine.isTerminal(runState)) {
                        runState = RunStateMachine.transition(runState, RunState.SUPERSEDED);
                    }
                }
                case STEP_RESULT -> {
                    requireRunCreated(runState, event);
                    UUID stepId = Objects.requireNonNull(event.stepId(), "STEP_RESULT 缺 step_id: " + event.eventId());
                    StepState target = StepState.valueOf(required(event, KEY_STEP_STATE));
                    StepState previous = stepStates.get(stepId);
                    // 首条结果事件直接落；已有状态时迁移必须合法
                    stepStates.put(stepId, previous == null ? target : StepStateMachine.transition(previous, target));
                }
                case PUBLICATION_CONFIRMED ->
                        publishedOperationIds.add(UUID.fromString(required(event, KEY_OPERATION_ID)));
                default -> {
                    // PUBLICATION_REQUESTED / PUBLICATION_OUTCOME_UNKNOWN / SEQUENCE_GAP_DETECTED
                    // / SAFETY_REJECTED / BUDGET_EXCEEDED：只记事实，不改投影
                }
            }
        }
        return new RunProjection(runState, stepStates, publishedOperationIds);
    }

    /**
     * M6 投影重建入口（回放 execution_event 全量流）。M0 不实现：
     * V1 已冻结不新建投影表，可变实体行即持久投影；本签名仅为 M6 预留挂载点，
     * 届时实现 = 加载全量事件后调 {@link #fold}。
     */
    public RunProjection rebuild(UUID reviewRunId) {
        throw new UnsupportedOperationException("投影重建属 M6，M0 只保留签名");
    }

    private static void requireRunCreated(RunState runState, ExecutionEvent event) {
        if (runState == null) {
            throw new IllegalStateException("账本事件流缺 RUN_CREATED 前置事件，事件: " + event.eventId());
        }
    }

    private static String required(ExecutionEvent event, String key) {
        Object value = event.payload().get(key);
        if (value == null) {
            throw new IllegalStateException("事件 payload 缺键 " + key + ": " + event.eventId());
        }
        return value.toString();
    }
}
