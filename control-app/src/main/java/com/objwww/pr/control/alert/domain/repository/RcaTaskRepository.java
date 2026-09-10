package com.objwww.pr.control.alert.domain.repository;

import com.objwww.pr.control.alert.domain.model.RcaTask;
import com.objwww.pr.control.alert.domain.model.RcaTaskState;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * rca_task 端口（调度列齐全；SLA 晋升排序 §6.2）。
 *
 * <p>SQL 契约：claimNext =
 * {@code UPDATE ... SET state='LEASED', ... WHERE id = (SELECT id FROM rca_task t
 * WHERE state IN ('READY','RETRY_WAIT') AND available_at <= :now
 * AND EXISTS (SELECT 1 FROM rca_run r WHERE r.id = t.run_id
 *             AND r.state IN ('QUEUED','RUNNING','REPORTING'))   -- M4-07 generation fence
 * ORDER BY (now() >= deadline_at) DESC, priority DESC,
 * deadline_at, created_at, id LIMIT 1 FOR UPDATE SKIP LOCKED) RETURNING *}
 *
 * <p>requireCurrentLease = epoch 栅栏 UPDATE（行数 0 = 旧 worker/已回收）；
 * slot 领取与本 claim 同一短事务（INV-AM1-7，CT-A04）。
 */
public interface RcaTaskRepository {

    void insert(RcaTask task);

    /** SLA 排序领取（§6.2 ORDER BY；SKIP LOCKED 并发互斥 CT-A02） */
    Optional<RcaTask> claimNext(String owner, Instant now, Duration lease);

    /** epoch 栅栏：当前租约校验（1=仍持有；0=过期/易主） */
    boolean requireCurrentLease(UUID id, String owner, long leaseEpoch);

    /** 状态/调度列更新（调用方已过栅栏） */
    boolean update(RcaTask task);

    /** 心跳续租：lease_until = now + extend（仅当前租约） */
    void heartbeat(UUID id, String owner, long leaseEpoch, Instant now, Duration extend);

    /** 崩溃回收扫描：LEASED 且 lease_until < now */
    List<RcaTask> findExpiredLeased(Instant now);

    /**
     * EX-A2（F10）：过期租约原子回收——四条件同语句（id + state='LEASED' +
     * lease_until < :now + lease_epoch = :expectedEpoch）：读快照后原 worker 心跳已续
     * （lease_until 前移）/已他人重领（epoch+1）/他回收者已收敛（state≠LEASED）任一发生
     * 即 0 行 = 竞态失败，调用方零补救。target=RETRY_WAIT（活跃 run 重排）或
     * STALE（死 run 不复活，M4-07）；readyAt 为退避后的 available_at/ready_since，
     * 租约列清空，epoch/attempt_count 不动。默认实现不可用（条件写必须真实现）。
     */
    default boolean reclaimExpired(UUID id, long expectedEpoch, Instant now,
                                   RcaTaskState target, Instant readyAt) {
        throw new UnsupportedOperationException(
                "reclaimExpired 需原子条件写实现: " + getClass().getName());
    }

    Optional<RcaTask> findById(UUID id);

    /** run 全量任务（推进器输入；返回序 = id 升序，稳定可复现） */
    List<RcaTask> findByRunId(UUID runId);

    /**
     * 状态 CAS 迁移（M4-06 推进器并发栅栏）：{@code UPDATE ... SET state=:to
     * WHERE id=:id AND state=:from}——1=本次迁移生效；0=并发已收敛/状态已漂移。
     * 状态机合法性由调用方保证（本端口只做行级竞态裁决）。
     */
    boolean transitionState(UUID id, RcaTaskState from, RcaTaskState to);

    /** 排队 task 数（DeferredPolicy backlog 输入） */
    int countQueued();
}
