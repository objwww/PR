package com.objwww.pr.control.infrastructure.persistence;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.objwww.pr.control.alert.domain.agent.RcaModelCallFenceException;
import com.objwww.pr.control.alert.domain.agent.RcaModelCallLedger;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionOperations;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * rca_model_call 的 Postgres 实现（R7a-1，V48）。PENDING 先行 + 终态 CAS
 * （WHERE state='PENDING' 判 affected）；未结算读 = state IN (PENDING, UNKNOWN)。
 *
 * <p>EN-04（H04 调度闸）：open 在 run 行锁（FOR UPDATE）内先验 epoch 栅栏——动作
 * 携带的 configEpoch 已落后于 rca_run_config_epoch 现行代际 = RcaModelCallFenceException
 * （零触网，非重试）。与切换应用事务共享 rca_run 行锁与同一锁序（§225/§229）：切换
 * 的"无在飞计数"与动作领取串行化，无计数检查空窗；锁内 check-then-insert 即原子
 * （BA-44 同律）。物理调用在 open 返回、锁释放后才发生（§229 不持库锁触网）。
 * configEpoch 为空的存量动作 = EN-04 前铸造面，栅栏放行（诚实留白不冒充）。
 */
public class PostgresRcaModelCallLedger implements RcaModelCallLedger {

    private final JdbcClient jdbc;
    private final ObjectMapper mapper;
    private final TransactionOperations tx;

    public PostgresRcaModelCallLedger(JdbcClient jdbc, ObjectMapper mapper,
            TransactionOperations tx) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        this.mapper = Objects.requireNonNull(mapper, "mapper");
        this.tx = Objects.requireNonNull(tx, "tx");
    }

    @Override
    public void open(OpenRow r) {
        tx.executeWithoutResult(status -> {
            // ① run 行锁（与切换应用同锁序：§229 统一避免死锁/TOCTOU）
            jdbc.sql("select id from rca_run where id = :runId for update")
                    .param("runId", r.runId())
                    .query((rs, n) -> rs.getString(1))
                    .optional()
                    .orElseThrow(() -> new IllegalStateException(
                            "run 不存在，账本拒绝落行: " + r.runId()));
            // ② epoch 栅栏（H04）：动作代际已落后 = 切换已生效，旧动作不得再领资格
            if (r.configEpoch() != null && jdbc.sql("""
                            SELECT count(*) FROM rca_run_config_epoch
                             WHERE run_id = :runId AND config_epoch > :epoch
                            """)
                    .param("runId", r.runId())
                    .param("epoch", r.configEpoch())
                    .query(Long.class).single() > 0) {
                throw new RcaModelCallFenceException(
                        "动作 configEpoch=" + r.configEpoch() + " 已落后于当前代际"
                                + "（run=" + r.runId() + "），零触网拒绝发送");
            }
            // ③ 锁内落 PENDING 行（取得发送资格）
            insert(r);
        });
    }

    private void insert(OpenRow r) {
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

    /** R6 评测费用链接线：run 全部已结算行逐调用读面（FAILED/UNKNOWN 行 usage/cost 恒空）。
     *  usage 是 V48 jsonb 列（{prompt_tokens,completion_tokens,total_tokens}）——
     *  195 真机 R4 探针首证：裸列名 SELECT 在真 PG 直接 BadSqlGrammar，必须从 jsonb 取键。 */
    @Override
    public List<CallUsage> listSettledUsageByRunId(UUID runId) {
        return jdbc.sql("""
                SELECT attempt_id, role_id, action_seq, physical_seq,
                       (usage->>'prompt_tokens')::int AS prompt_tokens,
                       (usage->>'completion_tokens')::int AS completion_tokens,
                       (usage->>'total_tokens')::int AS total_tokens,
                       cost_micros, pricing_version, currency, usage_missing, state
                  FROM rca_model_call
                 WHERE run_id = :runId AND state <> 'PENDING'
                 ORDER BY attempt_id, action_seq, physical_seq
                """)
                .param("runId", runId)
                .query((rs, rowNum) -> new CallUsage(
                        rs.getObject("attempt_id", UUID.class),
                        rs.getString("role_id"),
                        rs.getLong("action_seq"),
                        rs.getInt("physical_seq"),
                        (Integer) rs.getObject("prompt_tokens"),
                        (Integer) rs.getObject("completion_tokens"),
                        (Integer) rs.getObject("total_tokens"),
                        (Long) rs.getObject("cost_micros"),
                        rs.getString("pricing_version"),
                        rs.getString("currency"),
                        rs.getBoolean("usage_missing"),
                        rs.getString("state")))
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
