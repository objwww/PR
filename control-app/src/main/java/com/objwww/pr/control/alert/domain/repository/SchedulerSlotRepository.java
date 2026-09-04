package com.objwww.pr.control.alert.domain.repository;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * scheduler_slot 端口（固定槽位租约表，评审 #6——替代 SIGKILL 后永久泄漏的 running 计数器）。
 *
 * <p>SQL 契约：tryAcquire =
 * {@code UPDATE scheduler_slot SET lease_owner=:owner, lease_until=:now+:lease,
 * lease_epoch=lease_epoch+1, task_id=:taskId, updated_at=:now
 * WHERE (scope, slot_no) = (SELECT scope, slot_no FROM scheduler_slot WHERE scope=:scope
 * AND (lease_until IS NULL OR lease_until <= :now) FOR UPDATE SKIP LOCKED LIMIT 1)
 * RETURNING slot_no}
 *
 * <p>task 领取与 slot 占用同一短事务（INV-AM1-7：领取/归还/回收永远同事务或同租约周期）。
 */
public interface SchedulerSlotRepository {

    /** 领取结果（epoch 随槽返回——release/heartbeat 栅栏输入） */
    record AcquiredSlot(int slotNo, long leaseEpoch) {
    }

    /**
     * 原子占一槽（无空槽返回 empty；含租约过期槽的抢占）。
     * SQL 契约：RETURNING slot_no, lease_epoch（翻转后的新 epoch）。
     */
    Optional<AcquiredSlot> tryAcquire(String scope, String owner, UUID taskId, Instant now, Duration lease);

    /** 归还槽（epoch 栅栏；0 行=已易主，仅记日志） */
    boolean release(String scope, int slotNo, String owner, long leaseEpoch);

    /** 心跳续租：lease_until = now + extend（仅当前 owner+epoch 持有者；长调查期间防回收） */
    void heartbeat(String scope, int slotNo, String owner, long leaseEpoch, Instant now, Duration extend);

    /** 崩溃回收：lease_until < now 的占用槽清空（epoch 不动，重占时 +1） */
    long reclaimExpired(Instant now);

    /** 观测/自检：当前占用中的 slot_no 列表 */
    List<Integer> occupiedSlots(String scope);

    /** 固定槽位总数（部署门观测；迁移预置 2） */
    int totalSlots(String scope);
}
