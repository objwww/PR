package com.objwww.pr.control.alert.domain.repository;

import com.objwww.pr.control.alert.domain.model.Incident;

import java.util.Optional;
import java.util.UUID;

/**
 * incident 端口。
 *
 * <p>SQL 契约：findByKeyForUpdate = {@code SELECT ... FROM incident WHERE incident_key=:key
 * FOR UPDATE}——§6.7 投影/收尾算法的 "lock incident" 步；同 key 并发 upsert 串行化于此。
 */
public interface IncidentRepository {

    /** 按聚合身份行锁（投影入口） */
    Optional<Incident> findByKeyForUpdate(String incidentKey);

    Optional<Incident> findByIdForUpdate(UUID id);

    Optional<Incident> findById(UUID id);

    /**
     * 新铸 incident（incident_key 唯一）。EX-A4b（F19）：返回 true=本事务全新插入，
     * false=同 key 已存在（PG 面为 INSERT ON CONFLICT DO NOTHING——唯一冲突不再是
     * 异常，同事务后续语句不再被中断）；调用方按 false 走"重读既有行合并"。
     */
    boolean insert(Incident incident);

    /**
     * EX-A4b（F24）：等待重驱的 incident（status=FIRING 且 waiting_reason 非空）。
     * 是否真有活跃 run 由调用方经 findActiveByIncidentId 复核（避免跨表 SQL 面）。
     */
    java.util.List<Incident> findWaitingForRedrive();

    /** 全列覆盖更新（调用方已持行锁；updated_at 由调用方以 DB now() 语义赋值） */
    boolean update(Incident incident);

    /** 活跃 incident 数（DeferredPolicy backlog 输入；状态 FIRING） */
    int countActive();
}
