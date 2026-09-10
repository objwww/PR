package com.objwww.pr.control.alert.application.replay;

import com.objwww.pr.control.alert.application.agent.NativeRcaAgent;
import com.objwww.pr.control.alert.application.holmes.HolmesEvidenceAdapter;
import com.objwww.pr.control.alert.domain.budget.RunBudget;
import com.objwww.pr.control.alert.domain.claim.ClaimIdentity;
import com.objwww.pr.control.alert.domain.claim.ClaimProjection;
import com.objwww.pr.control.alert.domain.claim.ClaimReducer;
import com.objwww.pr.control.alert.domain.claim.ClaimStore;
import com.objwww.pr.control.alert.domain.claim.ClaimVerdict;
import com.objwww.pr.control.alert.domain.evidence.EvidenceEnvelope;
import com.objwww.pr.control.alert.domain.evidence.EvidenceRepository;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Snapshot Shadow Router 单测（AM4 M4-34，TDD 先行）：Holmes/Native 读同一冻结
 * Snapshot（两侧 outcome 均盖章同 snapshot_digest），预算两侧独立（Candidate 耗尽
 * 不动 Baseline），Candidate 失败被捕获隔离（不外抛、不影响 Baseline 结局）；
 * 路由器结构上无报告/发布出口——影子结果不触生产结论。
 *
 * @author wanghua
 * @date 2026-09-05
 */
class SnapshotShadowRouterTest {

    private static final UUID RUN_ID = UUID.randomUUID();
    private static final UUID TASK_ID = UUID.randomUUID();
    private static final long GENERATION = 5L;
    private static final long DEADLINE = 9_000_000_000_000L;
    private static final String POLICY_VERSION = "shadow-policy-test";
    private static final String SNAPSHOT = "ef".repeat(32);
    /** Holmes 基线报告包：两条断言（引用桥接用合法 UUID） */
    private static final String HOLMES_PACKAGE = ("{\"claims\":["
            + "{\"claim_type\":\"cpu_saturation\",\"status\":\"TRUE\",\"component\":\"svc-a\","
            + "\"fault_type\":\"cpu over threshold\",\"evidence_refs\":[\""
            + UUID.randomUUID() + "\"]},"
            + "{\"claim_type\":\"config_change\",\"status\":\"FALSE\",\"component\":\"svc-b\","
            + "\"fault_type\":\"deploy\",\"evidence_refs\":[\""
            + UUID.randomUUID() + "\"]}]}");

    private final HolmesEvidenceAdapter adapter = new HolmesEvidenceAdapter();
    private final ClaimReducer reducer = new ClaimReducer(
            Set.of("holmes", "prometheus"), POLICY_VERSION);
    private final MemClaimStore baselineClaims = new MemClaimStore();
    private final MemClaimStore candidateClaims = new MemClaimStore();
    private final RunBudget baselineBudget = budget(4);
    private final RunBudget candidateBudget = budget(3);

    @Test
    void bothSidesReadSameSnapshotAndCountClaims() {
        SnapshotShadowRouter router = new SnapshotShadowRouter();

        SnapshotShadowRouter.ShadowResult result = router.compare(SNAPSHOT,
                this::baselineRun, this::candidateRun, baselineBudget, candidateBudget);

        assertThat(result.baseline().failReason()).isNull();
        assertThat(result.baseline().succeeded()).isTrue();
        assertThat(result.baseline().claimCount()).isEqualTo(2);
        assertThat(result.candidate().succeeded()).isTrue();
        assertThat(result.candidate().claimCount()).isEqualTo(1);
        // 同一冻结 Snapshot：两侧 outcome 与路由输入三方同 digest
        assertThat(result.baseline().snapshotDigest()).isEqualTo(SNAPSHOT);
        assertThat(result.candidate().snapshotDigest()).isEqualTo(SNAPSHOT);
        // 两侧落库的断言均盖章同 snapshot_digest
        assertThat(baselineClaims.appended).allSatisfy(v ->
                assertThat(v.snapshotDigest()).isEqualTo(SNAPSHOT));
        assertThat(candidateClaims.appended).allSatisfy(v ->
                assertThat(v.snapshotDigest()).isEqualTo(SNAPSHOT));
    }

    @Test
    void candidateFailureIsIsolated() {
        SnapshotShadowRouter router = new SnapshotShadowRouter();

        SnapshotShadowRouter.ShadowResult result = router.compare(SNAPSHOT,
                this::baselineRun, (digest, budget) -> {
                    throw new IllegalStateException("candidate-boom");
                }, baselineBudget, candidateBudget);

        // 失败不外抛：Candidate 结局显式可见（原因携带），Baseline 结局不受影响
        assertThat(result.candidate().succeeded()).isFalse();
        assertThat(result.candidate().failReason()).contains("candidate-boom");
        assertThat(result.baseline().succeeded()).isTrue();
        assertThat(result.baseline().claimCount()).isEqualTo(2);
    }

    @Test
    void budgetsAreIndependentAcrossSides() {
        SnapshotShadowRouter router = new SnapshotShadowRouter();
        // Candidate 侧预算耗尽（3 用满后再扣即抛）——不得动 Baseline 预算
        RunBudget tightCandidate = budget(3);

        SnapshotShadowRouter.ShadowResult result = router.compare(SNAPSHOT,
                this::baselineRun, (digest, budget) -> {
                    budget.consume(RunBudget.Kind.TOOL_CALL, 3);
                    budget.consume(RunBudget.Kind.TOOL_CALL, 1);
                    return candidateRun(digest, budget);
                }, baselineBudget, tightCandidate);

        assertThat(result.candidate().succeeded()).isFalse();
        assertThat(result.candidate().failReason()).contains("BudgetExhausted");
        assertThat(baselineBudget.remaining(RunBudget.Kind.TOOL_CALL)).isEqualTo(3);
        assertThat(tightCandidate.remaining(RunBudget.Kind.TOOL_CALL)).isZero();
    }

    // ------------------------------------------------------------------ 夹具

    /** Baseline 侧：Holmes 报告包 → Claim 归并落库（真实 Adapter 代码） */
    private int baselineRun(String snapshotDigest, RunBudget budget) {
        budget.consume(RunBudget.Kind.TOOL_CALL, 1);
        HolmesEvidenceAdapter.AdaptResult adapted = adapter.adapt(RUN_ID, TASK_ID,
                GENERATION, snapshotDigest, EvidenceEnvelope.SCHEMA_VERSION, HOLMES_PACKAGE);
        if (adapted.outcome() != HolmesEvidenceAdapter.AdaptOutcome.ADAPTED) {
            throw new IllegalStateException(String.valueOf(adapted.failReason()));
        }
        List<ClaimVerdict> verdicts = reducer.reduce(adapted.claims());
        for (ClaimVerdict verdict : verdicts) {
            baselineClaims.append(RUN_ID, verdict);
        }
        return verdicts.size();
    }

    /** Candidate 侧：注记证据 → 冻结成员黑板 → NativeRcaAgent → Claim 落库（真实 Agent 代码） */
    private int candidateRun(String snapshotDigest, RunBudget budget) {
        budget.consume(RunBudget.Kind.TOOL_CALL, 1);
        MemEvidence evidence = new MemEvidence();
        evidence.insert(annotatedEvidence(snapshotDigest));
        // EX-A4a（F05）：黑板=冻结快照成员
        com.objwww.pr.control.alert.support.AlertInMemoryStores.Snapshots snapshots =
                new com.objwww.pr.control.alert.support.AlertInMemoryStores.Snapshots();
        snapshots.freeze(new com.objwww.pr.control.alert.domain.evidence
                        .EvidenceSnapshotRepository.FrozenSnapshot(UUID.randomUUID(), RUN_ID,
                        snapshotDigest, GENERATION, "cfg", "tools", null),
                evidence.rows.stream()
                        .map(e -> new com.objwww.pr.control.alert.domain.evidence
                                .EvidenceSnapshotRepository.SnapshotMemberRow(
                                e.evidenceId(), e.evidenceType(), e.payloadDigest()))
                        .toList());
        NativeRcaAgent candidate = new NativeRcaAgent(evidence, snapshots, candidateClaims,
                reducer);
        NativeRcaAgent.NativeResult result = candidate.investigate(RUN_ID,
                new com.objwww.pr.control.alert.domain.identity.InvestigationInputDigest(
                        snapshotDigest),
                new com.objwww.pr.control.alert.domain.identity.EvidenceSnapshotDigest(
                        snapshotDigest),
                GENERATION);
        return result.verdicts().size();
    }

    private EvidenceEnvelope annotatedEvidence(String snapshotDigest) {
        Map<String, Object> scope = new LinkedHashMap<>();
        scope.put("investigation_input_digest", snapshotDigest);
        scope.put("claim_key", "cpu_saturation");
        scope.put("claim_status", "TRUE");
        scope.put("reason", "cpu over threshold");
        scope.put("scope", "svc-a");
        scope.put("time_range", "07:50/08:00");
        return EvidenceEnvelope.create(UUID.randomUUID(), RUN_ID, TASK_ID,
                "metrics.query_range", EvidenceEnvelope.SCHEMA_VERSION, GENERATION,
                "prometheus", scope, null, null,
                Map.of("status", "success", "data", Map.of("result", List.of("x"))));
    }

    private static RunBudget budget(long toolCalls) {
        return new RunBudget(100, toolCalls, 100, 100, DEADLINE);
    }

    /** Claim 仓储内存件（append 语义同 NativeRcaAgentTest，另存 verdict 供断言） */
    static final class MemClaimStore implements ClaimStore {
        final List<ClaimVerdict> appended = new ArrayList<>();

        @Override
        public ClaimStore.ClaimAppendResult append(UUID runId, ClaimVerdict verdict) {
            appended.add(verdict);
            return new ClaimStore.ClaimAppendResult(ClaimProjection.Outcome.CREATED,
                    verdict.fingerprint(), verdict.contentHash(), null, 0, 1L);
        }

        @Override
        public long markUnresolved(UUID runId, ClaimIdentity identity, String policyVersion) {
            return 1L;
        }

        @Override
        public List<ClaimStore.ClaimRow> findByRunId(UUID runId) {
            return List.of();
        }
    }

    /** 证据仓储内存件 */
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
}
