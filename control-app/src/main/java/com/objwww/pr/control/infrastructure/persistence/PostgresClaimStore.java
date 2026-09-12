package com.objwww.pr.control.infrastructure.persistence;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.objwww.pr.control.alert.domain.claim.ClaimIdentity;
import com.objwww.pr.control.alert.domain.claim.ClaimKind;
import com.objwww.pr.control.alert.domain.claim.ClaimLifecycle;
import com.objwww.pr.control.alert.domain.claim.ClaimProjection;
import com.objwww.pr.control.alert.domain.claim.ClaimStatus;
import com.objwww.pr.control.alert.domain.claim.ClaimStore;
import com.objwww.pr.control.alert.domain.claim.ClaimVerdict;
import com.objwww.pr.control.alert.domain.claim.EvidenceBasis;
import com.objwww.pr.control.alert.domain.event.RcaEventAppender;
import com.objwww.pr.control.alert.domain.tool.InternalCanonicalJsonV1;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionOperations;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * V17 断言投影仓储的 Postgres 实现（AM4 M4-21/22）。四分支判定语义在
 * {@link ClaimProjection}（纯域函数，穷举 UT），本类只执行：
 * <ul>
 *   <li>CREATED：插行 + 同事务把同 proposition 更旧代际 ACTIVE 投影标 SUPERSEDED
 *       （只动 lifecycle 列，内容列零改写）+ CLAIM_CREATED 事件（载荷携带被取代
 *       fingerprint 列表）；</li>
 *   <li>UNCHANGED：只追加 CLAIM_UNCHANGED 事件（同 event_id 同 digest 重放 =
 *       事件账本幂等）；</li>
 *   <li>REVISED：CAS 更新（{@code WHERE claim_hash = prior}）+ CLAIM_REVISED 事件
 *       （载荷携带修订前内容——当前投影被更新，旧内容只活在事件账本，历史不可变）。
 *       CAS 落空 = 并发修订抢先，事务整体回滚后外层重读重判（收敛于 UNCHANGED 或
 *       基于最新内容的再次 REVISED）。</li>
 * </ul>
 * 行写与判定事件同短事务（状态事实，join 调用方事务）；并发同 fingerprint 首插由
 * uq_rca_claim_fingerprint 裁胜负，败者重试重读。event_id 为 nameUUIDFromBytes
 * 内容寻址（重放确定幂等）。
 */
public class PostgresClaimStore implements ClaimStore {

    /** CAS 落空/唯一键撞车的重试信号（事务整体回滚后外层重读重判） */
    private static final class RetrySignal extends RuntimeException {
        private RetrySignal(String message) {
            super(message);
        }
    }

    private static final int MAX_ATTEMPTS = 4;
    private static final TypeReference<List<String>> LIST_STRING = new TypeReference<>() {
    };

    private static final String SELECT_COLUMNS = """
            select id, run_id, claim_fingerprint, claim_hash, claim_key, status,
                   evidence_basis, lifecycle, reason, scope, time_range,
                   observed_generation, sources, evidence_refs, policy_version,
                   snapshot_digest, kind
              from rca_claim
            """;

    private final JdbcClient jdbc;
    private final TransactionOperations tx;
    private final ObjectMapper mapper;
    private final RcaEventAppender events;

    public PostgresClaimStore(JdbcClient jdbc, TransactionOperations tx,
            ObjectMapper mapper, RcaEventAppender events) {
        this.jdbc = Objects.requireNonNull(jdbc);
        this.tx = Objects.requireNonNull(tx);
        this.mapper = Objects.requireNonNull(mapper);
        this.events = Objects.requireNonNull(events);
    }

    @Override
    public ClaimAppendResult append(UUID runId, ClaimVerdict verdict) {
        Objects.requireNonNull(runId, "runId");
        Objects.requireNonNull(verdict, "verdict");
        String fingerprint = verdict.fingerprint();
        String claimHash = verdict.contentHash();
        RetrySignal lastSignal = null;
        for (int attempt = 0; attempt < MAX_ATTEMPTS; attempt++) {
            try {
                return tx.execute(status ->
                        appendOnce(runId, verdict, fingerprint, claimHash));
            } catch (RetrySignal | DuplicateKeyException e) {
                lastSignal = e instanceof RetrySignal rs
                        ? rs
                        : new RetrySignal("唯一键撞车");
            }
        }
        throw new IllegalStateException(
                "断言投影并发未收敛（fingerprint=" + fingerprint + "）：" + lastSignal.getMessage());
    }

    private ClaimAppendResult appendOnce(UUID runId, ClaimVerdict verdict,
            String fingerprint, String claimHash) {
        ClaimRow existing = findByFingerprint(runId, fingerprint);
        ClaimProjection.Decision decision = ClaimProjection.decide(
                existing == null ? null : existing.claimHash(), claimHash);
        return switch (decision.outcome()) {
            case CREATED -> create(runId, verdict, fingerprint, claimHash);
            case UNCHANGED -> {
                Map<String, Object> payload = payload();
                payload.put("fingerprint", fingerprint);
                payload.put("claimHash", claimHash);
                long seq = events.append(runId, new RcaEventAppender.EventDraft(
                        deterministicEventId("claim/" + fingerprint + "/unchanged"),
                        "CLAIM_UNCHANGED", canonical(payload)));
                yield new ClaimAppendResult(ClaimProjection.Outcome.UNCHANGED,
                        fingerprint, claimHash, decision.priorHash(), 0, seq);
            }
            case REVISED -> revise(runId, verdict, fingerprint, claimHash, existing);
        };
    }

    private ClaimAppendResult create(UUID runId, ClaimVerdict verdict,
            String fingerprint, String claimHash) {
        List<String> superseded = findSupersededFingerprints(runId, verdict);
        insertRow(runId, verdict, fingerprint, claimHash);
        markSuperseded(runId, verdict);
        Map<String, Object> payload = payload();
        payload.put("fingerprint", fingerprint);
        payload.put("claimHash", claimHash);
        payload.put("supersededFingerprints", superseded);
        long seq = events.append(runId, new RcaEventAppender.EventDraft(
                deterministicEventId("claim/" + fingerprint + "/created"),
                "CLAIM_CREATED", canonical(payload)));
        return new ClaimAppendResult(ClaimProjection.Outcome.CREATED,
                fingerprint, claimHash, null, superseded.size(), seq);
    }

    private ClaimAppendResult revise(UUID runId, ClaimVerdict verdict,
            String fingerprint, String claimHash, ClaimRow existing) {
        int updated = jdbc.sql("""
                update rca_claim set claim_hash = :hash, status = :status,
                    evidence_basis = :basis, reason = :reason, kind = :kind,
                    sources = cast(:sources as jsonb),
                    evidence_refs = cast(:refs as jsonb),
                    policy_version = :policy, updated_at = now()
                  where run_id = :run and claim_fingerprint = :fp and claim_hash = :prior
                """)
                .param("hash", claimHash)
                .param("status", verdict.status().name())
                .param("basis", verdict.evidenceBasis().name())
                .param("reason", verdict.reason())
                .param("kind", verdict.kind().name())
                .param("sources", jsonOf(verdict.sources()))
                .param("refs", jsonOf(verdict.evidenceRefs()))
                .param("policy", verdict.policyVersion())
                .param("run", runId)
                .param("fp", fingerprint)
                .param("prior", existing.claimHash())
                .update();
        if (updated == 0) {
            throw new RetrySignal("CAS 落空（并发修订抢先）");
        }
        Map<String, Object> payload = payload();
        payload.put("fingerprint", fingerprint);
        payload.put("prior", contentOf(existing));
        payload.put("revised", contentOfVerdict(verdict, claimHash));
        long seq = events.append(runId, new RcaEventAppender.EventDraft(
                deterministicEventId("claim/" + fingerprint + "/revised/" + existing.claimHash()),
                "CLAIM_REVISED", canonical(payload)));
        return new ClaimAppendResult(ClaimProjection.Outcome.REVISED,
                fingerprint, claimHash, existing.claimHash(), 0, seq);
    }

    @Override
    public long markUnresolved(UUID runId, ClaimIdentity identity, String policyVersion) {
        Objects.requireNonNull(runId, "runId");
        Objects.requireNonNull(identity, "identity");
        Objects.requireNonNull(policyVersion, "policyVersion");
        String fingerprint = identity.fingerprint();
        Map<String, Object> payload = payload();
        payload.put("fingerprint", fingerprint);
        payload.put("claimKey", identity.claimKey());
        payload.put("scope", identity.scope());
        payload.put("timeRange", identity.timeRange());
        payload.put("observedGeneration", identity.observedGeneration());
        payload.put("snapshotDigest", identity.snapshotDigest());
        payload.put("policyVersion", policyVersion);
        return tx.execute(status -> events.append(runId, new RcaEventAppender.EventDraft(
                deterministicEventId("claim/" + fingerprint + "/unresolved"),
                "CLAIM_UNRESOLVED", canonical(payload))));
    }

    @Override
    public List<ClaimRow> findByRunId(UUID runId) {
        return jdbc.sql(SELECT_COLUMNS + " where run_id = :run order by claim_key, claim_fingerprint")
                .param("run", runId)
                .query((rs, n) -> mapRow(rs))
                .list();
    }

    // ------------------------------------------------------------------ 内部

    private ClaimRow findByFingerprint(UUID runId, String fingerprint) {
        List<ClaimRow> rows = jdbc.sql(SELECT_COLUMNS
                        + " where run_id = :run and claim_fingerprint = :fp")
                .param("run", runId).param("fp", fingerprint)
                .query((rs, n) -> mapRow(rs))
                .list();
        return rows.isEmpty() ? null : rows.get(0);
    }

    private ClaimRow mapRow(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new ClaimRow(
                rs.getObject("id", UUID.class),
                rs.getObject("run_id", UUID.class),
                rs.getString("claim_fingerprint"),
                rs.getString("claim_hash"),
                rs.getString("claim_key"),
                ClaimStatus.valueOf(rs.getString("status")),
                EvidenceBasis.valueOf(rs.getString("evidence_basis")),
                ClaimLifecycle.valueOf(rs.getString("lifecycle")),
                rs.getString("reason"),
                rs.getString("scope"),
                rs.getString("time_range"),
                rs.getLong("observed_generation"),
                listOf(rs.getString("sources")),
                listOf(rs.getString("evidence_refs")),
                rs.getString("policy_version"),
                rs.getString("snapshot_digest"),
                rs.getString("kind") == null ? null : ClaimKind.valueOf(rs.getString("kind")));
    }

    private void insertRow(UUID runId, ClaimVerdict verdict, String fingerprint, String claimHash) {
        jdbc.sql("""
                insert into rca_claim(id, run_id, claim_fingerprint, claim_hash, claim_key,
                    status, evidence_basis, lifecycle, reason, scope, time_range,
                    observed_generation, sources, evidence_refs, policy_version,
                    snapshot_digest, kind)
                values (:id, :run, :fp, :hash, :key, :status, :basis, 'ACTIVE', :reason,
                    :scope, :timeRange, :gen, cast(:sources as jsonb),
                    cast(:refs as jsonb), :policy, :snapshot, :kind)
                """)
                .param("id", UUID.randomUUID()).param("run", runId)
                .param("fp", fingerprint).param("hash", claimHash)
                .param("key", verdict.claimKey())
                .param("status", verdict.status().name())
                .param("basis", verdict.evidenceBasis().name())
                .param("reason", verdict.reason())
                .param("scope", verdict.scope())
                .param("timeRange", verdict.timeRange())
                .param("gen", verdict.observedGeneration())
                .param("sources", jsonOf(verdict.sources()))
                .param("refs", jsonOf(verdict.evidenceRefs()))
                .param("policy", verdict.policyVersion())
                .param("snapshot", verdict.snapshotDigest())
                .param("kind", verdict.kind().name())
                .update();
    }

    /** 同 proposition（key+归一 scope+time_range）更旧代际 ACTIVE 投影 → 待取代面 */
    private List<String> findSupersededFingerprints(UUID runId, ClaimVerdict verdict) {
        return jdbc.sql("""
                select claim_fingerprint from rca_claim
                 where run_id = :run and claim_key = :key and scope = :scope
                   and time_range = :timeRange and lifecycle = 'ACTIVE'
                   and observed_generation < :gen
                """)
                .param("run", runId)
                .param("key", verdict.claimKey())
                .param("scope", verdict.scope().strip())
                .param("timeRange", verdict.timeRange())
                .param("gen", verdict.observedGeneration())
                .query((rs, n) -> rs.getString("claim_fingerprint"))
                .list();
    }

    private void markSuperseded(UUID runId, ClaimVerdict verdict) {
        jdbc.sql("""
                update rca_claim set lifecycle = 'SUPERSEDED', updated_at = now()
                 where run_id = :run and claim_key = :key and scope = :scope
                   and time_range = :timeRange and lifecycle = 'ACTIVE'
                   and observed_generation < :gen
                """)
                .param("run", runId)
                .param("key", verdict.claimKey())
                .param("scope", verdict.scope().strip())
                .param("timeRange", verdict.timeRange())
                .param("gen", verdict.observedGeneration())
                .update();
    }

    /** 修订事件载荷：修订前内容（取行上内容列） */
    private Map<String, Object> contentOf(ClaimRow row) {
        Map<String, Object> content = payload();
        content.put("claimHash", row.claimHash());
        content.put("status", row.status().name());
        content.put("reason", row.reason());
        content.put("evidenceRefs", row.evidenceRefs());
        content.put("sources", row.sources());
        content.put("policyVersion", row.policyVersion());
        return content;
    }

    private Map<String, Object> contentOfVerdict(ClaimVerdict verdict, String claimHash) {
        Map<String, Object> content = payload();
        content.put("claimHash", claimHash);
        content.put("status", verdict.status().name());
        content.put("reason", verdict.reason());
        content.put("evidenceRefs", verdict.evidenceRefs());
        content.put("sources", verdict.sources());
        content.put("policyVersion", verdict.policyVersion());
        return content;
    }

    private static LinkedHashMap<String, Object> payload() {
        return new LinkedHashMap<>();
    }

    private static UUID deterministicEventId(String key) {
        return UUID.nameUUIDFromBytes(key.getBytes(StandardCharsets.UTF_8));
    }

    private String jsonOf(List<String> values) {
        try {
            return mapper.writeValueAsString(values);
        } catch (Exception e) {
            throw new IllegalArgumentException("列表序列化失败", e);
        }
    }

    private List<String> listOf(String json) {
        if (json == null) {
            return List.of();
        }
        try {
            return List.copyOf(mapper.readValue(json, LIST_STRING));
        } catch (Exception e) {
            throw new IllegalStateException("jsonb 列表解析失败: " + e.getMessage(), e);
        }
    }

    private String canonical(Map<String, Object> payload) {
        return InternalCanonicalJsonV1.canonicalize(payload);
    }
}
