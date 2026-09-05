package com.objwww.pr.control.alert.domain.model;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import com.objwww.pr.shared.Digest;

/**
 * 调查记录（V9 rca_investigation_result；AM3 §6.2 冻结 DDL 语义 + v1.1 栅栏列）。
 *
 * <p>每次 Holmes attempt 全程落档（INV-AM3-7）：attempt 铸造同事事务落 STARTED 行，
 * 收尾事务终态 CAS（SUCCEEDED/FAILED/TIMEOUT/UNKNOWN 与 REJECTED_* 同权落档）——
 * 验证失败不再是"零落档误判超时"。observed_generation 直挂（FUT-50），
 * 仓储层以"与 rca_run.generation 一致"为写入栅栏（晚到旧代写入被拒）。
 */
public record InvestigationResult(
        UUID id,
        UUID attemptId,
        UUID runId,
        int observedGeneration,
        int schemaVersion,
        ExecutionStatus executionStatus,
        ValidationStatus validationStatus,
        List<String> validationErrors,
        String packageJson,
        String rawArtifactRef,
        Digest rawDigest,
        Digest payloadDigest,
        String model,
        String usageJson,
        Instant createdAt,
        Instant finishedAt) {

    public InvestigationResult {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(attemptId, "attemptId");
        Objects.requireNonNull(runId, "runId");
        Objects.requireNonNull(executionStatus, "executionStatus");
        Objects.requireNonNull(validationStatus, "validationStatus");
        validationErrors = validationErrors == null ? List.of() : List.copyOf(validationErrors);
        if (observedGeneration < 0) {
            throw new IllegalArgumentException("observedGeneration 不得为负");
        }
        if (schemaVersion < 1) {
            throw new IllegalArgumentException("schemaVersion 从 1 起");
        }
    }

    /** STARTED 先行行（attempt 铸造同事事务；终态列全空） */
    public static InvestigationResult started(UUID id, UUID attemptId, UUID runId,
                                              int observedGeneration, int schemaVersion,
                                              String model, Instant now) {
        return new InvestigationResult(id, attemptId, runId, observedGeneration, schemaVersion,
                ExecutionStatus.STARTED, ValidationStatus.NOT_VALIDATED, List.of(),
                null, null, null, null, model, null, now, null);
    }

    /** 终态行（CAS 回写载体；保留 STARTED 时的 id/attempt/run/generation 不变） */
    public InvestigationResult withTerminal(ExecutionStatus execution,
                                            ValidationStatus validation,
                                            List<String> errors,
                                            String packageJson,
                                            String rawArtifactRef,
                                            Digest rawDigest,
                                            Digest payloadDigest,
                                            String usageJson,
                                            Instant finishedAt) {
        return new InvestigationResult(id, attemptId, runId, observedGeneration, schemaVersion,
                execution, validation, errors, packageJson, rawArtifactRef, rawDigest,
                payloadDigest, model, usageJson, createdAt, finishedAt);
    }
}
