package com.objwww.pr.control.domain.sandbox;

import com.objwww.pr.shared.Digest;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * 工具调用账本实体（M4 §4.1 tool_call 表映射）。
 *
 * <p>状态：RUNNING → {SUCCEEDED | FAILED | REJECTED}
 * 观测链：run → attempt → step → tool_call（attempt_id 完整血缘，G1 甲 P0-7）。
 */
public class ToolCall {

    private final UUID id;
    private final UUID reviewRunId;
    private final UUID runStepId;
    private final UUID attemptId;
    private final int callSeq;              // attempt 内单调序号
    private final String toolName;
    private final String toolArgsJson;      // 工具入参 JSON
    private final long leaseEpoch;          // 绑定租约世代
    private final Instant startedAt;

    private ToolCallState state;
    private Integer exitCode;
    private Digest observationDigest;       // 工具返回的观测（→artifact TOOL_OBSERVATION）
    private String observationSummary;      // 前 200 字简化
    private Long observationBytes;
    private boolean truncated;
    private Instant finishedAt;

    /** 工具调用状态枚举（与 V6 DDL CHECK 约束对应） */
    public enum ToolCallState {
        RUNNING,
        SUCCEEDED,
        FAILED,
        REJECTED;       // 策略拒绝（输入物料不合规、输出违规等）

        public boolean isTerminal() {
            return this == SUCCEEDED || this == FAILED || this == REJECTED;
        }
    }

    // 构造函数（仓储重建用）
    public ToolCall(UUID id, UUID reviewRunId, UUID runStepId, UUID attemptId, int callSeq,
                    String toolName, String toolArgsJson, long leaseEpoch, Instant startedAt,
                    ToolCallState state, Integer exitCode, Digest observationDigest,
                    String observationSummary, Long observationBytes, boolean truncated,
                    Instant finishedAt) {
        this.id = Objects.requireNonNull(id, "id");
        this.reviewRunId = Objects.requireNonNull(reviewRunId, "reviewRunId");
        this.runStepId = Objects.requireNonNull(runStepId, "runStepId");
        this.attemptId = Objects.requireNonNull(attemptId, "attemptId");
        if (callSeq < 1) {
            throw new IllegalArgumentException("callSeq must be >= 1");
        }
        this.callSeq = callSeq;
        this.toolName = Objects.requireNonNull(toolName, "toolName");
        this.toolArgsJson = Objects.requireNonNull(toolArgsJson, "toolArgsJson");
        this.leaseEpoch = leaseEpoch;
        this.startedAt = Objects.requireNonNull(startedAt, "startedAt");
        this.state = Objects.requireNonNull(state, "state");
        this.exitCode = exitCode;
        this.observationDigest = observationDigest;
        this.observationSummary = observationSummary;
        this.observationBytes = observationBytes;
        this.truncated = truncated;
        this.finishedAt = finishedAt;
    }

    // 工厂方法：创建新 RUNNING 工具调用
    public static ToolCall createRunning(UUID id, UUID reviewRunId, UUID runStepId,
                                         UUID attemptId, int callSeq, String toolName,
                                         String toolArgsJson, long leaseEpoch) {
        return new ToolCall(
            id, reviewRunId, runStepId, attemptId, callSeq, toolName, toolArgsJson,
            leaseEpoch, Instant.now(), ToolCallState.RUNNING, null, null, null, null,
            false, null
        );
    }

    // 状态转换方法

    /** 工具调用成功完成：RUNNING → SUCCEEDED */
    public void complete(int exitCode, Digest observationDigest, String observationSummary,
                         long observationBytes, boolean truncated) {
        if (state != ToolCallState.RUNNING) {
            throw new IllegalStateException("Cannot complete tool call in state " + state);
        }
        if (exitCode != 0) {
            throw new IllegalArgumentException("exitCode must be 0 for SUCCEEDED");
        }
        this.state = ToolCallState.SUCCEEDED;
        this.exitCode = exitCode;
        this.observationDigest = observationDigest;
        this.observationSummary = observationSummary;
        this.observationBytes = observationBytes;
        this.truncated = truncated;
        this.finishedAt = Instant.now();
    }

    /** 工具调用失败：RUNNING → FAILED */
    public void fail(int exitCode, Digest observationDigest, String observationSummary,
                     long observationBytes, boolean truncated) {
        if (state != ToolCallState.RUNNING) {
            throw new IllegalStateException("Cannot fail tool call in state " + state);
        }
        if (exitCode == 0) {
            throw new IllegalArgumentException("exitCode must be non-zero for FAILED");
        }
        this.state = ToolCallState.FAILED;
        this.exitCode = exitCode;
        this.observationDigest = observationDigest;
        this.observationSummary = observationSummary;
        this.observationBytes = observationBytes;
        this.truncated = truncated;
        this.finishedAt = Instant.now();
    }

    /** 工具调用策略拒绝：RUNNING → REJECTED */
    public void reject(String reason) {
        if (state != ToolCallState.RUNNING) {
            throw new IllegalStateException("Cannot reject tool call in state " + state);
        }
        this.state = ToolCallState.REJECTED;
        this.observationSummary = "REJECTED: " + reason;
        this.finishedAt = Instant.now();
    }

    // Getters

    public UUID id() { return id; }
    public UUID reviewRunId() { return reviewRunId; }
    public UUID runStepId() { return runStepId; }
    public UUID attemptId() { return attemptId; }
    public int callSeq() { return callSeq; }
    public String toolName() { return toolName; }
    public String toolArgsJson() { return toolArgsJson; }
    public long leaseEpoch() { return leaseEpoch; }
    public Instant startedAt() { return startedAt; }
    public ToolCallState state() { return state; }
    public Integer exitCode() { return exitCode; }
    public Digest observationDigest() { return observationDigest; }
    public String observationSummary() { return observationSummary; }
    public Long observationBytes() { return observationBytes; }
    public boolean truncated() { return truncated; }
    public Instant finishedAt() { return finishedAt; }
}
