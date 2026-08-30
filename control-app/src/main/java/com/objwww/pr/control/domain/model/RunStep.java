package com.objwww.pr.control.domain.model;

import com.objwww.pr.control.domain.statemachine.StepStateMachine;
import com.objwww.pr.shared.Digest;
import com.objwww.pr.shared.OperationId;
import com.objwww.pr.shared.StepState;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * 逻辑步骤（与 V1 run_step 对齐）。step_key 在 Run 内唯一（逻辑幂等键），
 * operation_id 全局唯一；物理重试走 StepAttempt，不进入逻辑幂等键。
 */
public class RunStep {

    private final UUID id;
    private final UUID reviewRunId;
    private final UUID parentStepId;
    private final String stepKey;
    private final OperationId operationId;
    private final String executionScope;
    private final String stepType;
    private final int ordinal;

    private StepState state;
    private final Digest inputArtifactDigest;
    private Digest outputArtifactDigest;

    private final int maxAttempts;
    private final int timeoutSeconds;
    private long version;

    private final Instant createdAt;
    private Instant updatedAt;
    private Instant completedAt;

    public RunStep(UUID id, UUID reviewRunId, UUID parentStepId,
                   String stepKey, OperationId operationId, String executionScope,
                   String stepType, StepState state, int ordinal,
                   Digest inputArtifactDigest, Digest outputArtifactDigest,
                   int maxAttempts, int timeoutSeconds, long version,
                   Instant createdAt, Instant updatedAt, Instant completedAt) {
        this.id = Objects.requireNonNull(id);
        this.reviewRunId = Objects.requireNonNull(reviewRunId);
        this.parentStepId = parentStepId;
        this.stepKey = Objects.requireNonNull(stepKey);
        this.operationId = Objects.requireNonNull(operationId);
        this.executionScope = Objects.requireNonNull(executionScope);
        this.stepType = Objects.requireNonNull(stepType);
        this.state = Objects.requireNonNull(state);
        this.ordinal = ordinal;
        this.inputArtifactDigest = inputArtifactDigest;
        this.outputArtifactDigest = outputArtifactDigest;
        if (maxAttempts <= 0) {
            throw new IllegalArgumentException("maxAttempts 必须 > 0"); // ck_step_attempts
        }
        if (timeoutSeconds <= 0) {
            throw new IllegalArgumentException("timeoutSeconds 必须 > 0"); // ck_step_timeout
        }
        this.maxAttempts = maxAttempts;
        this.timeoutSeconds = timeoutSeconds;
        this.version = version;
        this.createdAt = Objects.requireNonNull(createdAt);
        this.updatedAt = Objects.requireNonNull(updatedAt);
        this.completedAt = completedAt;
    }

    public void transitionTo(StepState to, Instant now) {
        this.state = StepStateMachine.transition(this.state, to);
        this.updatedAt = Objects.requireNonNull(now);
        if (StepStateMachine.isTerminal(to)) {
            this.completedAt = now;
        }
    }

    public void completeWithOutput(Digest outputDigest, Instant now) {
        this.outputArtifactDigest = outputDigest;
        transitionTo(StepState.SUCCEEDED, now);
    }

    public UUID getId() { return id; }
    public UUID getReviewRunId() { return reviewRunId; }
    public UUID getParentStepId() { return parentStepId; }
    public String getStepKey() { return stepKey; }
    public OperationId getOperationId() { return operationId; }
    public String getExecutionScope() { return executionScope; }
    public String getStepType() { return stepType; }
    public int getOrdinal() { return ordinal; }
    public StepState getState() { return state; }
    public Digest getInputArtifactDigest() { return inputArtifactDigest; }
    public Digest getOutputArtifactDigest() { return outputArtifactDigest; }
    public int getMaxAttempts() { return maxAttempts; }
    public int getTimeoutSeconds() { return timeoutSeconds; }
    public long getVersion() { return version; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public Instant getCompletedAt() { return completedAt; }
}
