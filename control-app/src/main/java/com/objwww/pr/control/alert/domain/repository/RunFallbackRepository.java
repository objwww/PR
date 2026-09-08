package com.objwww.pr.control.alert.domain.repository;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * run_fallback 端口（M6-04，V33）。占位栅栏表：uq_rf_source(source_native_run_id)
 * 表级唯一 = fallback 幂等锚（C-68——fallback_of 事件只是审计副本，不承担唯一性）。
 *
 * <p>SQL 契约：{@link #insertOccupancy} = INSERT ... ON CONFLICT ON CONSTRAINT
 * uq_rf_source DO NOTHING，行数=1 即占位成功、行数=0 即败者（同源 run 已铸过
 * fallback；并发原子裁定，不抛 DuplicateKeyException）；insert-only 授权面
 * （V33 revoke update/delete），实现不得提供改/删路径。
 */
public interface RunFallbackRepository {

    /** 占位行（insert-only；depth 恒 1，check (depth &lt;= 1) 兜底） */
    record OccupancyRow(UUID sourceNativeRunId, UUID sourceIncidentId, int generation,
                        UUID fallbackRunId, int depth, String errorClass, Instant createdAt) {
    }

    /**
     * 恰一次占位：成功插入返回 true；并发/重放撞 uq_rf_source 返回 false（非错误，
     * 调用方＝败者语义静默放弃）。同事务先占位后铸 run 的调用序由 FallbackService 保证。
     */
    boolean insertOccupancy(OccupancyRow row);

    /** 独立预算计数面：时间窗内已占位行数（FallbackService 窗口裁定） */
    long countCreatedSince(Instant after);

    /** 观测/测试面：源 run 的 fallback 占位行 */
    Optional<OccupancyRow> findBySourceRunId(UUID sourceNativeRunId);
}
