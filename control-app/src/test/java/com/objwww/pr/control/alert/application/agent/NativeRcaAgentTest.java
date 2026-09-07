package com.objwww.pr.control.alert.application.agent;

import com.objwww.pr.control.alert.domain.claim.Claim;
import com.objwww.pr.control.alert.domain.claim.ClaimReducer;
import com.objwww.pr.control.alert.domain.claim.ClaimStatus;
import com.objwww.pr.control.alert.domain.claim.ClaimStore;
import com.objwww.pr.control.alert.domain.claim.ClaimVerdict;
import com.objwww.pr.control.alert.domain.evidence.EvidenceEnvelope;
import com.objwww.pr.control.alert.domain.evidence.EvidenceRepository;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Native RCA Agent 单测（AM4 M4-30，TDD 先行）：消费结构化黑板（带断言注记的证据），
 * 确定性提 Claim 经 Reducer 裁决落 ClaimStore；固定 Snapshot 绑定 → 相同黑板相同
 * Claim 指纹（回放可比较）；不直接发布报告（结构上无报告出口）。
 */
class NativeRcaAgentTest {

    private static final UUID RUN = UUID.randomUUID();
    private static final String SNAPSHOT = "ab".repeat(32);
    private static final String TIME_RANGE = "2026-09-05T07:50:00Z/2026-09-05T08:00:00Z";

    private final MemEvidence evidence = new MemEvidence();
    private final MemClaimStore claims = new MemClaimStore();
    private final NativeRcaAgent agent = new NativeRcaAgent(evidence, claims,
            new ClaimReducer(Set.of(), "am4-policy-v1"));

    @Test
    void sameBlackboardSameFingerprintsReplayComparable() {
        blackboard();
        List<String> first = agent.investigate(RUN, SNAPSHOT, 2L).fingerprints();
        claims.appendResults.clear();

        List<String> second = agent.investigate(RUN, SNAPSHOT, 2L).fingerprints();

        assertThat(second).isEqualTo(first);
        assertThat(second).isNotEmpty();
    }

    @Test
    void multiSourceCorroborationBecomesConfirmedClaim() {
        evidence.insert(envelope("e1", "prometheus", "db.cpu_anomaly", "TRUE", SNAPSHOT));
        evidence.insert(envelope("e2", "logs", "db.cpu_anomaly", "TRUE", SNAPSHOT));

        NativeRcaAgent.NativeResult result = agent.investigate(RUN, SNAPSHOT, 2L);

        assertThat(result.verdicts()).hasSize(1);
        ClaimVerdict verdict = result.verdicts().get(0);
        assertThat(verdict.status()).isEqualTo(ClaimStatus.TRUE);
        assertThat(verdict.corroborated()).isTrue();
        assertThat(verdict.evidenceBasis().name()).isEqualTo("MULTI_SOURCE_CONSISTENT");
    }

    @Test
    void conflictingSourcesBecomeUnknownWithConflictBasis() {
        evidence.insert(envelope("e1", "prometheus", "db.cpu_anomaly", "TRUE", SNAPSHOT));
        evidence.insert(envelope("e2", "logs", "db.cpu_anomaly", "FALSE", SNAPSHOT));

        ClaimVerdict verdict = agent.investigate(RUN, SNAPSHOT, 2L).verdicts().get(0);

        assertThat(verdict.status()).isEqualTo(ClaimStatus.UNKNOWN);
        assertThat(verdict.evidenceBasis().name()).isEqualTo("MULTI_SOURCE_CONFLICT");
    }

    @Test
    void foreignSnapshotEvidenceIsExcluded() {
        evidence.insert(envelope("e1", "prometheus", "db.cpu_anomaly", "TRUE", "ee".repeat(32)));
        evidence.insert(envelope("e2", "prometheus", "db.cpu_anomaly", "TRUE", SNAPSHOT));

        NativeRcaAgent.NativeResult result = agent.investigate(RUN, SNAPSHOT, 2L);

        // 外来快照排除后仅剩单源 → SINGLE_SOURCE，不冒充双源佐证（对齐 E2E-05）
        assertThat(result.verdicts()).hasSize(1);
        assertThat(result.verdicts().get(0).evidenceBasis().name())
                .isEqualTo("SINGLE_SOURCE");
    }

    @Test
    void rawDataWithoutClaimKeyIsIgnored() {
        Map<String, Object> scope = new LinkedHashMap<>();
        scope.put("time_range", TIME_RANGE);
        evidence.insert(EvidenceEnvelope.create(UUID.randomUUID(), RUN, UUID.randomUUID(),
                "metrics.query_range", EvidenceEnvelope.SCHEMA_VERSION, 2L, "prometheus",
                scope, null, null, Map.of("status", "success")));

        assertThat(agent.investigate(RUN, SNAPSHOT, 2L).verdicts()).isEmpty();
    }

    @Test
    void nullSnapshotIsFailClosed() {
        assertThatThrownBy(() -> agent.investigate(RUN, null, 2L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("快照");
    }

    // ------------------------------------------------------------------ 夹具

    private void blackboard() {
        evidence.insert(envelope("e1", "prometheus", "db.cpu_anomaly", "TRUE", SNAPSHOT));
        evidence.insert(envelope("e2", "logs", "db.cpu_anomaly", "TRUE", SNAPSHOT));
        evidence.insert(envelope("e3", "change", "db.recent_change", "TRUE", SNAPSHOT));
    }

    private static EvidenceEnvelope envelope(String idSeed, String source, String claimKey,
            String status, String snapshot) {
        Map<String, Object> scope = new LinkedHashMap<>();
        scope.put("claim_key", claimKey);
        scope.put("claim_status", status);
        scope.put("scope", "db-primary");
        scope.put("time_range", TIME_RANGE);
        scope.put("input_snapshot_digest", snapshot);
        return EvidenceEnvelope.create(UUID.nameUUIDFromBytes(idSeed.getBytes()), RUN,
                UUID.randomUUID(), "metrics.query_range", EvidenceEnvelope.SCHEMA_VERSION,
                2L, source, scope, null, null,
                Map.of("status", "success"));
    }

    static final class MemEvidence implements EvidenceRepository {
        final List<EvidenceEnvelope> rows = new ArrayList<>();

        @Override
        public void insert(EvidenceEnvelope envelope) {
            rows.add(envelope);
        }

        @Override
        public Optional<EvidenceEnvelope> findById(UUID id) {
            return rows.stream().filter(e -> e.evidenceId().equals(id)).findFirst();
        }

        @Override
        public List<EvidenceEnvelope> findByRunId(UUID runId) {
            return rows.stream().filter(e -> e.runId().equals(runId)).toList();
        }
    }

    static final class MemClaimStore implements ClaimStore {
        final List<ClaimVerdict> appendResults = new ArrayList<>();

        @Override
        public ClaimAppendResult append(UUID runId, ClaimVerdict verdict) {
            appendResults.add(verdict);
            return new ClaimAppendResult(com.objwww.pr.control.alert.domain.claim.ClaimProjection.Outcome.CREATED,
                    verdict.fingerprint(), verdict.contentHash(), null, 0, 1L);
        }

        @Override
        public long markUnresolved(UUID runId, com.objwww.pr.control.alert.domain.claim.ClaimIdentity identity,
                String policyVersion) {
            return 1L;
        }

        @Override
        public List<ClaimRow> findByRunId(UUID runId) {
            return List.of();
        }
    }
}
