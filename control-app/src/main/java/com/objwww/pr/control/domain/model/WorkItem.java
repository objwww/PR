package com.objwww.pr.control.domain.model;

import com.objwww.pr.shared.WorkItemState;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * 可领取工作单元（与 V1 work_item 对齐），WorkItem Worker 经 SKIP LOCKED + 租约消费。
 * 租约语义：lease_owner + lease_until + lease_epoch；晚到结果按 lease_epoch 比对记 STALE（I11）。
 */
public class WorkItem {

    private final UUID id;
    private final UUID reviewRunId;
    private final UUID stepId;

    private final String workType;
    private WorkItemState state;
    private final int priority;
    private Instant availableAt;

    private String leaseOwner;
    private Instant leaseUntil;
    private long leaseEpoch;

    private int attemptCount;
    private final int maxAttempts;

    private final Instant createdAt;
    private Instant updatedAt;

    public WorkItem(UUID id, UUID reviewRunId, UUID stepId,
                    String workType, WorkItemState state, int priority, Instant availableAt,
                    String leaseOwner, Instant leaseUntil, long leaseEpoch,
                    int attemptCount, int maxAttempts,
                    Instant createdAt, Instant updatedAt) {
        this.id = Objects.requireNonNull(id);
        this.reviewRunId = Objects.requireNonNull(reviewRunId);
        this.stepId = Objects.requireNonNull(stepId);
        this.workType = Objects.requireNonNull(workType);
        this.state = Objects.requireNonNull(state);
        this.priority = priority;
        this.availableAt = Objects.requireNonNull(availableAt);
        this.leaseOwner = leaseOwner;
        this.leaseUntil = leaseUntil;
        this.leaseEpoch = leaseEpoch;
        // 对齐 ck_work_attempt_count
        if (maxAttempts <= 0 || attemptCount < 0 || attemptCount > maxAttempts) {
            throw new IllegalArgumentException("attempt 计数越界: " + attemptCount + "/" + maxAttempts);
        }
        this.attemptCount = attemptCount;
        this.maxAttempts = maxAttempts;
        this.createdAt = Objects.requireNonNull(createdAt);
        this.updatedAt = Objects.requireNonNull(updatedAt);
    }

    /** 领取租约：epoch+1（重领时旧 worker 的晚到结果按 epoch 比对记 STALE） */
    public void leaseTo(String owner, Instant until, Instant now) {
        this.leaseOwner = Objects.requireNonNull(owner);
        this.leaseUntil = Objects.requireNonNull(until);
        this.leaseEpoch++;
        this.attemptCount++;
        if (this.attemptCount > this.maxAttempts) {
            throw new IllegalStateException("attempt 预算耗尽: " + maxAttempts);
        }
        this.state = WorkItemState.LEASED;
        this.updatedAt = Objects.requireNonNull(now);
    }

    public void transitionTo(WorkItemState to, Instant now) {
        this.state = Objects.requireNonNull(to);
        this.updatedAt = Objects.requireNonNull(now);
    }

    /** T2 失败未耗尽预算：RETRY_WAIT + 退避重领时间（available_at） */
    public void retryLater(Instant retryAt, Instant now) {
        this.state = WorkItemState.RETRY_WAIT;
        this.availableAt = Objects.requireNonNull(retryAt);
        this.updatedAt = Objects.requireNonNull(now);
    }

    /** 心跳续租：租约归属不变，仅延长 lease_until */
    public void renewLease(Instant newUntil, Instant now) {
        this.leaseUntil = Objects.requireNonNull(newUntil);
        this.updatedAt = Objects.requireNonNull(now);
    }

    /**
     * 回收过期租约（崩溃恢复）：epoch+1 使僵尸 worker 的心跳/晚到结果全部失效（I11），
     * 清空 owner/until，立即回到可领取时间。attempt_count 不动——崩溃那次 attempt 在领取时已计。
     */
    public void reclaim(WorkItemState target, Instant now) {
        if (this.state != WorkItemState.LEASED) {
            throw new IllegalStateException("只有 LEASED 可回收: " + this.state);
        }
        this.leaseEpoch++;
        this.leaseOwner = null;
        this.leaseUntil = null;
        this.availableAt = Objects.requireNonNull(now);
        transitionTo(target, now);
    }

    public UUID getId() { return id; }
    public UUID getReviewRunId() { return reviewRunId; }
    public UUID getStepId() { return stepId; }
    public String getWorkType() { return workType; }
    public WorkItemState getState() { return state; }
    public int getPriority() { return priority; }
    public Instant getAvailableAt() { return availableAt; }
    public String getLeaseOwner() { return leaseOwner; }
    public Instant getLeaseUntil() { return leaseUntil; }
    public long getLeaseEpoch() { return leaseEpoch; }
    public int getAttemptCount() { return attemptCount; }
    public int getMaxAttempts() { return maxAttempts; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
