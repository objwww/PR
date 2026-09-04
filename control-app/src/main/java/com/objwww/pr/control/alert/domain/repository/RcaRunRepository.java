package com.objwww.pr.control.alert.domain.repository;

import com.objwww.pr.control.alert.domain.model.RcaRun;

import java.util.Optional;
import java.util.UUID;

/**
 * rca_run 端口。
 *
 * <p>SQL 契约：insert 撞部分唯一索引 uq_rca_run_active_incident（incident_id where state
 * in ('QUEUED','RUNNING')) 抛 DuplicateKeyException(23505)——INV-AM1-2 同一 incident
 * 最多一个活跃 run 的 DB 强制（CT-A03 并发双铸实证）。
 */
public interface RcaRunRepository {

    void insert(RcaRun run);

    /** 行锁（finishTask 收尾算法 "lock task → lock incident" 链路） */
    Optional<RcaRun> findByIdForUpdate(UUID id);

    boolean update(RcaRun run);

    /** 当前活跃 run（QUEUED/RUNNING）；无则 empty */
    Optional<RcaRun> findActiveByIncidentId(UUID incidentId);
}
