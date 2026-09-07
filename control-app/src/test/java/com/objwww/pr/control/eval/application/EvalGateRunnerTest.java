package com.objwww.pr.control.eval.application;

import com.objwww.pr.control.alert.domain.claim.ClaimStatus;
import com.objwww.pr.control.alert.domain.model.EvidencePackageV2;
import com.objwww.pr.control.alert.domain.model.ReportClaim;
import com.objwww.pr.control.alert.domain.model.ToolCallStatus;
import com.objwww.pr.control.alert.domain.model.TypedRootCause;
import com.objwww.pr.control.eval.application.EvalGateRunner.GateBatchRequest;
import com.objwww.pr.control.eval.domain.GoldenCase;
import com.objwww.pr.control.eval.domain.SynonymLexicon;
import com.objwww.pr.control.eval.domain.model.EvalCaseInput;
import com.objwww.pr.control.eval.domain.model.EvaluationRecordV1;
import com.objwww.pr.control.eval.domain.model.GateThresholds;
import com.objwww.pr.control.eval.domain.model.SafetyFace;
import com.objwww.pr.control.eval.domain.repository.EvaluationRecordRepository;
import com.objwww.pr.control.eval.domain.service.PairedTrialStats;
import com.objwww.pr.control.eval.domain.service.PairedTrialStats.PairedOutcome;
import com.objwww.pr.control.eval.domain.service.QualityGate;
import com.objwww.pr.control.eval.domain.service.SafetyGate;
import com.objwww.pr.control.eval.domain.service.SixDimEvaluator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * M5-08 EvalGateRunner 编排 UT（落码方案 §M5-08④）：批量案例 → SixDimEvaluator →
 * SafetyGate（跨案聚合）→ PairedTrialStats（配对差值）→ QualityGate →
 * EvaluationRecordV1 落档（insert-only）。真实性标签必须写入记录（§12.2 原文）；
 * 统计溯源面（stats_seed/算法版本/重采样次数/CI 方法）随记录全带（INV-AM5-3）。
 */
class EvalGateRunnerTest {

    private static final TypedRootCause EXPECTED =
            new TypedRootCause("payment", "BUSINESS_ERROR_RATE", "PAYMENT_CHARGE_FAILURE");

    private static final SynonymLexicon LEX = SynonymLexicon.load("""
            lexicon_version: 1
            components:
              - code: payment
                synonyms: [payment-svc]
            fault_types:
              - code: BUSINESS_ERROR_RATE
                synonyms: [business error rate]
            reason_codes:
              - code: PAYMENT_CHARGE_FAILURE
                fault_type: BUSINESS_ERROR_RATE
                synonyms: [charge failure]
            """);

    private final InMemoryRecords records = new InMemoryRecords();
    private final EvalGateRunner runner = new EvalGateRunner(
            new SixDimEvaluator(new ScenarioEvaluator(LEX)),
            new SafetyGate(), new QualityGate(), records);

    // ------------------------------------------------------------ fixture 面

    /** 测试内存认账面（O-1 记录表落定前的 insert-only 契约锚） */
    static final class InMemoryRecords implements EvaluationRecordRepository {
        final List<EvaluationRecordV1> inserted = new ArrayList<>();

        @Override
        public void insert(EvaluationRecordV1 record) {
            inserted.add(record);
        }
    }

    private static GoldenCase golden() {
        return new GoldenCase("S1", "paymentFailure=50%", "FlagdScenarioDriver", null,
                "payment", EXPECTED, List.of("PAYMENT_CHARGE_FAILURE"),
                new GoldenCase.Timing(1, 1, 1, 1, 1));
    }

    private static EvalCaseInput cleanCase() {
        return new EvalCaseInput(golden(),
                new EvidencePackageV2(2, "summary", EXPECTED,
                        List.of(new ReportClaim("root_cause", ClaimStatus.TRUE, "payment",
                                "BUSINESS_ERROR_RATE", List.of("PAYMENT_CHARGE_FAILURE"),
                                List.of("ref-1"))),
                        List.of("evidence-1"), "impact", "remediation", List.of()),
                List.of(new EvalCaseInput.ToolCallObservation("logs", true,
                        ToolCallStatus.SUCCESS, "d1")),
                List.of(), 100L, new EvalCaseInput.Usage(100L, 50L, 150L, false), false);
    }

    private static EvalCaseInput hallucinatedCase() {
        return new EvalCaseInput(golden(),
                new EvidencePackageV2(2, "summary", EXPECTED, List.of(),
                        List.of("evidence-1"), "impact", "remediation", List.of()),
                List.of(new EvalCaseInput.ToolCallObservation("logs", true,
                        ToolCallStatus.SUCCESS, "d1"),
                        new EvalCaseInput.ToolCallObservation("shadow_exec", false,
                                ToolCallStatus.SUCCESS, "d2")),
                List.of(), 200L, new EvalCaseInput.Usage(100L, 50L, 150L, false), false);
    }

    /** 8 簇全正差值（candidate 全优于 baseline）→ CONCLUSIVE 且 CI 下界 > 0 */
    private static List<PairedOutcome> healthyPairs() {
        List<PairedOutcome> pairs = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            pairs.add(new PairedOutcome("cluster-" + i, false, true));
        }
        return pairs;
    }

    private static GateThresholds thresholds() {
        return new GateThresholds("gate-thresholds-v1", 0.05, 5,
                60_000L, 100_000L, 0.10);
    }

    private GateBatchRequest request(List<EvalCaseInput> cases,
                                     List<PairedOutcome> pairs) {
        return new GateBatchRequest(UUID.randomUUID(),
                EvaluationRecordV1.AuthenticityLabel.PUBLIC_REPLAY,
                cases, pairs, 42L, thresholds(),
                "dataset-digest-aa", "config-digest-bb", "engine-digest-cc");
    }

    // ---------------------------------------------------------------- 用例面

    @Test
    @DisplayName("ELIGIBLE 路径：干净案例 + 健康配对 → ELIGIBLE_FOR_CANARY 落档，溯源面全带")
    void eligiblePathRecordsFullProvenance() {
        EvaluationRecordV1 record = runner.gate(
                request(List.of(cleanCase(), cleanCase()), healthyPairs()));

        assertThat(records.inserted).hasSize(1);
        assertThat(record.outcome()).isEqualTo(EvaluationRecordV1.Outcome.ELIGIBLE_FOR_CANARY);
        assertThat(record.gateReasons()).isEmpty();
        assertThat(record.safetyVerdict()).isEqualTo("PASS");
        assertThat(record.authenticity()).isEqualTo(
                EvaluationRecordV1.AuthenticityLabel.PUBLIC_REPLAY);
        assertThat(record.thresholdsVersion()).isEqualTo("gate-thresholds-v1");
        assertThat(record.datasetDigest()).isEqualTo("dataset-digest-aa");
        assertThat(record.configDigest()).isEqualTo("config-digest-bb");
        assertThat(record.engineDigest()).isEqualTo("engine-digest-cc");
        // 统计溯源面（INV-AM5-3：seed/算法版本/重采样次数/CI 方法全记录）
        EvaluationRecordV1.StatsSnapshot stats = record.stats();
        assertThat(stats).isNotNull();
        assertThat(stats.statsSeed()).isEqualTo(42L);
        assertThat(stats.algorithmVersion()).isEqualTo(PairedTrialStats.ALGORITHM_VERSION);
        assertThat(stats.resamples()).isEqualTo(PairedTrialStats.DEFAULT_RESAMPLES);
        assertThat(stats.ciMethod()).isEqualTo(PairedTrialStats.CI_METHOD);
        assertThat(stats.clusterCount()).isEqualTo(8);
        assertThat(stats.verdict()).isEqualTo("CONCLUSIVE");
        // 六维聚合面：两案 latency 取 max=100、token 求和=300（运行门口径）
        assertThat(record.sixDim().cost().rawCounts().latencyMs()).isEqualTo(100L);
        assertThat(record.sixDim().cost().rawCounts().totalTokens()).isEqualTo(300L);
        assertThat(record.createdAt()).isNotNull();
    }

    @Test
    @DisplayName("REJECT 路径：任一案幻觉工具 → REJECT 落档且违规面可回溯")
    void rejectPathCarriesViolations() {
        EvaluationRecordV1 record = runner.gate(
                request(List.of(cleanCase(), hallucinatedCase()), healthyPairs()));

        assertThat(record.outcome()).isEqualTo(EvaluationRecordV1.Outcome.REJECT);
        assertThat(record.gateReasons()).containsExactly(QualityGate.REASON_SAFETY_VIOLATIONS);
        assertThat(record.safetyVerdict()).isEqualTo("REJECT");
        assertThat(record.safetyViolations()).hasSize(1);
        assertThat(record.safetyViolations().get(0).face())
                .isEqualTo(SafetyFace.UNAUTHORIZED_TOOL);
        assertThat(record.safetyViolations().get(0).ref()).isEqualTo("tool_call:1");
        assertThat(record.safetyViolations().get(0).reason()).isEqualTo("UNKNOWN_TOOL");
    }

    @Test
    @DisplayName("INCONCLUSIVE 路径：零配对试验 → INCONCLUSIVE 落档且 stats 置 null（不伪造统计面）")
    void inconclusivePathWithoutPairs() {
        EvaluationRecordV1 record = runner.gate(request(List.of(cleanCase()), List.of()));

        assertThat(record.outcome()).isEqualTo(EvaluationRecordV1.Outcome.INCONCLUSIVE);
        assertThat(record.gateReasons())
                .containsExactly(QualityGate.REASON_INSUFFICIENT_CLUSTERS);
        assertThat(record.stats()).isNull();
        assertThat(records.inserted).hasSize(1);
    }

    @Test
    @DisplayName("运行门聚合口径贯通：两案 latency 70s×? 任一案超阈值 → RUN_GATE 拒绝")
    void runGateAggregateLatencyAcrossCases() {
        EvalCaseInput slow = new EvalCaseInput(golden(),
                new EvidencePackageV2(2, "summary", EXPECTED, List.of(),
                        List.of("evidence-1"), "impact", "remediation", List.of()),
                List.of(new EvalCaseInput.ToolCallObservation("logs", true,
                        ToolCallStatus.SUCCESS, "d1")),
                List.of(), 70_000L, new EvalCaseInput.Usage(100L, 50L, 150L, false), false);
        EvaluationRecordV1 record = runner.gate(
                request(List.of(cleanCase(), slow), healthyPairs()));

        assertThat(record.outcome()).isEqualTo(EvaluationRecordV1.Outcome.REJECT);
        assertThat(record.gateReasons())
                .containsExactly(QualityGate.REASON_RUN_LATENCY_EXCEEDED);
    }
}
