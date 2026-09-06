package com.objwww.pr.control.eval.application;

import com.objwww.pr.control.alert.domain.claim.ClaimStatus;
import com.objwww.pr.control.alert.domain.model.EvidencePackageV2;
import com.objwww.pr.control.alert.domain.model.RcaReport;
import com.objwww.pr.control.alert.domain.model.RcaRun;
import com.objwww.pr.control.alert.domain.model.ReportClaim;
import com.objwww.pr.control.alert.domain.model.RunTrigger;
import com.objwww.pr.control.alert.domain.model.TypedRootCause;
import com.objwww.pr.control.alert.domain.model.ValidationStatus;
import com.objwww.pr.control.alert.domain.repository.RcaReportRepository;
import com.objwww.pr.control.alert.domain.repository.RcaRunRepository;
import com.objwww.pr.control.alert.domain.repository.RcaToolCallRepository;
import com.objwww.pr.control.alert.domain.repository.InvestigationResultRepository;
import com.objwww.pr.control.alert.support.AlertInMemoryStores;
import com.objwww.pr.control.eval.domain.EvalCaseResult;
import com.objwww.pr.control.eval.domain.GoldenCase;
import com.objwww.pr.control.eval.domain.ScenarioMetrics.ScoringVerdict;
import com.objwww.pr.control.eval.domain.SynonymLexicon;
import com.objwww.pr.shared.Digest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * M3-16 单案例评分编排：verdict 分派冻结（无已验证报告时 REJECTED→结构失败、
 * 否则缺席）、选择规则绑定（多 attempt 只评最新）、评分输入只取选定报告。
 */
class SingleCaseScorerTest {

    private static final TypedRootCause EXPECTED =
            new TypedRootCause("payment", "BUSINESS_ERROR_RATE", "PAYMENT_CHARGE_FAILURE");

    private final AlertInMemoryStores.Runs runs = new AlertInMemoryStores.Runs();
    private final AlertInMemoryStores.Reports reports = new AlertInMemoryStores.Reports();
    private final AlertInMemoryStores.Investigations investigations =
            new AlertInMemoryStores.Investigations();
    private final AlertInMemoryStores.ToolCalls toolCalls =
            new AlertInMemoryStores.ToolCalls();

    private SingleCaseScorer scorer;
    private GoldenCase golden;

    @BeforeEach
    void setUp() {
        SynonymLexicon lexicon = SynonymLexicon.load("""
                lexicon_version: 1
                components:
                  - code: payment
                    synonyms: [payment-svc]
                fault_types:
                  - code: BUSINESS_ERROR_RATE
                    synonyms: [业务错误率升高]
                reason_codes:
                  - code: PAYMENT_CHARGE_FAILURE
                    fault_type: BUSINESS_ERROR_RATE
                    synonyms: [扣款失败]
                """);
        scorer = new SingleCaseScorer(runs, reports, investigations, toolCalls,
                new ScenarioEvaluator(lexicon));
        golden = new GoldenCase("S1", "paymentFailure=50%", "FlagdScenarioDriver", null,
                "payment", EXPECTED, List.of("checkout"),
                new GoldenCase.Timing(1, 1, 1, 1, 1));
    }

    private UUID seedRun(Instant createdAt) {
        UUID runId = UUID.randomUUID();
        runs.insert(new RcaRun(runId, UUID.randomUUID(), 0, RunTrigger.INITIAL,
                com.objwww.pr.control.alert.domain.model.RcaRunState.SUCCEEDED,
                Digest.sha256Of("it"), createdAt, createdAt.plusSeconds(60),
                createdAt, createdAt.plusSeconds(60), null));
        return runId;
    }

    private UUID seedReport(UUID runId, UUID attemptId, Instant createdAt,
                            ValidationStatus status, String packageJson) {
        RcaReport report = new RcaReport(UUID.randomUUID(), runId, attemptId, 2,
                status, List.of(), packageJson, "raw", "m", 1, 1, 2, false, createdAt);
        reports.insert(report);
        return report.id();
    }

    private static String hitPackage() {
        return "{\"schema_version\":2,\"summary\":\"s\",\"root_cause\":{\"component\":"
                + "\"payment\",\"fault_type\":\"BUSINESS_ERROR_RATE\",\"reason_code\":"
                + "\"PAYMENT_CHARGE_FAILURE\"},\"claims\":[{\"claim_type\":\"root_cause\","
                + "\"status\":\"TRUE\",\"component\":\"payment\",\"fault_type\":"
                + "\"BUSINESS_ERROR_RATE\",\"symptom_codes\":[\"checkout\"],"
                + "\"evidence_refs\":[\"r\"]}],\"evidence\":[\"e\"],\"impact\":\"i\","
                + "\"remediation\":\"r\",\"references\":[]}";
    }

    private static String missPackage() {
        return hitPackage().replace("PAYMENT_CHARGE_FAILURE", "SOMETHING_ELSE");
    }

    private UUID seedInvestigationWithToolCalls(UUID attemptId, UUID runId) {
        UUID resultId = UUID.randomUUID();
        investigations.insertStartedIfAbsent(
                com.objwww.pr.control.alert.domain.model.InvestigationResult.started(
                        resultId, attemptId, runId, 0, 2, "m", Instant.now()));
        toolCalls.insertAll(List.of(new com.objwww.pr.control.alert.domain.model.RcaToolCall(
                resultId, "tc-1", 1, "prometheus_query", null, null, null,
                null, null, runId, 0, 2, Digest.sha256Of("p"))));
        return resultId;
    }

    @Test
    @DisplayName("DECIDABLE 命中：选择报告评分，TP=1 且 silence 由 tool_calls 存在性豁免")
    void decodableHitBindsSelectedReport() {
        Instant base = Instant.parse("2026-01-01T00:00:00Z");
        UUID runId = seedRun(base);
        UUID attemptId = UUID.randomUUID();
        seedInvestigationWithToolCalls(attemptId, runId);
        seedReport(runId, attemptId, base.plusSeconds(30),
                ValidationStatus.STRUCTURE_VALIDATED, hitPackage());

        Optional<EvalCaseResult> scored = scorer.score(UUID.randomUUID(), golden, 1, runId);

        assertThat(scored).isPresent();
        EvalCaseResult result = scored.get();
        assertThat(result.verdict()).isEqualTo(ScoringVerdict.DECIDABLE);
        assertThat(result.rootCauseHit()).isTrue();
        assertThat(result.tpCount()).isEqualTo(1);
        assertThat(result.fpCount()).isZero();
        assertThat(result.fnCount()).isZero();
        assertThat(result.silencePenalty()).isFalse();
        assertThat(result.latencyMs()).isEqualTo(30_000L);
        assertThat(result.selectionPolicyVersion()).isEqualTo("final-validated-report-v1");
        assertThat(result.actualRootCause()).isEqualTo(EXPECTED);
        assertThat(result.failureSampleJson()).isNull();
    }

    @Test
    @DisplayName("多 attempt 禁挑最优：晚到报告 miss 时按晚到者记 miss，不回挑早到命中者")
    void multiAttemptScoresLatestNotBest() {
        Instant base = Instant.parse("2026-01-01T00:00:00Z");
        UUID runId = seedRun(base);
        seedReport(runId, UUID.randomUUID(), base.plusSeconds(30),
                ValidationStatus.STRUCTURE_VALIDATED, hitPackage());
        seedReport(runId, UUID.randomUUID(), base.plusSeconds(90),
                ValidationStatus.STRUCTURE_VALIDATED, missPackage());

        EvalCaseResult result = scorer.score(UUID.randomUUID(), golden, 1, runId).orElseThrow();
        assertThat(result.verdict()).isEqualTo(ScoringVerdict.DECIDABLE);
        assertThat(result.rootCauseHit()).isFalse();
        assertThat(result.failureSampleJson()).contains("SOMETHING_ELSE");
    }

    @Test
    @DisplayName("无已验证报告：调查记录 REJECTED_* → STRUCTURE_REJECTED（样本带验证状态）；否则缺席")
    void noValidatedReportVerdictDispatch() {
        Instant base = Instant.parse("2026-01-01T00:00:00Z");
        UUID rejectedRun = seedRun(base);
        // 生产落档面（技术方案 §6.4 行 199 冻结判据）：REJECTED_* 只进
        // rca_investigation_result（RcaRunOrchestrator 仅对 STRUCTURE_VALIDATED 写报告行），
        // 本用例不种任何报告行 = 生产真实形态
        UUID attemptId = UUID.randomUUID();
        UUID resultId = UUID.randomUUID();
        investigations.insertStartedIfAbsent(com.objwww.pr.control.alert.domain.model.InvestigationResult
                .started(resultId, attemptId, rejectedRun, 0, 2, "m", base));
        investigations.finishTerminal(new com.objwww.pr.control.alert.domain.model.InvestigationResult(
                resultId, attemptId, rejectedRun, 0, 2,
                com.objwww.pr.control.alert.domain.model.ExecutionStatus.FAILED,
                ValidationStatus.REJECTED_MALFORMED,
                List.of("外层 analysis 缺失", "内嵌 JSON 解析失败"),
                null, null, null, null, "m", null,
                base.plusSeconds(30), base.plusSeconds(30)));

        EvalCaseResult rejected = scorer.score(UUID.randomUUID(), golden, 1, rejectedRun)
                .orElseThrow();
        assertThat(rejected.verdict()).isEqualTo(ScoringVerdict.STRUCTURE_REJECTED);
        assertThat(rejected.scoredReportId()).isNull();
        assertThat(rejected.failureSampleJson()).contains("REJECTED_MALFORMED");
        assertThat(rejected.failureSampleJson()).contains("内嵌 JSON 解析失败");

        UUID absentRunId = seedRun(base);
        EvalCaseResult absent = scorer.score(UUID.randomUUID(), golden, 1, absentRunId)
                .orElseThrow();
        assertThat(absent.verdict()).isEqualTo(ScoringVerdict.TIMEOUT_OR_ABSENT);
    }

    @Test
    @DisplayName("悬挂 STARTED（NOT_VALIDATED）不算结构失败：无报告 → TIMEOUT_OR_ABSENT")
    void hangingStartIsAbsentNotStructureRejected() {
        Instant base = Instant.parse("2026-01-01T00:00:00Z");
        UUID runId = seedRun(base);
        investigations.insertStartedIfAbsent(com.objwww.pr.control.alert.domain.model.InvestigationResult
                .started(UUID.randomUUID(), UUID.randomUUID(), runId, 0, 2, "m", base));

        EvalCaseResult result = scorer.score(UUID.randomUUID(), golden, 1, runId).orElseThrow();
        assertThat(result.verdict()).isEqualTo(ScoringVerdict.TIMEOUT_OR_ABSENT);
        assertThat(result.failureSampleJson()).contains("no_validated_report");
    }

    @Test
    @DisplayName("UNRESOLVED 与静默罚：哨兵报告 → UNRESOLVED；无 tool_calls + claims → silence")
    void unresolvedAndSilencePenalty() {
        Instant base = Instant.parse("2026-01-01T00:00:00Z");
        UUID runId = seedRun(base);
        String unresolved = hitPackage().replace("\"component\":\"payment\"",
                "\"component\":\"UNRESOLVED\"");
        seedReport(runId, UUID.randomUUID(), base.plusSeconds(30),
                ValidationStatus.STRUCTURE_VALIDATED, unresolved);

        EvalCaseResult result = scorer.score(UUID.randomUUID(), golden, 1, runId).orElseThrow();
        assertThat(result.verdict()).isEqualTo(ScoringVerdict.UNRESOLVED);
        assertThat(result.silencePenalty()).isTrue();
    }

    @Test
    @DisplayName("run 缺席（resolver 未找到）：TIMEOUT_OR_ABSENT 且不抛")
    void absentRunRecorded() {
        EvalCaseResult result = scorer.score(UUID.randomUUID(), golden, 1, UUID.randomUUID())
                .orElseThrow();
        assertThat(result.verdict()).isEqualTo(ScoringVerdict.TIMEOUT_OR_ABSENT);
        assertThat(result.failureSampleJson()).contains("run_absent");
    }
}
