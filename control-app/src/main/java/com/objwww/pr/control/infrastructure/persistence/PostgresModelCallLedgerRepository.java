package com.objwww.pr.control.infrastructure.persistence;

import com.objwww.pr.control.domain.ai.ModelCallLedgerEntry;
import com.objwww.pr.control.domain.ai.ModelCallLedgerRepository;
import com.objwww.pr.control.domain.ai.TokenUsage;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * PostgreSQL 账本实现（§4.1/§4.6）。
 * 不带 @Repository 注解，由 PersistenceConfig 手工装配（仅 docker profile）。
 */
public class PostgresModelCallLedgerRepository implements ModelCallLedgerRepository {

    private final JdbcTemplate jdbc;

    public PostgresModelCallLedgerRepository(JdbcTemplate jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc);
    }

    @Override
    public void insertStarted(ModelCallLedgerEntry entry) {
        Objects.requireNonNull(entry, "entry");

        String sql = """
            INSERT INTO model_call_ledger (
                id, invocation_id, call_seq, review_run_id, run_step_id, attempt_id,
                lease_epoch, route_id, route_role, fallback_from,
                endpoint_scope, quota_scope, requested_model,
                state, started_at
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'STARTED', now())
            """;

        jdbc.update(sql,
                entry.id(),
                entry.invocationId(),
                entry.callSeq(),
                entry.reviewRunId(),
                entry.runStepId(),
                entry.attemptId(),
                entry.leaseEpoch(),
                entry.routeId(),
                entry.routeRole(),
                entry.fallbackFrom(),
                entry.endpointScope(),
                entry.quotaScope(),
                entry.requestedModel()
        );
    }

    @Override
    public boolean completeTerminalSuccess(
            UUID id,
            TokenUsage usage,
            boolean usageMissing,
            String reportedModel,
            String providerRequestId,
            Duration latency,
            Long costMicros,
            String pricingVersion,
            String currency,
            Long inputPriceMicrosPerK,
            Long outputPriceMicrosPerK
    ) {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(usage, "usage");

        String sql = """
            UPDATE model_call_ledger
            SET state = 'SUCCEEDED',
                outcome = 'OK',
                prompt_tokens = ?,
                completion_tokens = ?,
                total_tokens = ?,
                usage_missing = ?,
                reported_model = ?,
                provider_request_id = ?,
                latency_ms = ?,
                cost_micros = ?,
                pricing_version = ?,
                currency = ?,
                input_price_micros_per_1k = ?,
                output_price_micros_per_1k = ?,
                finished_at = now()
            WHERE id = ? AND state = 'STARTED'
            """;

        int rows = jdbc.update(sql,
                usage.promptTokens(),
                usage.completionTokens(),
                usage.totalTokens(),
                usageMissing,
                reportedModel,
                providerRequestId,
                latency != null ? latency.toMillis() : null,
                costMicros,
                pricingVersion,
                currency,
                inputPriceMicrosPerK,
                outputPriceMicrosPerK,
                id
        );

        return rows > 0;
    }

    @Override
    public boolean completeTerminalFailure(
            UUID id,
            String outcome,
            Integer httpStatus,
            Duration retryAfter,
            Duration latency,
            String errorCode,
            String errorFingerprint,
            String sanitizedMessage
    ) {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(outcome, "outcome");

        String sql = """
            UPDATE model_call_ledger
            SET state = 'FAILED',
                outcome = ?,
                http_status = ?,
                retry_after_ms = ?,
                latency_ms = ?,
                error_code = ?,
                error_fingerprint = ?,
                sanitized_message = ?,
                finished_at = now()
            WHERE id = ? AND state = 'STARTED'
            """;

        int rows = jdbc.update(sql,
                outcome,
                httpStatus,
                retryAfter != null ? retryAfter.toMillis() : null,
                latency != null ? latency.toMillis() : null,
                errorCode,
                errorFingerprint,
                sanitizedMessage,
                id
        );

        return rows > 0;
    }

    @Override
    public int markUnknownOlderThan(Instant threshold) {
        Objects.requireNonNull(threshold, "threshold");

        String sql = """
            UPDATE model_call_ledger
            SET state = 'UNKNOWN',
                finished_at = now()
            WHERE state = 'STARTED'
              AND started_at < ?
            """;

        // pgjdbc 不支持直接绑定 Instant（07006），项目惯例统一 Timestamp.from（INC-60）
        return jdbc.update(sql, Timestamp.from(threshold));
    }
}
