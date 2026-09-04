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

    /** 新铸 incident（incident_key 唯一；并发双铸撞唯一约束抛 DuplicateKeyException） */
    void insert(Incident incident);

    /** 全列覆盖更新（调用方已持行锁；updated_at 由调用方以 DB now() 语义赋值） */
    boolean update(Incident incident);

    /** 活跃 incident 数（DeferredPolicy backlog 输入；状态 FIRING） */
    int countActive();
}
