package com.objwww.pr.control.alert.domain.repository;

import com.objwww.pr.control.alert.domain.model.RcaTask;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * rca_task 端口（调度列齐全；SLA 晋升排序 §6.2）。
 *
 * <p>SQL 契约：claimNext =
 * {@code UPDATE ... SET state='LEASED', lease_owner=:owner, lease_until=:now+:lease,
 * lease_epoch=lease_epoch+1, attempt_count=attempt_count+1, updated_at=:now
 * WHERE id = (SELECT id FROM rca_task WHERE state IN ('READY','RETRY_WAIT')
 * AND available_at <= :now ORDER BY (now() >= deadline_at) DESC, priority DESC,
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

    Optional<RcaTask> findById(UUID id);

    /** 排队 task 数（DeferredPolicy backlog 输入） */
    int countQueued();
}
