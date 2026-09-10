package com.objwww.pr.control.alert.application.agent;

import com.objwww.pr.control.alert.domain.claim.Claim;
import com.objwww.pr.control.alert.domain.claim.ClaimReducer;
import com.objwww.pr.control.alert.domain.claim.ClaimStatus;
import com.objwww.pr.control.alert.domain.claim.ClaimStore;
import com.objwww.pr.control.alert.domain.claim.ClaimVerdict;
import com.objwww.pr.control.alert.domain.evidence.EvidenceEnvelope;
import com.objwww.pr.control.alert.domain.evidence.EvidenceRepository;
import com.objwww.pr.control.alert.domain.evidence.EvidenceSnapshotRepository;
import com.objwww.pr.control.alert.domain.identity.EvidenceSnapshotDigest;
import com.objwww.pr.control.alert.domain.identity.InvestigationInputDigest;
import com.objwww.pr.control.alert.support.AlertInMemoryStores;
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
 *
 * <p>EX-A0 三身份分野（F04）：输入比对输入（{@code investigation_input_digest}
 * scope vs run 输入身份）、Claim 绑定输出快照身份——两个身份参数独立类型，混用
 * 编译期拒绝。
 *
 * <p>EX-A4a（F05）黑板收紧：黑板 = 冻结快照成员表（非 run 全量证据）——缺快照行/
 * 缺成员证据行/成员身份不符一律显式失败；冻结后迟到证据结构性不可入黑板。
 */
class NativeRcaAgentTest {

    private static final UUID RUN = UUID.randomUUID();
    private static final InvestigationInputDigest INPUT =
            new InvestigationInputDigest("ab".repeat(32));
    private static final EvidenceSnapshotDigest SNAPSHOT =
            new EvidenceSnapshotDigest("cd".repeat(32));
    private static final String TIME_RANGE = "2026-09-05T07:50:00Z/2026-09-05T08:00:00Z";

    private final MemEvidence evidence = new MemEvidence();
    private final AlertInMemoryStores.Snapshots snapshots = new AlertInMemoryStores.Snapshots();
    private final MemClaimStore claims = new MemClaimStore();
    private final NativeRcaAgent agent = new NativeRcaAgent(evidence, snapshots, claims,
            new ClaimReducer(Set.of(), "am4-policy-v1"));

    @Test
    void sameBlackboardSameFingerprintsReplayComparable() {
        blackboard();
        List<String> first = agent.investigate(RUN, INPUT, SNAPSHOT, 2L).fingerprints();
        claims.appendResults.clear();

        List<String> second = agent.investigate(RUN, INPUT, SNAPSHOT, 2L).fingerprints();

        assertThat(second).isEqualTo(first);
        assertThat(second).isNotEmpty();
    }

    @Test
    void multiSourceCorroborationBecomesConfirmedClaim() {
        evidence.insert(envelope("e1", "prometheus", "db.cpu_anomaly", "TRUE", INPUT));
        evidence.insert(envelope("e2", "logs", "db.cpu_anomaly", "TRUE", INPUT));
        freezeBlackboard();

        NativeRcaAgent.NativeResult result = agent.investigate(RUN, INPUT, SNAPSHOT, 2L);

        assertThat(result.verdicts()).hasSize(1);
        ClaimVerdict verdict = result.verdicts().get(0);
        assertThat(verdict.status()).isEqualTo(ClaimStatus.TRUE);
        assertThat(verdict.corroborated()).isTrue();
        assertThat(verdict.evidenceBasis().name()).isEqualTo("MULTI_SOURCE_CONSISTENT");
        // Claim 绑定输出快照身份（不是输入身份）
        assertThat(verdict.snapshotDigest()).isEqualTo(SNAPSHOT.hex());
        // EX-A4a（F06）：无类型断言经 compat 构造默认 HYPOTHESIS（保守形态，
        // 结构性禁止默认 ROOT_CAUSE——"症状自动升级为根因"的消灭面）
        assertThat(verdict.kind()).isEqualTo(com.objwww.pr.control.alert.domain.claim.ClaimKind.HYPOTHESIS);
    }

    @Test
    void conflictingSourcesBecomeUnknownWithConflictBasis() {
        evidence.insert(envelope("e1", "prometheus", "db.cpu_anomaly", "TRUE", INPUT));
        evidence.insert(envelope("e2", "logs", "db.cpu_anomaly", "FALSE", INPUT));
        freezeBlackboard();

        ClaimVerdict verdict = agent.investigate(RUN, INPUT, SNAPSHOT, 2L).verdicts().get(0);

        assertThat(verdict.status()).isEqualTo(ClaimStatus.UNKNOWN);
        assertThat(verdict.evidenceBasis().name()).isEqualTo("MULTI_SOURCE_CONFLICT");
    }

    @Test
    void matchingInputDigestEvidenceIsAccepted() {
        // EX-A0 契约翻转（原缺陷：输入身份比对输出快照 → 注记证据必被丢弃）：
        // 输入身份与本 run 一致的注记证据必须采纳
        evidence.insert(envelope("e1", "prometheus", "db.cpu_anomaly", "TRUE", INPUT));
        freezeBlackboard();

        NativeRcaAgent.NativeResult result = agent.investigate(RUN, INPUT, SNAPSHOT, 2L);

        assertThat(result.verdicts()).hasSize(1);
        assertThat(result.verdicts().get(0).claimKey()).isEqualTo("db.cpu_anomaly");
    }

    @Test
    void foreignInputDigestEvidenceIsExcluded() {
        evidence.insert(envelope("e1", "prometheus", "db.cpu_anomaly", "TRUE",
                new InvestigationInputDigest("ee".repeat(32))));
        evidence.insert(envelope("e2", "prometheus", "db.cpu_anomaly", "TRUE", INPUT));
        freezeBlackboard();

        NativeRcaAgent.NativeResult result = agent.investigate(RUN, INPUT, SNAPSHOT, 2L);

        // 外来输入身份排除后仅剩单源 → SINGLE_SOURCE，不冒充双源佐证（对齐 E2E-05）
        assertThat(result.verdicts()).hasSize(1);
        assertThat(result.verdicts().get(0).evidenceBasis().name())
                .isEqualTo("SINGLE_SOURCE");
    }

    @Test
    void nullRunInputMeansNoExclusion() {
        // 存量 run（无冻结输入身份）兼容面：不排除任何注记证据
        evidence.insert(envelope("e1", "prometheus", "db.cpu_anomaly", "TRUE", INPUT));
        freezeBlackboard();

        assertThat(agent.investigate(RUN, null, SNAPSHOT, 2L).verdicts()).hasSize(1);
    }

    @Test
    void rawDataWithoutClaimKeyIsIgnored() {
        Map<String, Object> scope = new LinkedHashMap<>();
        scope.put("time_range", TIME_RANGE);
        evidence.insert(EvidenceEnvelope.create(UUID.randomUUID(), RUN, UUID.randomUUID(),
                "metrics.query_range", EvidenceEnvelope.SCHEMA_VERSION, 2L, "prometheus",
                scope, null, null, Map.of("status", "success")));
        freezeBlackboard();

        assertThat(agent.investigate(RUN, INPUT, SNAPSHOT, 2L).verdicts()).isEmpty();
    }

    @Test
    void nullSnapshotIsFailClosed() {
        assertThatThrownBy(() -> agent.investigate(RUN, INPUT, null, 2L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("快照");
    }

    // -------------------------------------------------- EX-A4a（F05）黑板收紧

    @Test
    void missingSnapshotRowFailsExplicitly() {
        evidence.insert(envelope("e1", "prometheus", "db.cpu_anomaly", "TRUE", INPUT));
        // 未冻结快照：黑板契约 fail-closed，显式失败而非静默空结论
        assertThatThrownBy(() -> agent.investigate(RUN, INPUT, SNAPSHOT, 2L))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("快照行缺失");
    }

    @Test
    void missingMemberEvidenceRowFailsExplicitly() {
        snapshots.freeze(new EvidenceSnapshotRepository.FrozenSnapshot(UUID.randomUUID(),
                RUN, SNAPSHOT.hex(), 2L, "cfg", "tools", null), List.of(
                new EvidenceSnapshotRepository.SnapshotMemberRow(UUID.randomUUID(),
                        "metrics.query_range", "aa".repeat(32))));

        assertThatThrownBy(() -> agent.investigate(RUN, INPUT, SNAPSHOT, 2L))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("缺证据行");
    }

    @Test
    void memberIdentityMismatchFailsExplicitly() {
        EvidenceEnvelope envelope = envelope("e1", "prometheus", "db.cpu_anomaly", "TRUE",
                INPUT);
        evidence.insert(envelope);
        // 成员行 payload_digest 与证据行不符 = 快照完整性破坏
        snapshots.freeze(new EvidenceSnapshotRepository.FrozenSnapshot(UUID.randomUUID(),
                RUN, SNAPSHOT.hex(), 2L, "cfg", "tools", null), List.of(
                new EvidenceSnapshotRepository.SnapshotMemberRow(envelope.evidenceId(),
                        envelope.evidenceType(), "ff".repeat(32))));

        assertThatThrownBy(() -> agent.investigate(RUN, INPUT, SNAPSHOT, 2L))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("身份不符");
    }

    @Test
    void lateEvidenceCannotEnterFrozenBlackboard() {
        evidence.insert(envelope("e1", "prometheus", "db.cpu_anomaly", "TRUE", INPUT));
        freezeBlackboard();
        // 冻结后迟到证据（同键注记）——不进黑板，双源佐证不成立
        evidence.insert(envelope("e2", "logs", "db.cpu_anomaly", "TRUE", INPUT));

        NativeRcaAgent.NativeResult result = agent.investigate(RUN, INPUT, SNAPSHOT, 2L);

        assertThat(result.verdicts()).hasSize(1);
        assertThat(result.verdicts().get(0).evidenceBasis().name())
                .isEqualTo("SINGLE_SOURCE");
    }

    // ------------------------------------------------------------------ 夹具

    private void blackboard() {
        evidence.insert(envelope("e1", "prometheus", "db.cpu_anomaly", "TRUE", INPUT));
        evidence.insert(envelope("e2", "logs", "db.cpu_anomaly", "TRUE", INPUT));
        evidence.insert(envelope("e3", "change", "db.recent_change", "TRUE", INPUT));
        freezeBlackboard();
    }

    /** F05：把当前 evidence 全量冻结为 SNAPSHOT（成员=冻结面，此后迟到证据不可入黑板） */
    private void freezeBlackboard() {
        List<EvidenceSnapshotRepository.SnapshotMemberRow> members = evidence.rows.stream()
                .map(e -> new EvidenceSnapshotRepository.SnapshotMemberRow(
                        e.evidenceId(), e.evidenceType(), e.payloadDigest()))
                .toList();
        snapshots.freeze(new EvidenceSnapshotRepository.FrozenSnapshot(UUID.randomUUID(),
                RUN, SNAPSHOT.hex(), 2L, "cfg", "tools", null), members);
    }

    private static EvidenceEnvelope envelope(String idSeed, String source, String claimKey,
            String status, InvestigationInputDigest input) {
        Map<String, Object> scope = new LinkedHashMap<>();
        scope.put("claim_key", claimKey);
        scope.put("claim_status", status);
        scope.put("scope", "db-primary");
        scope.put("time_range", TIME_RANGE);
        scope.put("investigation_input_digest", input.hex());
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
