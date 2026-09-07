package com.objwww.pr.control.alert.domain.repository;

import com.objwww.pr.control.alert.domain.model.RcaRun;
import com.objwww.pr.control.alert.domain.model.RcaRunRouting;

import java.util.Optional;
import java.util.UUID;

/**
 * rca_run 端口。
 *
 * <p>SQL 契约：insert 撞部分唯一索引 uq_rca_run_active_incident（incident_id, engine
 * where state in ('QUEUED','RUNNING')，V25 升 (incident_id, engine) 粒度）抛
 * DuplicateKeyException(23505)——INV-AM1-2 同一 incident 同引擎最多一个活跃 run 的
 * DB 强制（CT-A03 并发双铸实证）。
 */
public interface RcaRunRepository {

    void insert(RcaRun run);

    /**
     * 带路由四列铸造（M5-10；engine/config_digest/stickiness_key/canary_bucket 落行，
     * Run 启动固定不再变）。默认实现 = 无路由语义环境（测试 fake/降级仓储）回退普通
     * insert，列走 DB 默认（HOLMES/null/null/null）——行为与 insert 等价。
     */
    default void insertRouted(RcaRun run, RcaRunRouting routing) {
        insert(run);
    }

    /** 行锁（finishTask 收尾算法 "lock task → lock incident" 链路） */
    Optional<RcaRun> findByIdForUpdate(UUID id);

    /** 无锁读（评测评分轮询面，M3-16；只读身份用） */
    Optional<RcaRun> findById(UUID id);

    boolean update(RcaRun run);

    /** 当前活跃 run（QUEUED/RUNNING）；无则 empty */
    Optional<RcaRun> findActiveByIncidentId(UUID incidentId);
}
