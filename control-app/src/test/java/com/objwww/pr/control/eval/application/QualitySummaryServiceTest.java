package com.objwww.pr.control.eval.application;

import com.objwww.pr.control.alert.domain.model.TypedRootCause;
import com.objwww.pr.control.eval.domain.EvalCaseResult;
import com.objwww.pr.control.eval.domain.EvalRun;
import com.objwww.pr.control.eval.domain.EvalRunMetadata;
import com.objwww.pr.control.eval.domain.ScenarioMetrics;
import com.objwww.pr.control.eval.domain.repository.EvalRunRepository;
import com.objwww.pr.shared.Digest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * OP-02 质量口径单测（FO06~08）：显式分母与诚实 null、错误确诊绝对数保留、
 * 合理未决与"应能定位却未决"分开、流程成功≠根因正确、排除样本单列。
 */
class QualitySummaryServiceTest {

    private static final TypedRootCause GT_DB =
            new TypedRootCause("db.pool", "exhaustion", "POOL_EXHAUSTED");
    private static final TypedRootCause GT_UNRESOLVED =
            new TypedRootCause("unknown", "unresolved", "INSUFFICIENT_EVIDENCE");
    private static final TypedRootCause ACTUAL_DB =
            new TypedRootCause("db.pool", "exhaustion", "POOL_EXHAUSTED");
    private static final TypedRootCause ACTUAL_WRONG =
            new TypedRootCause("net.dns", "failure", "DNS_TIMEOUT");

    private final FakeRuns runs = new FakeRuns();
    private final QualitySummaryService service = new QualitySummaryService(runs);

    // ------------------------------------------------------------------ 夹具

    private void runOf(EvalRun.EvalRunState state) {
        runs.run = EvalRun.terminal(UUID.randomUUID(), metadata(), state,
                Instant.parse("2026-09-13T00:00:00Z"),
                Instant.parse("2026-09-13T01:00:00Z"),
                ScenarioMetrics.of(List.of()),
                new EvalRun.SymptomCounts(0, 0, 0), null);
    }

    private static EvalRunMetadata metadata() {
        return new EvalRunMetadata(1, "rca-regression:v1",
                Digest.sha256Of("registry"), 1, "glm-4.7", "am3-rca-v2",
                Digest.sha256Of("prompt"), Digest.sha256Of("tools"),
                null, null, null, null, null, "fp", Digest.sha256Of("rules"),
                "scenario-driver-v1", "grader-test-v1");
    }

    private void caseRow(ScenarioMetrics.ScoringVerdict verdict, boolean hit,
            TypedRootCause expected, TypedRootCause actual) {
        boolean hasReport = verdict == ScenarioMetrics.ScoringVerdict.DECIDABLE
                || verdict == ScenarioMetrics.ScoringVerdict.UNRESOLVED;
        runs.cases.add(new EvalCaseResult(UUID.randomUUID(), runs.run.id(),
                "s-" + runs.cases.size(), 1, "final-validated-report-v1",
                UUID.randomUUID(), UUID.randomUUID(),
                hasReport ? UUID.randomUUID() : null,
                verdict, hit, expected, actual, List.of(), List.of(),
                0, 0, 0, 100L, false, null));
    }

    // ------------------------------------------------------------------ FO06 显式分母

    @Test
    @DisplayName("FO06 根因正确率分母=DECIDABLE：排除样本（结构拒绝/超时缺席）不进分母单列")
    void rootCauseRateOverDecidableOnly() {
        runOf(EvalRun.EvalRunState.SUCCEEDED);
        caseRow(ScenarioMetrics.ScoringVerdict.DECIDABLE, true, GT_DB, ACTUAL_DB);
        caseRow(ScenarioMetrics.ScoringVerdict.DECIDABLE, false, GT_DB, ACTUAL_WRONG);
        caseRow(ScenarioMetrics.ScoringVerdict.STRUCTURE_REJECTED, false, GT_DB, null);
        caseRow(ScenarioMetrics.ScoringVerdict.TIMEOUT_OR_ABSENT, false, GT_DB, null);

        QualitySummaryService.QualitySummaryResponse out =
                service.qualityOf(runs.run.id()).orElseThrow();

        assertThat(out.totalCases()).isEqualTo(4);
        assertThat(out.decidable()).isEqualTo(2);
        assertThat(out.rootCauseCorrect()).isEqualTo(1);
        assertThat(out.rootCauseCorrectRate()).isEqualTo(0.5);
        assertThat(out.structureRejected()).isEqualTo(1);
        assertThat(out.timeoutOrAbsent()).isEqualTo(1);
    }

    @Test
    @DisplayName("FO06 零分母 → 比率诚实 null 不回填 0")
    void zeroDenominatorIsHonestNull() {
        runOf(EvalRun.EvalRunState.SUCCEEDED);
        caseRow(ScenarioMetrics.ScoringVerdict.STRUCTURE_REJECTED, false, GT_DB, null);

        QualitySummaryService.QualitySummaryResponse out =
                service.qualityOf(runs.run.id()).orElseThrow();

        assertThat(out.decidable()).isZero();
        assertThat(out.rootCauseCorrectRate()).isNull();
        assertThat(out.wrongDiagnosisRate()).isNull();
        assertThat(out.reasonableUnresolvedRate()).isNull();
    }

    @Test
    @DisplayName("FO06 run 不存在 → empty（404 语义）")
    void unknownRunEmpty() {
        assertThat(service.qualityOf(UUID.randomUUID())).isEmpty();
    }

    // ------------------------------------------------------------------ FO07 未决口径分离

    @Test
    @DisplayName("FO07 合理未决率=期望 unresolved 且保持未决 / 期望 unresolved 总数")
    void reasonableUnresolvedRate() {
        runOf(EvalRun.EvalRunState.SUCCEEDED);
        caseRow(ScenarioMetrics.ScoringVerdict.UNRESOLVED, false,
                GT_UNRESOLVED, null);
        caseRow(ScenarioMetrics.ScoringVerdict.UNRESOLVED, false,
                GT_UNRESOLVED, null);
        caseRow(ScenarioMetrics.ScoringVerdict.DECIDABLE, false,
                GT_UNRESOLVED, ACTUAL_WRONG);

        QualitySummaryService.QualitySummaryResponse out =
                service.qualityOf(runs.run.id()).orElseThrow();

        assertThat(out.reasonableUnresolvedDenominator()).isEqualTo(3);
        assertThat(out.reasonableUnresolved()).isEqualTo(2);
        assertThat(out.reasonableUnresolvedRate()).isEqualTo(2.0 / 3.0);
    }

    @Test
    @DisplayName("FO07 应能定位却未决（期望可判定但输出 UNRESOLVED）单列，不混入合理未决")
    void shouldHaveDecidedSeparated() {
        runOf(EvalRun.EvalRunState.SUCCEEDED);
        caseRow(ScenarioMetrics.ScoringVerdict.UNRESOLVED, false, GT_DB, null);
        caseRow(ScenarioMetrics.ScoringVerdict.UNRESOLVED, false,
                GT_UNRESOLVED, null);

        QualitySummaryService.QualitySummaryResponse out =
                service.qualityOf(runs.run.id()).orElseThrow();

        assertThat(out.shouldHaveDecidedButUnresolved()).isEqualTo(1);
        assertThat(out.reasonableUnresolvedDenominator()).isEqualTo(1);
        assertThat(out.reasonableUnresolved()).isEqualTo(1);
    }

    // ------------------------------------------------------------------ FO08 流程≠质量

    @Test
    @DisplayName("FO08 错误确诊绝对数保留；Run SUCCEEDED 不等于根因正确（标记恒真声明）")
    void wrongDiagnosisAbsoluteAndSucceededNotCorrect() {
        runOf(EvalRun.EvalRunState.SUCCEEDED);
        caseRow(ScenarioMetrics.ScoringVerdict.DECIDABLE, false, GT_DB, ACTUAL_WRONG);
        caseRow(ScenarioMetrics.ScoringVerdict.DECIDABLE, false, GT_DB, ACTUAL_WRONG);
        caseRow(ScenarioMetrics.ScoringVerdict.DECIDABLE, true, GT_DB, ACTUAL_DB);

        QualitySummaryService.QualitySummaryResponse out =
                service.qualityOf(runs.run.id()).orElseThrow();

        assertThat(out.runState()).isEqualTo("SUCCEEDED");
        assertThat(out.wrongDiagnosis()).isEqualTo(2);
        assertThat(out.wrongDiagnosisRate()).isEqualTo(2.0 / 3.0);
        assertThat(out.runSucceededIsNotRootCauseCorrect()).isTrue();
        assertThat(out.denominatorsNote()).contains("DECIDABLE");
    }

    private static final class FakeRuns implements EvalRunRepository {
        EvalRun run;
        final List<EvalCaseResult> cases = new ArrayList<>();

        @Override
        public void insertRunning(EvalRun running) {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean finalizeOnce(EvalRun terminal) {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean applyLaunchIdentity(UUID runId, String displayName, String mode,
                String launchPlanJson) {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean updateRecoveryState(UUID runId, String recoveryState) {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean insertCaseResult(EvalCaseResult result) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Optional<EvalRun> findById(UUID id) {
            return run != null && run.id().equals(id) ? Optional.of(run) : Optional.empty();
        }

        @Override
        public List<EvalCaseResult> findCasesByRunId(UUID id) {
            return run != null && run.id().equals(id) ? List.copyOf(cases) : List.of();
        }
    }
}
