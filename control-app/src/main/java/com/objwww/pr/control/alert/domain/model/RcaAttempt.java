package com.objwww.pr.control.alert.domain.model;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * 物理执行尝试（V1 step_attempt 同构）：epoch 栅栏 + 终态记录。
 *
 * <p>samplingFingerprint（M5-04/V23）：LLM 调用点回写的采样指纹 jsonb 形态
 * （Map 键集 = ck_rca_attempt_fingerprint_keys；门禁语义见 eval 侧
 * SamplingFingerprint.isGateEligible）。STARTED 行为 null，终态随 artifact 落。
 * 以 Map 承载保持告警域不依赖 eval 类型（域零框架不变）。
 */
public record RcaAttempt(
        UUID id,
        UUID taskId,
        int attemptNo,
        long leaseEpoch,
        String workerId,
        RcaAttemptStatus status,
        String errorClass,
        String errorCode,
        String errorDetail,
        Instant startedAt,
        Instant finishedAt,
        Map<String, Object> samplingFingerprint
) {
    public RcaAttempt {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(taskId, "taskId");
        Objects.requireNonNull(workerId, "workerId");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(startedAt, "startedAt");
        if (attemptNo < 1) {
            throw new IllegalArgumentException("attemptNo 从 1 起");
        }
    }
}
