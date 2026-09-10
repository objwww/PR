package com.objwww.pr.control.infrastructure.persistence;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.objwww.pr.control.alert.domain.agent.RcaModelCallLedger;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * rca_model_call 的 Postgres 实现（R7a-1，V48）。PENDING 先行 + 终态 CAS
 * （WHERE state='PENDING' 判 affected）；未结算读 = state IN (PENDING, UNKNOWN)。
 */
public class PostgresRcaModelCallLedger implements RcaModelCallLedger {

    private final JdbcClient jdbc;
    private final ObjectMapper mapper;

    public PostgresRcaModelCallLedger(JdbcClient jdbc, ObjectMapper mapper) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        this.mapper = Objects.requireNonNull(mapper, "mapper");
    }

    @Override
    public void open(OpenRow r) {
        jdbc.sql("""
                INSERT INTO rca_model_call (
                    id, run_id, task_id, attempt_id, action_seq, physical_seq, round_id,
                    role_id, role_version, role_digest, prompt_digest,
                    budget_reservation_id, input_snapshot_digest, config_epoch,
                    release_digest, lease_epoch, state, created_at
                ) VALUES (
                    :id, :runId, :taskId, :attemptId, :actionSeq, :physicalSeq, :roundId,
                    :roleId, :roleVersion, :roleDigest, :promptDigest,
                    :reservationId, :snapshotDigest, :configEpoch,
                    :releaseDigest, :leaseEpoch, 'PENDING', now()
                )
                """)
                .param("id", r.id())
                .param("runId", r.runId())
                .param("taskId", r.taskId())
                .param("attemptId", r.attemptId())
                .param("actionSeq", r.actionSeq())
                .param("physicalSeq", r.physicalSeq())
                .param("roundId", r.roundId())
                .param("roleId", r.roleId())
                .param("roleVersion", r.roleVersion())
                .param("roleDigest", r.roleDigest())
                .param("promptDigest", r.promptDigest())
                .param("reservationId", r.budgetReservationId())
                .param("snapshotDigest", r.inputSnapshotDigest())
                .param("configEpoch", r.configEpoch())
                .param("releaseDigest", r.releaseDigest())
                .param("leaseEpoch", r.leaseEpoch())
                .update();
    }

    @Override
    public boolean succeed(UUID id, UsageOutcome u) {
        int updated = jdbc.sql("""
                UPDATE rca_model_call
                   SET state = 'SUCCESS', settled_at = now(),
                       usage = cast(:usage as jsonb), usage_missing = :usageMissing,
                       cost_micros = :costMicros, pricing_version = :pricingVersion,
                       currency = :currency, provider_request_id = :providerRequestId,
                       route_id = :routeId, requested_model = :requestedModel,
                       latency_ms = :latencyMs, invocation_id = :invocationId
                 WHERE id = :id AND state = 'PENDING'
                """)
                .param("id", id)
                .param("usage", u.usageMissing() ? null : jsonOf(Map.of(
                        "prompt_tokens", u.promptTokens(),
                        "completion_tokens", u.completionTokens(),
                        "total_tokens", u.totalTokens())))
                .param("usageMissing", u.usageMissing())
                .param("costMicros", u.costMicros())
                .param("pricingVersion", u.pricingVersion())
                .param("currency", u.currency())
                .param("providerRequestId", u.providerRequestId())
                .param("routeId", u.routeId())
                .param("requestedModel", u.requestedModel())
                .param("latencyMs", u.latencyMs())
                .param("invocationId", u.gatewayInvocationId())
                .update();
        return updated > 0;
    }

    @Override
    public boolean fail(UUID id, String errorCode) {
        int updated = jdbc.sql("""
                UPDATE rca_model_call
                   SET state = 'FAILED', settled_at = now(), error_code = :code
                 WHERE id = :id AND state = 'PENDING'
                """)
                .param("id", id)
                .param("code", errorCode)
                .update();
        return updated > 0;
    }

    @Override
    public boolean markUnknown(UUID id) {
        int updated = jdbc.sql("""
                UPDATE rca_model_call
                   SET state = 'UNKNOWN', settled_at = now(), error_code = 'TRANSPORT_UNKNOWN'
                 WHERE id = :id AND state = 'PENDING'
                """)
                .param("id", id)
                .update();
        return updated > 0;
    }

    @Override
    public List<UnsettledRow> findUnsettledByRun(UUID runId) {
        return jdbc.sql("""
                SELECT id, task_id, action_seq, physical_seq, state, error_code
                  FROM rca_model_call
                 WHERE run_id = :runId AND state IN ('PENDING', 'UNKNOWN')
                 ORDER BY task_id, action_seq, physical_seq
                """)
                .param("runId", runId)
                .query((rs, rowNum) -> new UnsettledRow(
                        rs.getObject("id", UUID.class),
                        rs.getObject("task_id", UUID.class),
                        rs.getLong("action_seq"),
                        rs.getInt("physical_seq"),
                        rs.getString("state"),
                        rs.getString("error_code")))
                .list();
    }

    private String jsonOf(Map<String, Object> value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException("usage jsonb 序列化失败: " + e.getMessage(), e);
        }
    }
}
