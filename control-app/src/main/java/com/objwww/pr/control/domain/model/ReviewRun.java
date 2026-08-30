package com.objwww.pr.control.domain.model;

import com.objwww.pr.control.domain.statemachine.RunStateMachine;
import com.objwww.pr.shared.Digest;
import com.objwww.pr.shared.RunState;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * 一次逻辑评审长事务（与 V1 review_run 对齐）。
 * run_key = hash(revision + policy + prompt + toolset + trigger)，webhook 重投幂等兜底（B-3）；
 * root_run_id/parent_run_id 双字段 lineage（v2.2 E9）。物理重试归 StepAttempt，不在本实体。
 */
public class ReviewRun {

    private final UUID id;
    private final UUID prRevisionId;
    private final UUID parentRunId;
    private final UUID rootRunId;

    private final Digest runKey;
    private final String triggerKey;
    private final RunMode runMode;

    private final String policyVersion;
    private final String promptVersion;
    private final String toolsetVersion;
    private final String initialModelRoute;

    private RunState state;
    private final boolean publisherDisabled;

    private final Long tokenBudget;
    private final Long costBudgetMicros;
    private final Instant deadlineAt;

    private long version;
    private final Instant createdAt;
    private Instant updatedAt;
    private Instant completedAt;

    public ReviewRun(UUID id, UUID prRevisionId, UUID parentRunId, UUID rootRunId,
                     Digest runKey, String triggerKey, RunMode runMode,
                     String policyVersion, String promptVersion, String toolsetVersion,
                     String initialModelRoute, RunState state, boolean publisherDisabled,
                     Long tokenBudget, Long costBudgetMicros, Instant deadlineAt,
                     long version, Instant createdAt, Instant updatedAt, Instant completedAt) {
        this.id = Objects.requireNonNull(id);
        this.prRevisionId = Objects.requireNonNull(prRevisionId);
        this.parentRunId = parentRunId;
        this.rootRunId = rootRunId;
        this.runKey = Objects.requireNonNull(runKey);
        this.triggerKey = Objects.requireNonNull(triggerKey);
        this.runMode = Objects.requireNonNull(runMode);
        this.policyVersion = Objects.requireNonNull(policyVersion);
        this.promptVersion = Objects.requireNonNull(promptVersion);
        this.toolsetVersion = Objects.requireNonNull(toolsetVersion);
        this.initialModelRoute = initialModelRoute;
        this.state = Objects.requireNonNull(state);
        this.publisherDisabled = publisherDisabled;
        if (runMode != RunMode.NORMAL && !publisherDisabled) {
            // 对齐 V1 ck_replay_publisher_disabled：回放/重建类 Run 禁止发布
            throw new IllegalArgumentException("非 NORMAL 模式必须 publisher_disabled=true");
        }
        this.tokenBudget = tokenBudget;
        this.costBudgetMicros = costBudgetMicros;
        this.deadlineAt = deadlineAt;
        this.version = version;
        this.createdAt = Objects.requireNonNull(createdAt);
        this.updatedAt = Objects.requireNonNull(updatedAt);
        this.completedAt = completedAt;
    }

    /** 状态推进统一过状态机；非法迁移抛 IllegalTransitionException */
    public void transitionTo(RunState to, Instant now) {
        this.state = RunStateMachine.transition(this.state, to);
        this.updatedAt = Objects.requireNonNull(now);
        if (RunStateMachine.isTerminal(to)) {
            this.completedAt = now;
        }
    }

    public UUID getId() { return id; }
    public UUID getPrRevisionId() { return prRevisionId; }
    public UUID getParentRunId() { return parentRunId; }
    public UUID getRootRunId() { return rootRunId; }
    public Digest getRunKey() { return runKey; }
    public String getTriggerKey() { return triggerKey; }
    public RunMode getRunMode() { return runMode; }
    public String getPolicyVersion() { return policyVersion; }
    public String getPromptVersion() { return promptVersion; }
    public String getToolsetVersion() { return toolsetVersion; }
    public String getInitialModelRoute() { return initialModelRoute; }
    public RunState getState() { return state; }
    public boolean isPublisherDisabled() { return publisherDisabled; }
    public Long getTokenBudget() { return tokenBudget; }
    public Long getCostBudgetMicros() { return costBudgetMicros; }
    public Instant getDeadlineAt() { return deadlineAt; }
    public long getVersion() { return version; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public Instant getCompletedAt() { return completedAt; }
}
