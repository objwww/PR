package com.objwww.pr.arena.application.chaos;

import java.util.Optional;

/**
 * 故障注入判定端口（INV-AM2-2：注入 = DB 已提交且未过期的 ACTIVE 场景存在，fail-closed——
 * 查询异常 = 无故障）。M2-16 提供数据库实现；此前链路以 NoFaultGate 装配（恒否）。
 * correlation 前缀限定：只对 chaos- 流量生效由实现/调用方共同保证（INV-AM2-1）。
 */
public interface FaultGate {

    /**
     * @param sessionIdOrEmpty 命中时携带会话标识（审计面）
     */
    Optional<ActiveFault> probe(FaultType type, String correlationId);

    default boolean active(FaultType type, String correlationId) {
        return probe(type, correlationId).isPresent();
    }

    /** 命中的故障会话只读视图 */
    record ActiveFault(FaultType type, String scenarioId, String target, long generation) {
    }
}
