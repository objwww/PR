package com.objwww.pr.control.infrastructure.persistence;

import com.objwww.pr.control.alert.application.approval.ApprovalStore;
import com.objwww.pr.control.alert.domain.approval.DecisionQuorum;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionOperations;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * V119 审批四账本的 Postgres 实现（PC-C1）。同人重复决策以 UNIQUE 冲突转 false
 * （结构拒绝，非竞态让步）；grant 配额预留以条件 UPDATE CAS（ACTIVE 且未满）。
 */
public class PostgresApprovalStore implements ApprovalStore,
        com.objwww.pr.control.alert.application.approval.ApprovalPlannerGate,
        com.objwww.pr.control.alert.application.approval.ApprovalShadowReader {

    private final JdbcClient jdbc;
    private final TransactionOperations tx;

    public PostgresApprovalStore(JdbcClient jdbc, TransactionOperations tx) {
        this.jdbc = Objects.requireNonNull(jdbc);
        this.tx = Objects.requireNonNull(tx);
    }

    @Override
    public int generationOfRun(UUID runId) {
        Integer generation = tx.execute(status -> jdbc.sql(
                        "select generation from rca_run where id = :run")
                .param("run", runId)
                .query(Integer.class)
                .optional()
                .orElseThrow(() -> new IllegalArgumentException("run 不存在: " + runId)));
        return generation == null ? 0 : generation;
    }

    @Override
    public void insertRequest(RequestView r) {
        tx.executeWithoutResult(status -> jdbc.sql("""
                insert into approval_request(request_id, intent_id, run_id, action_id,
                    action_digest, observed_generation, risk, required_approvers,
                    scope_snapshot, scope_snapshot_hash, policy_version, state,
                    requested_at, expires_at)
                select :id, i.intent_id, i.run_id, i.action_id, i.action_digest,
                    :gen, i.risk, :required, i.scope_snapshot, i.scope_snapshot_hash,
                    :policyVersion, :state, :now, :expires
                  from action_intent i where i.intent_id = :intentId
                """)
                .param("id", r.requestId())
                .param("intentId", r.intentId())
                .param("gen", r.observedGeneration())
                .param("required", r.requiredApprovers())
                .param("policyVersion", r.policyVersion())
                .param("state", r.state())
                .param("now", Timestamp.from(Instant.now()))
                .param("expires", Timestamp.from(r.expiresAt()))
                .update());
    }

    @Override
    public Optional<RequestView> findRequest(UUID requestId) {
        return tx.execute(status -> jdbc.sql("""
                select request_id, intent_id, run_id, action_id, action_digest,
                       observed_generation, required_approvers, scope_snapshot_hash,
                       policy_version, state, expires_at
                  from approval_request where request_id = :id
                """)
                .param("id", requestId)
                .query((rs, n) -> new RequestView(
                        rs.getObject("request_id", UUID.class),
                        rs.getObject("intent_id", UUID.class),
                        rs.getObject("run_id", UUID.class),
                        rs.getString("action_id"),
                        rs.getString("action_digest"),
                        rs.getInt("observed_generation"),
                        rs.getInt("required_approvers"),
                        rs.getString("scope_snapshot_hash"),
                        rs.getString("policy_version"),
                        rs.getString("state"),
                        rs.getTimestamp("expires_at").toInstant()))
                .optional());
    }

    @Override
    public boolean insertDecision(UUID requestId, String approverId, String approverRole,
            boolean approved, Instant at) {
        return Boolean.TRUE.equals(tx.execute(status -> {
            try {
                jdbc.sql("""
                        insert into approval_decisions(decision_id, request_id, approver_id,
                            approver_role, decision, decided_at)
                        values (:id, :requestId, :approverId, :approverRole, :decision, :at)
                        """)
                        .param("id", UUID.randomUUID())
                        .param("requestId", requestId)
                        .param("approverId", approverId)
                        .param("approverRole", approverRole)
                        .param("decision", approved ? "approved" : "denied")
                        .param("at", Timestamp.from(at))
                        .update();
                return true;
            } catch (DataIntegrityViolationException e) {
                return false; // 同人重复决策：UNIQUE 结构拒绝
            }
        }));
    }

    @Override
    public List<DecisionQuorum.Decision> listDecisions(UUID requestId) {
        return tx.execute(status -> jdbc.sql("""
                        select approver_id, approver_role, decision
                          from approval_decisions where request_id = :id order by decided_at
                        """)
                .param("id", requestId)
                .query((rs, n) -> new DecisionQuorum.Decision(rs.getString("approver_id"),
                        rs.getString("approver_role"),
                        "approved".equals(rs.getString("decision"))))
                .list());
    }

    @Override
    public boolean markRequestState(UUID requestId, String expectedState, String nextState,
            Instant decidedAt, String voidReason) {
        return Boolean.TRUE.equals(tx.execute(status -> {
            boolean decided = "APPROVED".equals(nextState) || "DENIED".equals(nextState);
            String sql = "update approval_request set state = :next, updated_at = now()"
                    + (decided ? ", decided_at = :at" : "")
                    + (voidReason == null ? "" : ", void_reason = :reason")
                    + " where request_id = :id and state = :expected";
            var query = jdbc.sql(sql)
                    .param("next", nextState)
                    .param("id", requestId)
                    .param("expected", expectedState);
            if (decided) {
                query = query.param("at", Timestamp.from(decidedAt));
            }
            if (voidReason != null) {
                query = query.param("reason", voidReason);
            }
            return query.update() == 1;
        }));
    }

    @Override
    public List<ExpiredRequest> expirePendingRequests(Instant now) {
        return tx.execute(status -> jdbc.sql("""
                        update approval_request
                           set state = 'EXPIRED', void_reason = 'REQUEST_TTL', updated_at = now()
                         where state = 'PENDING' and expires_at < :now
                        returning request_id, run_id
                        """)
                .param("now", Timestamp.from(now))
                .query((rs, n) -> new ExpiredRequest(rs.getObject("request_id", UUID.class),
                        rs.getObject("run_id", UUID.class)))
                .list());
    }

    @Override
    public void insertGrant(GrantView g) {
        tx.executeWithoutResult(status -> jdbc.sql("""
                insert into approval_grant(grant_id, request_id, run_id, action_id,
                    action_digest, scope_snapshot_hash, policy_version, scope_kind,
                    max_operations, issued_operations, state, issued_at, expires_at)
                values (:id, :requestId, :runId, :actionId, :digest, :snapshotHash,
                    :policyVersion, :scopeKind, :max, 0, 'ACTIVE', :now, :expires)
                """)
                .param("id", g.grantId())
                .param("requestId", g.requestId())
                .param("runId", g.runId())
                .param("actionId", g.actionId())
                .param("digest", g.actionDigest())
                .param("snapshotHash", g.scopeSnapshotHash())
                .param("policyVersion", g.policyVersion())
                .param("scopeKind", g.scopeKind())
                .param("max", g.maxOperations())
                .param("now", Timestamp.from(Instant.now()))
                .param("expires", Timestamp.from(g.expiresAt()))
                .update());
    }

    @Override
    public Optional<GrantView> findActiveGrant(UUID runId, String actionDigest,
            String scopeSnapshotHash, String policyVersion) {
        return tx.execute(status -> jdbc.sql("""
                        select grant_id, request_id, run_id, action_id, action_digest,
                               scope_snapshot_hash, policy_version, scope_kind,
                               max_operations, issued_operations, state, expires_at
                          from approval_grant
                         where run_id = :run and action_digest = :digest
                           and scope_snapshot_hash = :snapshotHash
                           and policy_version = :policyVersion
                           and state = 'ACTIVE' and expires_at > :now
                         order by issued_at desc limit 1
                        """)
                .param("run", runId)
                .param("digest", actionDigest)
                .param("snapshotHash", scopeSnapshotHash)
                .param("policyVersion", policyVersion)
                .param("now", Timestamp.from(Instant.now()))
                .query(this::grantView)
                .optional());
    }

    @Override
    public boolean tryReserveGrantQuota(UUID grantId, Instant at) {
        return Boolean.TRUE.equals(tx.execute(status -> jdbc.sql("""
                        update approval_grant
                           set issued_operations = issued_operations + 1,
                               state = case when issued_operations + 1 >= max_operations
                                            then 'EXHAUSTED' else state end,
                               updated_at = now()
                         where grant_id = :id and state = 'ACTIVE'
                           and issued_operations < max_operations and expires_at > :at
                        """)
                .param("id", grantId)
                .param("at", Timestamp.from(at))
                .update()) == 1);
    }

    @Override
    public Optional<GrantView> findGrant(UUID grantId) {
        return tx.execute(status -> jdbc.sql("""
                        select grant_id, request_id, run_id, action_id, action_digest,
                               scope_snapshot_hash, policy_version, scope_kind,
                               max_operations, issued_operations, state, expires_at
                          from approval_grant where grant_id = :id
                        """)
                .param("id", grantId)
                .query(this::grantView)
                .optional());
    }

    @Override
    public boolean markGrantState(UUID grantId, String expectedState, String nextState,
            Instant at) {
        return Boolean.TRUE.equals(tx.execute(status -> jdbc.sql("""
                        update approval_grant set state = :next, updated_at = now(),
                            revoked_at = case when :next = 'REVOKED' then :at else revoked_at end
                         where grant_id = :id and state = :expected
                        """)
                .param("next", nextState)
                .param("at", Timestamp.from(at))
                .param("id", grantId)
                .param("expected", expectedState)
                .update()) == 1);
    }

    @Override
    public List<ExpiredRequest> expireActiveGrants(Instant now) {
        return tx.execute(status -> jdbc.sql("""
                        update approval_grant
                           set state = 'EXPIRED', updated_at = now()
                         where state = 'ACTIVE' and expires_at < :now
                        returning grant_id, run_id
                        """)
                .param("now", Timestamp.from(now))
                .query((rs, n) -> new ExpiredRequest(rs.getObject("grant_id", UUID.class),
                        rs.getObject("run_id", UUID.class)))
                .list());
    }

    @Override
    public UUID insertAuthorization(UUID grantId, Instant at) {
        return tx.execute(status -> {
            UUID authzId = UUID.randomUUID();
            jdbc.sql("""
                    insert into operation_authorization(authz_id, grant_id, state, issued_at)
                    values (:id, :grantId, 'ISSUED', :at)
                    """)
                    .param("id", authzId)
                    .param("grantId", grantId)
                    .param("at", Timestamp.from(at))
                    .update();
            return authzId;
        });
    }

    @Override
    public boolean consumeAuthorization(UUID authzId, UUID operationId, Instant at) {
        return Boolean.TRUE.equals(tx.execute(status -> jdbc.sql("""
                        update operation_authorization
                           set state = 'CONSUMED', consumed_at = :at, operation_id = :opId
                         where authz_id = :id and state = 'ISSUED'
                        """)
                .param("at", Timestamp.from(at))
                .param("opId", operationId)
                .param("id", authzId)
                .update()) == 1);
    }

    @Override
    public Optional<AuthzView> findAuthorization(UUID authzId) {
        return tx.execute(status -> jdbc.sql("""
                        select authz_id, grant_id, operation_id, state
                          from operation_authorization where authz_id = :id
                        """)
                .param("id", authzId)
                .query((rs, n) -> new AuthzView(rs.getObject("authz_id", UUID.class),
                        rs.getObject("grant_id", UUID.class),
                        rs.getObject("operation_id", UUID.class),
                        rs.getString("state")))
                .optional());
    }

    private GrantView grantView(ResultSet rs, int n) throws SQLException {
        return new GrantView(rs.getObject("grant_id", UUID.class),
                rs.getObject("request_id", UUID.class),
                rs.getObject("run_id", UUID.class),
                rs.getString("action_id"),
                rs.getString("action_digest"),
                rs.getString("scope_snapshot_hash"),
                rs.getString("policy_version"),
                rs.getString("scope_kind"),
                rs.getInt("max_operations"),
                rs.getInt("issued_operations"),
                rs.getString("state"),
                rs.getTimestamp("expires_at").toInstant());
    }

    @Override
    public java.util.Map<String, Object> summary() {
        return tx.execute(status -> jdbc.sql("""
                        select (select count(*) from approval_request) as requests,
                               (select count(*) from approval_request
                                 where state = 'APPROVED') as approved,
                               (select count(*) from approval_request
                                 where state = 'DENIED') as denied,
                               (select count(*) from approval_request
                                 where state = 'EXPIRED') as expired,
                               (select count(*) from approval_grant) as grants,
                               (select count(*) from operation_authorization
                                 where state = 'CONSUMED') as consumed_authzs,
                               (select coalesce(avg(extract(epoch from
                                   (decided_at - requested_at))), 0)
                                  from approval_request where decided_at is not null)
                                   as avg_decision_seconds
                        """)
                .query((rs, n) -> {
                    java.util.Map<String, Object> m = new java.util.LinkedHashMap<>();
                    m.put("requests", rs.getLong("requests"));
                    m.put("approved", rs.getLong("approved"));
                    m.put("denied", rs.getLong("denied"));
                    m.put("expired", rs.getLong("expired"));
                    m.put("grants", rs.getLong("grants"));
                    m.put("consumed_authorizations", rs.getLong("consumed_authzs"));
                    m.put("avg_decision_seconds", rs.getDouble("avg_decision_seconds"));
                    return m;
                })
                .single());
    }
}
