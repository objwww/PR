package com.objwww.pr.control.eval.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.objwww.pr.control.alert.domain.model.EvidencePackageV2;
import com.objwww.pr.control.alert.domain.model.InvestigationResult;
import com.objwww.pr.control.alert.domain.model.RcaReport;
import com.objwww.pr.control.alert.domain.model.RcaRun;
import com.objwww.pr.control.alert.domain.model.ValidationStatus;
import com.objwww.pr.control.alert.domain.repository.InvestigationResultRepository;
import com.objwww.pr.control.alert.domain.repository.RcaReportRepository;
import com.objwww.pr.control.alert.domain.repository.RcaRunRepository;
import com.objwww.pr.control.alert.domain.repository.RcaToolCallRepository;
import com.objwww.pr.control.alert.domain.model.TypedRootCause;
import com.objwww.pr.control.eval.domain.EvalCaseResult;
import com.objwww.pr.control.eval.domain.GoldenCase;
import com.objwww.pr.control.eval.domain.ScenarioMetrics.ScoringVerdict;

import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * 单案例评分（M3-16）：选择规则（{@link FinalReportSelector}）→ 纯函数评分
 * （{@link ScenarioEvaluator}）→ EvalCaseResult（由调用方持久化，V10 insert-only）。
 *
 * <p>verdict 分派冻结（无已验证报告时）：
 * <ul>
 *   <li>Run 内存在 REJECTED_* 报告行 → STRUCTURE_REJECTED（结构失败，计 0 入总分母）；</li>
 *   <li>否则（run 未终态/轮询超时/执行失败无产物）→ TIMEOUT_OR_ABSENT（缺席单独标注）。</li>
 * </ul>
 * 评分输入只取选定报告——多 attempt 并存时其余报告一律不进评分（禁挑最优）。
 */
public class SingleCaseScorer {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final RcaRunRepository runs;
    private final RcaReportRepository reports;
    private final InvestigationResultRepository investigations;
    private final RcaToolCallRepository toolCalls;
    private final ScenarioEvaluator evaluator;
    private final FinalReportSelector selector = new FinalReportSelector();

    public SingleCaseScorer(RcaRunRepository runs,
                            RcaReportRepository reports,
                            InvestigationResultRepository investigations,
                            RcaToolCallRepository toolCalls,
                            ScenarioEvaluator evaluator) {
        this.runs = Objects.requireNonNull(runs);
        this.reports = Objects.requireNonNull(reports);
        this.investigations = Objects.requireNonNull(investigations);
        this.toolCalls = Objects.requireNonNull(toolCalls);
        this.evaluator = Objects.requireNonNull(evaluator);
    }

    public Optional<EvalCaseResult> score(UUID evalRunId, GoldenCase golden,
                                          int roundNo, UUID rcaRunId) {
        Optional<RcaRun> run = runs.findById(rcaRunId);
        if (run.isEmpty()) {
            return Optional.of(absent(evalRunId, golden, roundNo, rcaRunId));
        }
        List<RcaReport> runReports = reports.findByRunId(rcaRunId);
        Optional<RcaReport> selected = selector.select(runReports);

        if (selected.isEmpty()) {
            boolean structureRejected = runReports.stream().anyMatch(r ->
                    r.validationStatus() != ValidationStatus.STRUCTURE_VALIDATED);
            ScoringVerdict verdict = structureRejected
                    ? ScoringVerdict.STRUCTURE_REJECTED
                    : ScoringVerdict.TIMEOUT_OR_ABSENT;
            return Optional.of(new EvalCaseResult(UUID.randomUUID(), evalRunId,
                    golden.scenarioId(), roundNo, FinalReportSelector.SELECTION_POLICY_VERSION,
                    rcaRunId, null, null, verdict, false,
                    golden.expectedRootCause(), null,
                    golden.expectedSymptomCodes(), List.of(), 0, 0,
                    golden.expectedSymptomCodes().size(), null, false,
                    failureSample(structureRejected, runReports)));
        }

        RcaReport report = selected.get();
        EvidencePackageV2 pkg = parse(report.packageJson());
        if (pkg == null) {
            // 选定报告结构异形（防御面：validator 放行后被评分侧复检拒绝）
            return Optional.of(new EvalCaseResult(UUID.randomUUID(), evalRunId,
                    golden.scenarioId(), roundNo, FinalReportSelector.SELECTION_POLICY_VERSION,
                    rcaRunId, report.attemptId(), report.id(),
                    ScoringVerdict.STRUCTURE_REJECTED, false,
                    golden.expectedRootCause(), null,
                    golden.expectedSymptomCodes(), List.of(), 0, 0,
                    golden.expectedSymptomCodes().size(), null, false,
                    "{\"reason\":\"package_reparse_failed\"}"));
        }

        boolean toolCallsPresent = hasToolCalls(report.attemptId());
        ScenarioEvaluator.Evaluation ev = evaluator.evaluate(golden, pkg, toolCallsPresent);
        long latencyMs = Duration.between(run.get().createdAt(), report.createdAt())
                .toMillis();
        boolean hitMissed = ev.verdict() == ScoringVerdict.DECIDABLE && !ev.rootCauseHit();
        return Optional.of(new EvalCaseResult(UUID.randomUUID(), evalRunId,
                golden.scenarioId(), roundNo, FinalReportSelector.SELECTION_POLICY_VERSION,
                rcaRunId, report.attemptId(), report.id(),
                ev.verdict(), ev.rootCauseHit(),
                golden.expectedRootCause(), pkg.rootCause(),
                golden.expectedSymptomCodes(), ev.actualSymptomCodes(),
                ev.truePositives(), ev.falsePositives(), ev.falseNegatives(),
                latencyMs, ev.silencePenalty(),
                failureSample(hitMissed
                                || ev.verdict() == ScoringVerdict.UNRESOLVED,
                        ev.actualSymptomCodes(), pkg.rootCause())));
    }

    // ------------------------------------------------------------------ 内部

    private EvalCaseResult absent(UUID evalRunId, GoldenCase golden, int roundNo, UUID rcaRunId) {
        return new EvalCaseResult(UUID.randomUUID(), evalRunId, golden.scenarioId(),
                roundNo, FinalReportSelector.SELECTION_POLICY_VERSION,
                rcaRunId, null, null, ScoringVerdict.TIMEOUT_OR_ABSENT, false,
                golden.expectedRootCause(), null,
                golden.expectedSymptomCodes(), List.of(), 0, 0,
                golden.expectedSymptomCodes().size(), null, false,
                "{\"reason\":\"run_absent\"}");
    }

    private EvidencePackageV2 parse(String packageJson) {
        try {
            JsonNode node = JSON.readTree(packageJson);
            return EvidencePackageV2.fromJson(node);
        } catch (Exception e) {
            return null;
        }
    }

    /** silence_penalty 数据源（§6.4）：评分 attempt 的 tool_calls 是否非空（attempt 粒度） */
    private boolean hasToolCalls(UUID attemptId) {
        Optional<InvestigationResult> result = investigations.findByAttemptId(attemptId);
        return result.isPresent()
                && !toolCalls.findByResultId(result.get().id()).isEmpty();
    }

    private String failureSample(boolean anyRejected, List<RcaReport> reports) {
        if (!anyRejected) {
            return "{\"reason\":\"no_validated_report\"}";
        }
        return reports.stream()
                .filter(r -> r.validationStatus() != ValidationStatus.STRUCTURE_VALIDATED)
                .findFirst()
                .map(r -> {
                    try {
                        return JSON.writeValueAsString(java.util.Map.of(
                                "reason", "structure_rejected",
                                "validationStatus", r.validationStatus().name(),
                                "validationErrors", r.validationErrors()));
                    } catch (Exception e) {
                        return "{\"reason\":\"structure_rejected\"}";
                    }
                })
                .orElse("{\"reason\":\"structure_rejected\"}");
    }

    /** 失败样本原文（M-04：原值原样保留供人工复核；M3-18 报告引用） */
    private String failureSample(boolean include, List<String> actualSymptoms,
                                 TypedRootCause actualCause) {
        if (!include) {
            return null;
        }
        try {
            return JSON.writeValueAsString(java.util.Map.of(
                    "reason", "root_cause_miss_or_unresolved",
                    "actualRootCause", actualCause == null ? "" : java.util.Map.of(
                            "component", actualCause.component(),
                            "fault_type", actualCause.faultType(),
                            "reason_code", actualCause.reasonCode()),
                    "actualSymptomCodes", actualSymptoms));
        } catch (Exception e) {
            return "{\"reason\":\"sample_serialize_failed\"}";
        }
    }
}
