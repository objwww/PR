package com.objwww.pr.control.eval.application;

import com.objwww.pr.control.eval.domain.EvalCaseResult;
import com.objwww.pr.control.eval.domain.EvalRun;
import com.objwww.pr.control.eval.domain.ScenarioMetrics;
import com.objwww.pr.control.eval.domain.repository.EvalRunRepository;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * 评测质量口径汇总（OP-02，方案 §3.2）：显式分母——每个比率都带分子/分母与
 * 排除数，分母为 0 时比率为诚实 null（不回填 0 也不除 0）。
 *
 * <ul>
 *   <li>根因正确率 = DECIDABLE 且 rootCauseHit / 全部 DECIDABLE（流程成功 ≠
 *       质量正确：Run SUCCEEDED 只表示跑完，wrongDiagnosis 单列绝对数，
 *       不能靠大量未决隐藏错误）；</li>
 *   <li>合理未决率 = 期望本身标"unresolved"（GT fault_type 约定标记）且实际
 *       保持 UNRESOLVED / 期望 unresolved 总数——与"应能定位却未决"
 *       （期望可判定但输出 UNRESOLVED）分开计数；</li>
 *   <li>排除面单列：STRUCTURE_REJECTED / TIMEOUT_OR_ABSENT（删失样本，不按 0 算）。</li>
 * </ul>
 */
public class QualitySummaryService {

    /** GT 侧"合理未决"标记（NativeReportAdapter UNRESOLVED_FAULT_TYPE 同约定） */
    public static final String UNRESOLVED_FAULT_TYPE = "unresolved";

    private final EvalRunRepository runs;

    public QualitySummaryService(EvalRunRepository runs) {
        this.runs = Objects.requireNonNull(runs, "runs");
    }

    /** 质量汇总应答（record 字段名即 JSON 契约；rate 字段分母 0 → null） */
    public record QualitySummaryResponse(UUID evalRunId, String runState,
            long totalCases, long decidable,
            long rootCauseCorrect, Double rootCauseCorrectRate,
            long wrongDiagnosis, Double wrongDiagnosisRate,
            long reasonableUnresolvedDenominator, long reasonableUnresolved,
            Double reasonableUnresolvedRate,
            long shouldHaveDecidedButUnresolved,
            long structureRejected, long timeoutOrAbsent,
            boolean runSucceededIsNotRootCauseCorrect,
            String denominatorsNote) {
    }

    /** run 不存在 → empty（HTTP 层如实 404） */
    public Optional<QualitySummaryResponse> qualityOf(UUID evalRunId) {
        Objects.requireNonNull(evalRunId, "evalRunId");
        Optional<EvalRun> run = runs.findById(evalRunId);
        if (run.isEmpty()) {
            return Optional.empty();
        }
        List<EvalCaseResult> cases = runs.findCasesByRunId(evalRunId);
        long decidable = cases.stream()
                .filter(c -> c.verdict() == ScenarioMetrics.ScoringVerdict.DECIDABLE)
                .count();
        long correct = cases.stream()
                .filter(c -> c.verdict() == ScenarioMetrics.ScoringVerdict.DECIDABLE
                        && c.rootCauseHit())
                .count();
        long wrongDiagnosis = decidable - correct;
        long expectedUnresolved = cases.stream()
                .filter(c -> UNRESOLVED_FAULT_TYPE
                        .equals(c.expectedRootCause().faultType()))
                .count();
        long stayedUnresolved = cases.stream()
                .filter(c -> UNRESOLVED_FAULT_TYPE
                        .equals(c.expectedRootCause().faultType())
                        && c.verdict() == ScenarioMetrics.ScoringVerdict.UNRESOLVED)
                .count();
        long shouldHaveDecided = cases.stream()
                .filter(c -> !UNRESOLVED_FAULT_TYPE
                        .equals(c.expectedRootCause().faultType())
                        && c.verdict() == ScenarioMetrics.ScoringVerdict.UNRESOLVED)
                .count();
        long structureRejected = cases.stream()
                .filter(c -> c.verdict()
                        == ScenarioMetrics.ScoringVerdict.STRUCTURE_REJECTED)
                .count();
        long timeoutOrAbsent = cases.stream()
                .filter(c -> c.verdict()
                        == ScenarioMetrics.ScoringVerdict.TIMEOUT_OR_ABSENT)
                .count();
        return Optional.of(new QualitySummaryResponse(
                evalRunId, run.get().state().name(),
                cases.size(), decidable,
                correct, rate(correct, decidable),
                wrongDiagnosis, rate(wrongDiagnosis, decidable),
                expectedUnresolved, stayedUnresolved,
                rate(stayedUnresolved, expectedUnresolved),
                shouldHaveDecided, structureRejected, timeoutOrAbsent,
                true,
                "rootCauseCorrectRate=DECIDABLE 命中/DECIDABLE；"
                        + "wrongDiagnosisRate 同分母（绝对数单列）；"
                        + "reasonableUnresolvedRate=期望 unresolved 且保持 UNRESOLVED/"
                        + "期望 unresolved 总数；应能定位却未决与排除样本单列不进分母"));
    }

    /** 分母 0 → null（诚实空，不回填 0） */
    private static Double rate(long numerator, long denominator) {
        return denominator == 0 ? null : (double) numerator / denominator;
    }
}
