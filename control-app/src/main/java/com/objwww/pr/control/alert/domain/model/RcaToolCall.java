package com.objwww.pr.control.alert.domain.model;

import com.objwww.pr.shared.Digest;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Holmes 响应 tool_calls 落表条目（V9 rca_tool_call；AM3 §6.2 + v1.1 栅栏直挂列）。
 *
 * <p>只存 params/result 的 SHA-256 摘要（脱敏原文走 CAS，M3-27）——Thought 一律不保存
 * （FUT-09）。run_id/observed_generation/schema_version/payload_digest 由收尾事务按
 * result 同值冗余直挂，禁止多层 JOIN 推导（FUT-50）。
 */
public record RcaToolCall(
        UUID investigationResultId,
        String toolCallId,
        int sequenceNo,
        String toolName,
        ToolCallStatus status,
        Digest paramsDigest,
        Digest resultDigest,
        Instant startedAt,
        Instant finishedAt,
        UUID runId,
        int observedGeneration,
        int schemaVersion,
        Digest payloadDigest) {

    public RcaToolCall {
        Objects.requireNonNull(investigationResultId, "investigationResultId");
        Objects.requireNonNull(toolCallId, "toolCallId");
        if (toolCallId.isBlank()) {
            throw new IllegalArgumentException("toolCallId 不得为 blank");
        }
        if (sequenceNo < 1) {
            throw new IllegalArgumentException("sequenceNo 从 1 起");
        }
        Objects.requireNonNull(toolName, "toolName");
        if (observedGeneration < 0) {
            throw new IllegalArgumentException("observedGeneration 不得为负");
        }
        if (schemaVersion < 1) {
            throw new IllegalArgumentException("schemaVersion 从 1 起");
        }
    }
}
