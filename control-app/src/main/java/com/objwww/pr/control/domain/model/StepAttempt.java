package com.objwww.pr.control.domain.model;

import com.objwww.pr.control.domain.statemachine.AttemptStatusMachine;
import com.objwww.pr.shared.AttemptStatus;
import com.objwww.pr.shared.Digest;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * 物理尝试（与 V1 step_attempt 对齐）。(step_id, attempt_no) 唯一。
 * attempt start 不进账本（v2.2 E10），本实体行即计数载体。
 */
public class StepAttempt {

    private final UUID id;
    private final UUID stepId;
    private final UUID workItemId;
    private final int attemptNo;
    private final long leaseEpoch;
    private final String workerId;

    private AttemptStatus status;
    private final String actualModelProvider;
    private final String actualModel;

    private final Digest inputArtifactDigest;
    private Digest outputArtifactDigest;

    private String errorClass;
    private String errorCode;
    private String errorDetail;

    private final Instant startedAt;
    private Instant finishedAt;

    public StepAttempt(UUID id, UUID stepId, UUID workItemId,
                       int attemptNo, long leaseEpoch, String workerId,
                       AttemptStatus status,
                       String actualModelProvider, String actualModel,
                       Digest inputArtifactDigest, Digest outputArtifactDigest,
                       String errorClass, String errorCode, String errorDetail,
                       Instant startedAt, Instant finishedAt) {
        this.id = Objects.requireNonNull(id);
        this.stepId = Objects.requireNonNull(stepId);
        this.workItemId = Objects.requireNonNull(workItemId);
        this.attemptNo = attemptNo;
        this.leaseEpoch = leaseEpoch;
        this.workerId = Objects.requireNonNull(workerId);
        this.status = Objects.requireNonNull(status);
        this.actualModelProvider = actualModelProvider;
        this.actualModel = actualModel;
        this.inputArtifactDigest = inputArtifactDigest;
        this.outputArtifactDigest = outputArtifactDigest;
        this.errorClass = errorClass;
        this.errorCode = errorCode;
        this.errorDetail = errorDetail;
        this.startedAt = Objects.requireNonNull(startedAt);
        this.finishedAt = finishedAt;
    }

    public void transitionTo(AttemptStatus to, Instant now) {
        this.status = AttemptStatusMachine.transition(this.status, to);
        this.finishedAt = Objects.requireNonNull(now);
    }

    public void failWith(AttemptStatus failureStatus, String errorClass, String errorCode,
                         String errorDetail, Instant now) {
        if (failureStatus != AttemptStatus.FAILED_RETRYABLE && failureStatus != AttemptStatus.FAILED_TERMINAL) {
            throw new IllegalArgumentException("失败态只能为 FAILED_RETRYABLE/FAILED_TERMINAL: " + failureStatus);
        }
        this.errorClass = errorClass;
        this.errorCode = errorCode;
        this.errorDetail = errorDetail;
        transitionTo(failureStatus, now);
    }

    public void succeedWith(Digest outputDigest, Instant now) {
        this.outputArtifactDigest = outputDigest;
        transitionTo(AttemptStatus.SUCCEEDED, now);
    }

    public UUID getId() { return id; }
    public UUID getStepId() { return stepId; }
    public UUID getWorkItemId() { return workItemId; }
    public int getAttemptNo() { return attemptNo; }
    public long getLeaseEpoch() { return leaseEpoch; }
    public String getWorkerId() { return workerId; }
    public AttemptStatus getStatus() { return status; }
    public String getActualModelProvider() { return actualModelProvider; }
    public String getActualModel() { return actualModel; }
    public Digest getInputArtifactDigest() { return inputArtifactDigest; }
    public Digest getOutputArtifactDigest() { return outputArtifactDigest; }
    public String getErrorClass() { return errorClass; }
    public String getErrorCode() { return errorCode; }
    public String getErrorDetail() { return errorDetail; }
    public Instant getStartedAt() { return startedAt; }
    public Instant getFinishedAt() { return finishedAt; }
}
