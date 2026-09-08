package com.objwww.pr.control.it;

import com.objwww.pr.control.infrastructure.persistence.PostgresCanaryWindowVerdictRepository;
import com.objwww.pr.control.release.domain.model.CanaryEvidenceClass;
import com.objwww.pr.control.release.domain.model.CanaryWindowPolicy;
import com.objwww.pr.control.release.domain.repository.CanaryWindowVerdictRepository;
import com.objwww.pr.control.release.domain.service.CanaryWindowEvaluator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * M6-01 IT 案③（落码方案 §M6-01 测试清单 IT 面；INV-AM6-5 真 PG 面，195 补证）：
 * <b>DRILL 全链即使指标全优也无 promotion eligibility</b>——全优 DRILL 窗照常评判
 * 落档（PASS 判定本身有效，只证机制），但连续 K 窗晋升资格仅数 LIVE_CANARY；
 * 非 LIVE 窗占槽自然断链；正对照 = 同形 LIVE 链可晋升（先证能拒、再证能过）。
 * 判定行经 {@link PostgresCanaryWindowVerdictRepository} 真 PG append-only 落档
 * 再读回重评（uq_cwv_window 同窗幂等），非内存面自说自话。本机无 docker 自动跳过。
 */
class CanaryWindowPromotionIT extends PostgresITBase {

    private static final Instant WINDOW_START = Instant.parse("2026-09-09T10:00:00Z");
    private static final String POLICY_DIGEST = "policy-d-it";
    private static final String CAPABILITY_DIGEST = "cap-d-it";

    private PostgresCanaryWindowVerdictRepository verdicts;
    private CanaryWindowPolicy policy;
    private CanaryWindowEvaluator evaluator;

    @BeforeEach
    void setUp() {
        verdicts = new PostgresCanaryWindowVerdictRepository(
                JdbcClient.create(controlDataSource()));
        evaluator = new CanaryWindowEvaluator();
        policy = new CanaryWindowPolicy(2, 3, Duration.ofMinutes(60), 0.2, 0.1);
    }

    // ---------------------------------------- 案③ 主案：DRILL 全优 PASS ≠ 晋升资格

    @Test
    void drillWindowsPerfectMetricsNeverPromote() {
        UUID rollout = UUID.randomUUID();
        String candidate = "cand-drill";
        for (int seq = 1; seq <= 3; seq++) {
            CanaryWindowEvaluator.Draft draft = evaluator.evaluate(
                    identity(rollout, candidate, seq, CanaryEvidenceClass.DRILL),
                    samples(3, 0), samples(2, 0), Boolean.TRUE, policy);
            assertThat(draft.verdict())
                    .as("DRILL 窗指标全优照常判 PASS（判定有效，只证机制）")
                    .isEqualTo("PASS");
            assertThat(verdicts.append(row(draft,
                    WINDOW_START.plus(Duration.ofMinutes(60L * seq))))).isTrue();
        }

        // 同窗幂等：uq_cwv_window 重评不重记（append-only，零 UPDATE 路径）
        assertThat(verdicts.append(row(evaluator.evaluate(
                identity(rollout, candidate, 3, CanaryEvidenceClass.DRILL),
                samples(3, 0), samples(2, 0), Boolean.TRUE, policy),
                WINDOW_START.plus(Duration.ofMinutes(180))))).isFalse();
        assertThat(count("canary_window_verdict")).isEqualTo(3);

        // 全优 DRILL 链（K=3 连续 PASS）无晋升资格：连续 K 窗仅数 LIVE_CANARY
        assertThat(CanaryWindowEvaluator.hasConsecutivePasses(
                readBack(rollout, candidate), 3))
                .as("INV-AM6-5：DRILL 全优不晋升")
                .isFalse();
    }

    // ----------------------------- 对照面：LIVE 链可晋升 + 非 LIVE 占槽断链

    @Test
    void liveChainPromotesAndDrillSlotBreaksChain() {
        // 正对照：同形 LIVE PASS 链 ≥K → 晋升资格成立（先证能过，函数不是恒 false）
        UUID liveRollout = UUID.randomUUID();
        String liveCandidate = "cand-live";
        for (int seq = 1; seq <= 3; seq++) {
            assertThat(verdicts.append(row(passWindow(liveRollout, liveCandidate, seq,
                    CanaryEvidenceClass.LIVE_CANARY),
                    WINDOW_START.plus(Duration.ofMinutes(60L * seq))))).isTrue();
        }
        assertThat(CanaryWindowEvaluator.hasConsecutivePasses(
                readBack(liveRollout, liveCandidate), 3))
                .as("LIVE 连续 K 窗 PASS = 晋升资格成立")
                .isTrue();

        // 断链：LIVE 1,2 → DRILL 占 3 → LIVE 4——最长 LIVE 连续链被截断为 2 < K=3
        UUID mixedRollout = UUID.randomUUID();
        String mixedCandidate = "cand-mixed";
        assertThat(verdicts.append(row(passWindow(mixedRollout, mixedCandidate, 1,
                CanaryEvidenceClass.LIVE_CANARY), WINDOW_START.plusSeconds(3_600)))).isTrue();
        assertThat(verdicts.append(row(passWindow(mixedRollout, mixedCandidate, 2,
                CanaryEvidenceClass.LIVE_CANARY), WINDOW_START.plusSeconds(7_200)))).isTrue();
        assertThat(verdicts.append(row(passWindow(mixedRollout, mixedCandidate, 3,
                CanaryEvidenceClass.DRILL), WINDOW_START.plusSeconds(10_800)))).isTrue();
        assertThat(verdicts.append(row(passWindow(mixedRollout, mixedCandidate, 4,
                CanaryEvidenceClass.LIVE_CANARY), WINDOW_START.plusSeconds(14_400)))).isTrue();
        assertThat(CanaryWindowEvaluator.hasConsecutivePasses(
                readBack(mixedRollout, mixedCandidate), 3))
                .as("非 LIVE 窗占槽断链（窗序列按 windowSeq 逐窗连续）")
                .isFalse();
    }

    // ------------------------------------------------------------------ 组装助手

    private CanaryWindowEvaluator.WindowIdentity identity(UUID rollout, String candidate,
            int seq, CanaryEvidenceClass evidenceClass) {
        return new CanaryWindowEvaluator.WindowIdentity(rollout, candidate, POLICY_DIGEST,
                CAPABILITY_DIGEST, 1, 1, seq, evidenceClass, WINDOW_START,
                WINDOW_START.plus(Duration.ofMinutes(60)));
    }

    /** 指标全优样本：native 3 独立事件 0 失败，control 2 样本 0 失败（双门全过） */
    private List<CanaryWindowEvaluator.Sample> samples(int count, int failedCount) {
        List<CanaryWindowEvaluator.Sample> out = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            out.add(new CanaryWindowEvaluator.Sample("g:svc-" + i, "tenant-a", "page",
                    i < failedCount, WINDOW_START));
        }
        return out;
    }

    private CanaryWindowEvaluator.Draft passWindow(UUID rollout, String candidate, int seq,
            CanaryEvidenceClass evidenceClass) {
        return evaluator.evaluate(identity(rollout, candidate, seq, evidenceClass),
                samples(3, 0), samples(2, 0), Boolean.TRUE, policy);
    }

    private CanaryWindowVerdictRepository.VerdictRow row(CanaryWindowEvaluator.Draft draft,
            Instant evaluatedAt) {
        CanaryWindowEvaluator.WindowIdentity id = draft.identity();
        return new CanaryWindowVerdictRepository.VerdictRow(id.rolloutId(),
                id.candidateDigest(), id.rolloutPolicyDigest(), id.capabilityDigest(),
                id.fromPercent(), id.toPercent(), id.windowSeq(), id.windowStart(),
                id.windowEnd(), id.evidenceClass().name(), draft.eligibleIncidents(),
                draft.rawCounts(), draft.strata(), draft.control(), draft.absoluteSlo(),
                draft.criticalPass(), null, draft.verdict(), List.of(), evaluatedAt);
    }

    /** 判定行读回重评面：真 PG jsonb 往返后重组成 Draft（晋升资格函数的输入源） */
    private List<CanaryWindowEvaluator.Draft> readBack(UUID rollout, String candidate) {
        return verdicts.findByRollout(rollout, candidate).stream()
                .map(r -> new CanaryWindowEvaluator.Draft(
                        new CanaryWindowEvaluator.WindowIdentity(r.rolloutId(),
                                r.candidateDigest(), r.rolloutPolicyDigest(),
                                r.capabilityDigest(), r.fromPercent(), r.toPercent(),
                                r.windowSeq(), CanaryEvidenceClass.valueOf(r.evidenceClass()),
                                r.windowStart(), r.windowEnd()),
                        r.eligibleIncidents(), r.rawCounts(), r.strata(), r.control(),
                        r.absoluteSlo(), r.criticalPass(), r.verdict()))
                .toList();
    }
}
