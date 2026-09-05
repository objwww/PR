package com.objwww.pr.arena.domain.model;

import java.util.List;
import java.util.UUID;

/**
 * 补偿事件（业务事务内生产的 outbox 行，M2-12）：携带反向回补计划——
 * 只引用台账 DEDUCT 行的 (resourceType, deductionSeq, quantity)，worker 按严格逆序回补。
 */
public record CompensationEvent(
        UUID id,
        UUID orderId,
        List<PlanEntry> plan,
        OutboxState state) {

    /** 回补计划项（与台账 DEDUCT 行一一对应） */
    public record PlanEntry(ResourceType resourceType, int deductionSeq, int quantity) {
    }

    public static CompensationEvent pending(UUID orderId, List<PlanEntry> plan) {
        return new CompensationEvent(UUID.randomUUID(), orderId, List.copyOf(plan),
                OutboxState.PENDING);
    }

    /** 反向回补序：计划严格逆序（后扣的先补，§6.3） */
    public List<PlanEntry> reversedPlan() {
        return plan.stream()
                .sorted((a, b) -> Integer.compare(b.deductionSeq(), a.deductionSeq()))
                .toList();
    }
}
