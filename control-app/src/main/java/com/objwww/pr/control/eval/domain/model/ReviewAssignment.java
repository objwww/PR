package com.objwww.pr.control.eval.domain.model;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * EV-08 评审任务（review_assignment 行投影；§3.6 任务列表→单例工作区）。
 *
 * <p>状态机：PENDING 待领取 → IN_PROGRESS 进行中 → SUBMITTED 已提交（终态）。
 * 有界租约：IN_PROGRESS 且 leaseExpiresAt 已过 = 惰性回收——{@link #effectiveStatus}
 * 投影回 PENDING，领取 CAS 谓词允许任何人重领（无 worker，EV-08 卡"选简单可靠的"）。
 *
 * <p>revision 是 CAS 锚：领取/提交各 +1；提交携带 expectedRevision，租约被回收
 * 重领后旧持有者提交必撞 revision（EU29 显式冲突，不静默覆盖）。
 */
public record ReviewAssignment(UUID id, UUID runId, UUID caseExecutionId,
                               Status status, String reviewer, int revision,
                               Instant claimedAt, Instant leaseExpiresAt,
                               Instant submittedAt, String createdBy, Instant createdAt) {

    public enum Status {PENDING, IN_PROGRESS, SUBMITTED}

    public ReviewAssignment {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(runId, "runId");
        Objects.requireNonNull(caseExecutionId, "caseExecutionId");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(createdBy, "createdBy");
        Objects.requireNonNull(createdAt, "createdAt");
    }

    /** 新生成的待领取任务（revision 0，零评审面） */
    public static ReviewAssignment pending(UUID id, UUID runId, UUID caseExecutionId,
                                           String createdBy, Instant createdAt) {
        return new ReviewAssignment(id, runId, caseExecutionId, Status.PENDING, null, 0,
                null, null, null, createdBy, createdAt);
    }

    /** 租约是否已超时（仅 IN_PROGRESS 有租约面） */
    public boolean leaseExpired(Instant now) {
        return status == Status.IN_PROGRESS && leaseExpiresAt != null
                && !leaseExpiresAt.isAfter(now);
    }

    /**
     * 有效状态（惰性回收投影）：IN_PROGRESS + 租约超时 → PENDING（可重领）；
     * 其余原样。存储列不变——回收不发行 UPDATE，读面与领取谓词双路判定。
     */
    public Status effectiveStatus(Instant now) {
        return leaseExpired(now) ? Status.PENDING : status;
    }

    /** 是否可被领取（待领取，或租约已超时的进行中 = 回收面） */
    public boolean claimable(Instant now) {
        return status == Status.PENDING || leaseExpired(now);
    }
}
