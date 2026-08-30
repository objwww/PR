package com.objwww.pr.control.domain.repository;

import com.objwww.pr.control.domain.model.WorkItem;
import com.objwww.pr.shared.WorkItemState;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface WorkItemRepository {

    void save(WorkItem workItem);

    Optional<WorkItem> findById(UUID id);

    /** 对齐 uq_work_item_step */
    Optional<WorkItem> findByStepId(UUID stepId);

    /**
     * 领取下一个可执行 WorkItem（READY/RETRY_WAIT 且 available_at &lt;= now，
     * 按 priority DESC, available_at, created_at 排序，SKIP LOCKED）。
     * 原子写租约：lease_owner=owner、lease_until=now+min(step.timeout_seconds, maxLeaseSeconds)、
     * lease_epoch+1、state=LEASED、attempt_count+1。无可领取项返回空。
     */
    Optional<WorkItem> claimNext(String owner, Instant now, int maxLeaseSeconds);

    /**
     * 心跳续租：仅当租约仍属 (leaseOwner, leaseEpoch) 且 state=LEASED 时延长 lease_until。
     * 0 行（返回 false）= 已被判死/重领，持有方必须停止干活。
     */
    boolean heartbeat(UUID id, String leaseOwner, long leaseEpoch, Instant newLeaseUntil, Instant now);

    /** 崩溃恢复扫描（CT-02/ST-08）：过期 LEASED 项（lease_until &lt; now），按 lease_until 升序 */
    List<WorkItem> findExpiredLeases(Instant now, int limit);

    /**
     * 回收过期租约：仅当 id 仍处 LEASED 且 lease_epoch 匹配且 lease_until &lt; now 时
     * 置为 target（READY=预算未尽重领 / DEAD=耗尽），lease_epoch+1、清空 owner/until，
     * available_at=now。0 行（返回 false）= 已被他人处理。
     */
    boolean reclaimExpiredLease(UUID id, long leaseEpoch, Instant now, WorkItemState target);

    /**
     * I11 晚到结果栅栏：仅当 lease_owner 与 lease_epoch 仍匹配时推进状态。
     * 单句条件 UPDATE，0 行 = 租约已易主（晚到结果），返回 false，调用方只记 STALE 不推进。
     * availableAt 仅 RETRY_WAIT（退避重领）时使用，其余状态传 null（保持原值）。
     */
    boolean transitionIfLeaseCurrent(UUID id, String leaseOwner, long leaseEpoch,
                                     WorkItemState to, Instant availableAt, Instant now);

    /** T1 换届：取消该 Run 所有未完成 WorkItem（READY/LEASED/RETRY_WAIT → CANCELLED），返回影响行数 */
    int cancelActiveByRunId(UUID reviewRunId, Instant now);
}
