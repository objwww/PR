package com.objwww.pr.control.alert.domain.model;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * RCA 报告（六段式 package + 结构验证结果；语义验证归 AM4）。
 */
public record RcaReport(
        UUID id,
        UUID runId,
        UUID attemptId,
        int schemaVersion,
        ValidationStatus validationStatus,
        List<String> validationErrors,
        String packageJson,
        String rawText,
        String model,
        Integer promptTokens,
        Integer completionTokens,
        Integer totalTokens,
        boolean usageMissing,
        Instant createdAt
) {
    public RcaReport {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(runId, "runId");
        Objects.requireNonNull(attemptId, "attemptId");
        Objects.requireNonNull(validationStatus, "validationStatus");
        Objects.requireNonNull(packageJson, "packageJson");
        Objects.requireNonNull(rawText, "rawText");
        Objects.requireNonNull(createdAt, "createdAt");
        validationErrors = validationErrors == null ? List.of() : List.copyOf(validationErrors);
        if (schemaVersion < 1) {
            throw new IllegalArgumentException("schemaVersion 从 1 起");
        }
    }
}
