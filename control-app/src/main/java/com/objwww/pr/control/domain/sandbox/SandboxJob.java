package com.objwww.pr.control.domain.sandbox;

import com.objwww.pr.shared.Digest;
import com.objwww.pr.shared.sandbox.FailureClass;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * 沙箱作业实体（M4 §4.1 sandbox_job 表映射，§4.2 状态机）。
 *
 * <p>生命周期：PENDING → LEASED → {SUCCEEDED | FAILED | TIMED_OUT | CANCELLED}
 * 全局并发闸：同时 LEASED ≤ 1（uq_sandbox_job_inflight 部分唯一索引）。
 */
public class SandboxJob {

    private final UUID id;
    private final UUID toolCallId;          // 单向 FK → tool_call.id
    private final UUID reviewRunId;
    private final UUID runStepId;
    private final UUID attemptId;
    private final String jobSpecImmutable;  // JobSpec JSON（不可变，触发器双保险）
    private final Instant createdAt;

    private String workerId;
    private JobState state;
    private String leaseOwner;
    private Instant leaseUntil;
    private long leaseEpoch;
    private Instant heartbeatAt;
    private int attemptCount;
    private final int maxAttempts;

    private String containerId;
    private Integer exitCode;
    private Digest resultDigest;
    private Digest logDigest;
    private String errorCode;
    private String sanitizedMessage;
    private FailureClass failureClass;
    private Boolean retryable;

    private Instant startedAt;
    private Instant finishedAt;

    /** 作业状态枚举（与 V6 DDL CHECK 约束对应） */
    public enum JobState {
        PENDING,
        LEASED,
        SUCCEEDED,
        FAILED,
        TIMED_OUT,
        CANCELLED;

        public boolean isTerminal() {
            return this == SUCCEEDED || this == FAILED || this == TIMED_OUT || this == CANCELLED;
        }
    }

    // 构造函数（仓储重建用）
    public SandboxJob(UUID id, UUID toolCallId, UUID reviewRunId, UUID runStepId, UUID attemptId,
                      String jobSpecImmutable, Instant createdAt, String workerId, JobState state,
                      String leaseOwner, Instant leaseUntil, long leaseEpoch, Instant heartbeatAt,
                      int attemptCount, int maxAttempts, String containerId, Integer exitCode,
                      Digest resultDigest, Digest logDigest, String errorCode, String sanitizedMessage,
                      FailureClass failureClass, Boolean retryable, Instant startedAt, Instant finishedAt) {
        this.id = Objects.requireNonNull(id, "id");
        this.toolCallId = Objects.requireNonNull(toolCallId, "toolCallId");
        this.reviewRunId = Objects.requireNonNull(reviewRunId, "reviewRunId");
        this.runStepId = Objects.requireNonNull(runStepId, "runStepId");
        this.attemptId = Objects.requireNonNull(attemptId, "attemptId");
        this.jobSpecImmutable = Objects.requireNonNull(jobSpecImmutable, "jobSpecImmutable");
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt");
        this.workerId = workerId;
        this.state = Objects.requireNonNull(state, "state");
        this.leaseOwner = leaseOwner;
        this.leaseUntil = leaseUntil;
        this.leaseEpoch = leaseEpoch;
        this.heartbeatAt = heartbeatAt;
        this.attemptCount = attemptCount;
        this.maxAttempts = maxAttempts;
        this.containerId = containerId;
        this.exitCode = exitCode;
        this.resultDigest = resultDigest;
        this.logDigest = logDigest;
        this.errorCode = errorCode;
        this.sanitizedMessage = sanitizedMessage;
        this.failureClass = failureClass;
        this.retryable = retryable;
        this.startedAt = startedAt;
        this.finishedAt = finishedAt;
    }

    // 工厂方法：创建新 PENDING 作业
    public static SandboxJob createPending(UUID id, UUID toolCallId, UUID reviewRunId,
                                           UUID runStepId, UUID attemptId, String jobSpecJson) {
        return new SandboxJob(
            id, toolCallId, reviewRunId, runStepId, attemptId, jobSpecJson,
            Instant.now(), null, JobState.PENDING, null, null, 0L, null,
            0, 3, null, null, null, null, null, null, null, null, null, null
        );
    }

    // 状态转换方法

    /** Broker claim：PENDING → LEASED */
    public void claim(String leaseOwner, Instant leaseUntil, String workerId) {
        if (state != JobState.PENDING) {
            throw new IllegalStateException("Cannot claim job in state " + state);
        }
        this.state = JobState.LEASED;
        this.leaseOwner = Objects.requireNonNull(leaseOwner, "leaseOwner");
        this.leaseUntil = Objects.requireNonNull(leaseUntil, "leaseUntil");
        this.leaseEpoch++;
        this.workerId = workerId;
        this.attemptCount++;
        this.startedAt = Instant.now();
    }

    /** Broker 续租：租约延长 + 心跳更新 */
    public void renewLease(Instant newLeaseUntil) {
        if (state != JobState.LEASED) {
            throw new IllegalStateException("Cannot renew lease in state " + state);
        }
        this.leaseUntil = Objects.requireNonNull(newLeaseUntil, "newLeaseUntil");
        this.heartbeatAt = Instant.now();
    }

    /** 作业成功完成：LEASED → SUCCEEDED */
    public void complete(String containerId, int exitCode, Digest resultDigest, Digest logDigest) {
        if (state != JobState.LEASED) {
            throw new IllegalStateException("Cannot complete job in state " + state);
        }
        this.state = JobState.SUCCEEDED;
        this.containerId = containerId;
        this.exitCode = exitCode;
        this.resultDigest = resultDigest;
        this.logDigest = logDigest;
        this.finishedAt = Instant.now();
    }

    /** 作业失败：LEASED → FAILED */
    public void fail(String containerId, Integer exitCode, Digest logDigest, String errorCode,
                     String sanitizedMessage, FailureClass failureClass) {
        if (state != JobState.LEASED) {
            throw new IllegalStateException("Cannot fail job in state " + state);
        }
        this.state = JobState.FAILED;
        this.containerId = containerId;
        this.exitCode = exitCode;
        this.logDigest = logDigest;
        this.errorCode = errorCode;
        this.sanitizedMessage = sanitizedMessage;
        this.failureClass = Objects.requireNonNull(failureClass, "failureClass");
        this.retryable = failureClass.isRetryable() && attemptCount < maxAttempts;
        this.finishedAt = Instant.now();
    }

    /** 作业超时：LEASED → TIMED_OUT */
    public void timeout(String containerId, Digest logDigest) {
        if (state != JobState.LEASED) {
            throw new IllegalStateException("Cannot timeout job in state " + state);
        }
        this.state = JobState.TIMED_OUT;
        this.containerId = containerId;
        this.logDigest = logDigest;
        this.errorCode = "TIMEOUT";
        this.finishedAt = Instant.now();
    }

    /** 作业取消：LEASED → CANCELLED */
    public void cancel() {
        if (state != JobState.LEASED) {
            throw new IllegalStateException("Cannot cancel job in state " + state);
        }
        this.state = JobState.CANCELLED;
        this.finishedAt = Instant.now();
    }

    // Getters

    public UUID id() { return id; }
    public UUID toolCallId() { return toolCallId; }
    public UUID reviewRunId() { return reviewRunId; }
    public UUID runStepId() { return runStepId; }
    public UUID attemptId() { return attemptId; }
    public String jobSpecImmutable() { return jobSpecImmutable; }
    public Instant createdAt() { return createdAt; }
    public String workerId() { return workerId; }
    public JobState state() { return state; }
    public String leaseOwner() { return leaseOwner; }
    public Instant leaseUntil() { return leaseUntil; }
    public long leaseEpoch() { return leaseEpoch; }
    public Instant heartbeatAt() { return heartbeatAt; }
    public int attemptCount() { return attemptCount; }
    public int maxAttempts() { return maxAttempts; }
    public String containerId() { return containerId; }
    public Integer exitCode() { return exitCode; }
    public Digest resultDigest() { return resultDigest; }
    public Digest logDigest() { return logDigest; }
    public String errorCode() { return errorCode; }
    public String sanitizedMessage() { return sanitizedMessage; }
    public FailureClass failureClass() { return failureClass; }
    public Boolean retryable() { return retryable; }
    public Instant startedAt() { return startedAt; }
    public Instant finishedAt() { return finishedAt; }
}
