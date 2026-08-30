package com.objwww.pr.publisher.domain.service;

import com.objwww.pr.shared.ExecutionEventType;

import java.util.Map;

/**
 * T3-A 决策结果（{@link PublicationGate} 纯函数产出，PublicationStore 在同一事务内应用）。
 *
 * <p>动作语义：
 * <ul>
 *   <li>PROCEED —— →IN_FLIGHT，提交后才允许触网（不持 DB 锁跨外部调用）；</li>
 *   <li>MARK_SUPERSEDED —— →SUPERSEDED + 级联 REQUIRE_* 依赖方（跳过 OPTIONAL，E3）+ 游标推进；</li>
 *   <li>MARK_FAILED_TERMINAL —— →FAILED_TERMINAL + 游标推进 + 事件（schema 白名单拒绝走此路，EX-09）；</li>
 *   <li>DEFER —— 不执行不迁移，仅释放租约（前置未到终态 / 前置 MANUAL / epoch 超前可重试，EX-05）；</li>
 *   <li>RECORD_GAP —— 记 SEQUENCE_GAP_DETECTED 事件 + 释放租约，不执行（E2，跳号不静默）。</li>
 * </ul>
 */
public record T3ADecision(
        Action action,
        String errorCode,
        ExecutionEventType eventType,
        Map<String, Object> eventPayload) {

    public enum Action {PROCEED, MARK_SUPERSEDED, MARK_FAILED_TERMINAL, DEFER, RECORD_GAP}

    public T3ADecision {
        eventPayload = eventPayload == null ? Map.of() : Map.copyOf(eventPayload);
    }

    public static T3ADecision proceed() {
        return new T3ADecision(Action.PROCEED, null, null, Map.of());
    }

    /** 级联/自身/fence 三路径统一落 SUPERSEDED（差异只在 errorCode 取证） */
    public static T3ADecision supersede(String errorCode) {
        return new T3ADecision(Action.MARK_SUPERSEDED, errorCode, null, Map.of());
    }

    /** schema/白名单拒绝：FAILED_TERMINAL + SAFETY_REJECTED 告警事件（EX-09，fail-closed E5） */
    public static T3ADecision rejectSafety(String errorCode, Map<String, Object> eventPayload) {
        return new T3ADecision(Action.MARK_FAILED_TERMINAL, errorCode,
                ExecutionEventType.SAFETY_REJECTED, eventPayload);
    }

    public static T3ADecision defer() {
        return new T3ADecision(Action.DEFER, null, null, Map.of());
    }

    public static T3ADecision gap(Map<String, Object> eventPayload) {
        return new T3ADecision(Action.RECORD_GAP, "SEQUENCE_GAP",
                ExecutionEventType.SEQUENCE_GAP_DETECTED, eventPayload);
    }
}
