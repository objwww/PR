package com.objwww.pr.control.drill.domain.model;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * DR-02 演练事件（V86 drill_event 行的域形，insert-only 账本）：
 * 相位迁移 / 预检结果 / 停止请求审计 / 结论落档 / 执行注记。seq 由库 identity 生成
 * （单调序 = 事件游标锚），域对象 seq 可空（未落库行）。
 */
public record DrillEvent(
        UUID id,
        UUID drillId,
        Long seq,
        EventType eventType,
        String fromState,
        String toState,
        String actor,
        String payloadJson,
        Instant createdAt) {

    public enum EventType {
        PHASE_TRANSITION, PRECHECK_RESULT, STOP_REQUESTED, OUTCOME_RECORDED, WORKER_NOTE
    }

    public DrillEvent {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(drillId, "drillId");
        Objects.requireNonNull(eventType, "eventType");
        Objects.requireNonNull(actor, "actor");
        Objects.requireNonNull(payloadJson, "payloadJson");
        Objects.requireNonNull(createdAt, "createdAt");
    }

    public static DrillEvent phaseTransition(UUID drillId, DrillJob.State from,
                                             DrillJob.State to, String actor,
                                             String payloadJson, Instant now) {
        return new DrillEvent(UUID.randomUUID(), drillId, null,
                EventType.PHASE_TRANSITION, from.name(), to.name(), actor, payloadJson, now);
    }

    public static DrillEvent of(UUID drillId, EventType type, String actor,
                                String payloadJson, Instant now) {
        return new DrillEvent(UUID.randomUUID(), drillId, null, type, null, null,
                actor, payloadJson, now);
    }
}
