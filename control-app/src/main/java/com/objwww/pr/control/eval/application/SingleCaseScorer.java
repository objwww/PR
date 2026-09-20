package com.objwww.pr.control.eval.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.objwww.pr.control.alert.domain.model.EvidencePackageV2;
import com.objwww.pr.control.alert.domain.model.InvestigationResult;
import com.objwww.pr.control.alert.domain.model.RcaReport;
import com.objwww.pr.control.alert.domain.model.RcaRun;
import com.objwww.pr.control.alert.domain.model.RcaToolCall;
import com.objwww.pr.control.alert.domain.model.ValidationStatus;
import com.objwww.pr.control.alert.domain.repository.InvestigationResultRepository;
import com.objwww.pr.control.alert.domain.repository.RcaReportRepository;
import com.objwww.pr.control.alert.domain.repository.RcaRunRepository;
import com.objwww.pr.control.alert.domain.repository.RcaToolCallRepository;
import com.objwww.pr.control.alert.domain.model.TypedRootCause;
import com.objwww.pr.control.alert.domain.evidence.EvidenceEnvelope;
import com.objwww.pr.control.alert.domain.evidence.EvidenceRepository;
import com.objwww.pr.control.eval.domain.EvalCaseResult;
import com.objwww.pr.control.eval.domain.GoldenCase;
import com.objwww.pr.control.eval.domain.ScenarioMetrics.ScoringVerdict;
import com.objwww.pr.control.eval.domain.model.EvalCaseInput;
import com.objwww.pr.control.eval.domain.repository.EvalCaseSafetySink;
import com.objwww.pr.control.eval.domain.service.SafetyGate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;

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
 *   <li>Run 内存在 REJECTED_* 调查记录（investigation_result）→ STRUCTURE_REJECTED
 *       （结构失败，计 0 入总分母，技术方案 §6.4 冻结）；</li>
 *   <li>否则（run 未终态/轮询超时/执行失败无产物）→ TIMEOUT_OR_ABSENT（缺席单独标注）。</li>
 * </ul>
 * 评分输入只取选定报告——多 attempt 并存时其余报告一律不进评分（禁挑最优）。
 */
public class SingleCaseScorer {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Logger log = LoggerFactory.getLogger(SingleCaseScorer.class);

    private final RcaRunRepository runs;
    private final RcaReportRepository reports;
    private final InvestigationResultRepository investigations;
    private final RcaToolCallRepository toolCalls;
    private final ScenarioEvaluator evaluator;
    private final SafetyGate safetyGate = new SafetyGate();
    private final EvalCaseSafetySink safetySink;
    private final com.objwww.pr.control.eval.domain.repository.EvalReportJudge judge;
    private final com.objwww.pr.control.eval.domain.repository.EvalCaseJudgeSink judgeSink;
    private final EvidenceRepository evidence;
    private final com.objwww.pr.control.eval.domain.repository.EvalCaseSixPartsSink sixPartsSink;
    private final JdbcClient jdbc;
    private final FinalReportSelector selector = new FinalReportSelector();

    public SingleCaseScorer(RcaRunRepository runs,
                            RcaReportRepository reports,
                            InvestigationResultRepository investigations,
                            RcaToolCallRepository toolCalls,
                            ScenarioEvaluator evaluator) {
        this(runs, reports, investigations, toolCalls, evaluator, null);
    }

    /** P4 全形：安全裁决落库面（null = 不落，旧装配/测试兼容） */
    public SingleCaseScorer(RcaRunRepository runs,
                            RcaReportRepository reports,
                            InvestigationResultRepository investigations,
                            RcaToolCallRepository toolCalls,
                            ScenarioEvaluator evaluator,
                            EvalCaseSafetySink safetySink) {
        this(runs, reports, investigations, toolCalls, evaluator, safetySink, null, null);
    }

    /** P7 全形：LLM-judge 第三判定式（null judge = 未启用，fail-closed 不落行） */
    public SingleCaseScorer(RcaRunRepository runs,
                            RcaReportRepository reports,
                            InvestigationResultRepository investigations,
                            RcaToolCallRepository toolCalls,
                            ScenarioEvaluator evaluator,
                            EvalCaseSafetySink safetySink,
                            com.objwww.pr.control.eval.domain.repository.EvalReportJudge judge,
                            com.objwww.pr.control.eval.domain.repository.EvalCaseJudgeSink judgeSink) {
        this(runs, reports, investigations, toolCalls, evaluator, safetySink, judge,
                judgeSink, null);
    }

    /** 证据回退全形：evidence 非 null 时，NATIVE 链（不产 tool_call 账本行）的过程计数
     *  回退到 rca_evidence 面（每次工具调用一行证据），不再恒 0 冒充无调用 */
    public SingleCaseScorer(RcaRunRepository runs,
                            RcaReportRepository reports,
                            InvestigationResultRepository investigations,
                            RcaToolCallRepository toolCalls,
                            ScenarioEvaluator evaluator,
                            EvalCaseSafetySink safetySink,
                            com.objwww.pr.control.eval.domain.repository.EvalReportJudge judge,
                            com.objwww.pr.control.eval.domain.repository.EvalCaseJudgeSink judgeSink,
                            EvidenceRepository evidence) {
        this(runs, reports, investigations, toolCalls, evaluator, safetySink, judge,
                judgeSink, evidence, null, null);
    }

    /** M-d T5 全形：六要素检出版库面（V152 eval_case_six_parts；null = 不落，
     *  旧装配/测试兼容） */
    public SingleCaseScorer(RcaRunRepository runs,
                            RcaReportRepository reports,
                            InvestigationResultRepository investigations,
                            RcaToolCallRepository toolCalls,
                            ScenarioEvaluator evaluator,
                            EvalCaseSafetySink safetySink,
                            com.objwww.pr.control.eval.domain.repository.EvalReportJudge judge,
                            com.objwww.pr.control.eval.domain.repository.EvalCaseJudgeSink judgeSink,
                            EvidenceRepository evidence,
                            com.objwww.pr.control.eval.domain.repository.EvalCaseSixPartsSink sixPartsSink,
                            JdbcClient jdbc) {
        this.runs = Objects.requireNonNull(runs);
        this.reports = Objects.requireNonNull(reports);
        this.investigations = Objects.requireNonNull(investigations);
        this.toolCalls = Objects.requireNonNull(toolCalls);
        this.evaluator = Objects.requireNonNull(evaluator);
        this.safetySink = safetySink;
        this.judge = judge;
        this.judgeSink = judgeSink;
        this.evidence = evidence;
        this.sixPartsSink = sixPartsSink;
        this.jdbc = jdbc;
    }

    public Optional<EvalCaseResult> score(UUID evalRunId, GoldenCase golden,
                                          int roundNo, UUID rcaRunId) {
        Optional<RcaRun> run = runs.findById(rcaRunId);
        if (run.isEmpty()) {
            // 六要素口径（BA-177 注释冻结）：调查不存在=无报告可检，本案例不进
            // eval_case_six_parts——六要素完整率分母只含"有报告且落档"的案例，
            // 缺席案例由 verdict=TIMEOUT_OR_ABSENT 在症状/根因口径单独记账
            return Optional.of(absent(evalRunId, golden, roundNo, rcaRunId));
        }
        List<RcaReport> runReports = reports.findByRunId(rcaRunId);
        Optional<RcaReport> selected = selector.select(runReports);

        if (selected.isEmpty()) {
            // 结构失败判定面 = 调查记录（技术方案 §6.4 行 199 冻结：REJECTED_* 计 0 入分母、
            // 明确失败非超时；rca_report 只收 STRUCTURE_VALIDATED 行——ORCH-INSERT 面见
            // RcaRunOrchestrator.archiveArtifact，故报告行的 anyMatch 判据永远为假，改查调查记录）
            boolean structureRejected = investigations.findByRunId(rcaRunId).stream().anyMatch(r ->
                    r.validationStatus() != ValidationStatus.STRUCTURE_VALIDATED
                    && r.validationStatus() != ValidationStatus.NOT_VALIDATED);
            ScoringVerdict verdict = structureRejected
                    ? ScoringVerdict.STRUCTURE_REJECTED
                    : ScoringVerdict.TIMEOUT_OR_ABSENT;
            // 六要素口径（BA-177 注释冻结）：无通过校验的报告=六要素不可检，不落
            // eval_case_six_parts、不计入完整率分母（分母口径见 recordSixParts 头注）
            return Optional.of(new EvalCaseResult(UUID.randomUUID(), evalRunId,
                    golden.scenarioId(), roundNo, FinalReportSelector.SELECTION_POLICY_VERSION,
                    rcaRunId, null, null, verdict, false,
                    golden.expectedRootCause(), null,
                    golden.expectedSymptomCodes(), List.of(), 0, 0,
                    golden.expectedSymptomCodes().size(), null, false,
                    failureSample(structureRejected, investigations.findByRunId(rcaRunId)),
                    null, null, null, null, null, null, null, null, null,
                    golden.difficulty()));
        }

        RcaReport report = selected.get();
        EvidencePackageV2 pkg = parse(report.packageJson());
        if (pkg == null) {
            // 选定报告结构异形（防御面：validator 放行后被评分侧复检拒绝）——
            // 六要素口径同上：包解析失败=不可检，不落档不计入分母
            return Optional.of(new EvalCaseResult(UUID.randomUUID(), evalRunId,
                    golden.scenarioId(), roundNo, FinalReportSelector.SELECTION_POLICY_VERSION,
                    rcaRunId, report.attemptId(), report.id(),
                    ScoringVerdict.STRUCTURE_REJECTED, false,
                    golden.expectedRootCause(), null,
                    golden.expectedSymptomCodes(), List.of(), 0, 0,
                    golden.expectedSymptomCodes().size(), null, false,
                    "{\"reason\":\"package_reparse_failed\"}",
                    null, null, null, null, null, null, null, null, null,
                    golden.difficulty()));
        }

        boolean toolCallsPresent = hasToolCalls(report.attemptId());
        ScenarioEvaluator.Evaluation ev = evaluator.evaluate(golden, pkg, toolCallsPresent);
        // P3 过程计数：评分 attempt 的 tool_call 账本（total/unique——重复调用观测；
        // 与 silence_penalty 同源，一次取数）
        List<RcaToolCall> attemptCalls = toolCallsOf(report.attemptId());
        long totalCalls = attemptCalls.size();
        long uniqueCalls = attemptCalls.stream()
                .map(c -> c.toolName() + "|" + (c.paramsDigest() == null
                        ? "" : c.paramsDigest().value()))
                .distinct()
                .count();
        if (totalCalls == 0 && evidence != null) {
            // NATIVE 确定性链不产 tool_call 账本行——每次工具调用以 rca_evidence 落库。
            // 过程计数回退证据面：total=证据行数、unique=证据类型×来源去重，
            // 否则前端工具调用列恒 0 冒充无调用（假面）。silence_penalty 语义不动。
            try {
                List<EvidenceEnvelope> runEvidence = evidence.findByRunId(rcaRunId);
                totalCalls = runEvidence.size();
                uniqueCalls = runEvidence.stream()
                        .map(e -> e.evidenceType() + "|" + e.source())
                        .distinct()
                        .count();
            } catch (Exception e) {
                // 证据面读失败（含 digest 校验拒绝）：维持账本计数 0，不伪造；
                // 但必须留痕——p8 实证 GRANT 缺失时这里静默吞成 0（假面路线）
                log.warn("eval 工具计数证据面回退读失败 run={}：{}", rcaRunId, e.toString());
            }
        }
        if (totalCalls == 0 && jdbc != null) {
            // NATIVE 确定性链写的是 rca_tool_invocation（确定性步骤账本）而非
            // rca_tool_call（LLM 工具调用账本）——回退读取，出真值不恒 0
            try {
                var inv = jdbc.sql("""
                        SELECT count(*) AS total,
                               count(DISTINCT tool_name) AS unique_tools
                          FROM rca_tool_invocation WHERE run_id = :runId
                        """).param("runId", rcaRunId)
                        .query((rs, i) -> new long[]{rs.getLong("total"), rs.getLong("unique_tools")})
                        .single();
                totalCalls = inv[0];
                uniqueCalls = inv[1];
            } catch (Exception e) {
                // 读失败维持 0 如实，但留痕（p8：eval_app 缺 SELECT 授权曾静默归零）
                log.warn("eval 工具计数 invocation 账本回退读失败 run={}：{}", rcaRunId, e.toString());
            }
        }
        // P3 路径维 + 结论复核维（确定性纯函数；无检查点 → total=null 如实未评）
        List<ScenarioEvaluator.CheckpointMatch> checkpoints =
                evaluator.checkpointMatches(golden, pkg);
        Integer checkpointsTotal = checkpoints.isEmpty() ? null : checkpoints.size();
        Integer checkpointsCovered = checkpoints.isEmpty() ? null
                : (int) checkpoints.stream().filter(ScenarioEvaluator.CheckpointMatch::matched)
                        .count();
        String checkpointJson = checkpoints.isEmpty() ? null
                : checkpointMatchesJson(checkpoints);
        long latencyMs = Duration.between(run.get().createdAt(), report.createdAt())
                .toMillis();
        boolean hitMissed = ev.verdict() == ScoringVerdict.DECIDABLE && !ev.rootCauseHit();
        EvalCaseResult result = new EvalCaseResult(UUID.randomUUID(), evalRunId,
                golden.scenarioId(), roundNo, FinalReportSelector.SELECTION_POLICY_VERSION,
                rcaRunId, report.attemptId(), report.id(),
                ev.verdict(), ev.rootCauseHit(),
                golden.expectedRootCause(), pkg.rootCause(),
                golden.expectedSymptomCodes(), ev.actualSymptomCodes(),
                ev.truePositives(), ev.falsePositives(), ev.falseNegatives(),
                latencyMs, ev.silencePenalty(),
                failureSample(hitMissed
                                || ev.verdict() == ScoringVerdict.UNRESOLVED,
                        ev.actualSymptomCodes(), pkg.rootCause()),
                ev.componentHit(), ev.faultHit(), ev.reasonHit(),
                checkpointsTotal, checkpointsCovered, checkpointJson,
                evaluator.conclusionGrounded(pkg),
                (int) totalCalls, (int) uniqueCalls, golden.difficulty());
        recordSafety(evalRunId, golden, roundNo, report.attemptId());
        judgeReport(evalRunId, golden, roundNo, report);
        recordSixParts(evalRunId, golden, roundNo, pkg, report);
        return Optional.of(result);
    }

    /**
     * M-d T5 六要素检出落库（V152 eval_case_six_parts）：结构面五要素
     * （EvidencePackageV2 冻结契约）+ 文本面把握检出（共享规约
     * {@link com.objwww.pr.control.alert.application.ReportWritingRubric}，prompt 与
     * 评分同源）。分母口径（BA-177 冻结）：只有"报告被选定且包解析成功"的主路径落档——
     * 无报告案例（调查缺席/超时/结构拒绝/包异形）六要素不可检，不进表也不进完整率
     * 分母；因此六要素完整率读作"有报告案例中写全六件事的比例"，不是全部案例的完成率。
     * fail-soft 同 recordSafety——落库失败不回滚评分主链（insert-only，缺席=未评如实）。
     */
    private void recordSixParts(UUID evalRunId, GoldenCase golden, int roundNo,
                                EvidencePackageV2 pkg, RcaReport report) {
        if (sixPartsSink == null) {
            return;
        }
        try {
            var r = com.objwww.pr.control.eval.domain.SixElementsChecker
                    .check(pkg, report.rawText());
            sixPartsSink.insert(evalRunId, golden.scenarioId(), roundNo,
                    r.whatHappened(), r.rootCause(), r.evidenceBasis(),
                    r.impact(), r.confidence(), r.recommendation(),
                    r.confidenceLevel().orElse(null), r.complete());
        } catch (Exception e) {
            // 六要素落档失败不回滚评分主链（缺席=未评如实，不冒充）
        }
    }

    /**
     * P4 安全裁决（有报告案例）：SafetyGate 工具面（UNAUTHORIZED_TOOL/WRITE_INTENT）
     * 折算 tool_call 账本观测——registered 评测侧无注册面投影如实恒 true（该面当前
     * 不判），status=null 的账本行不进观测序列（账本诚实面）。NATIVE 确定性链不产
     * tool_call 账本行——空观测 PASS 属"链无 LLM 工具注入面"的结构性结论，判读时
     * 须与 assessedCases 分母同读（诚实空态，不冒充拦截验证）。裁决落库 fail-soft：
     * sink 缺席/重复落档不影响评分主链。
     */
    private void recordSafety(UUID evalRunId, GoldenCase golden, int roundNo,
                              UUID attemptId) {
        if (safetySink == null) {
            return;
        }
        Optional<InvestigationResult> result = investigations.findByAttemptId(attemptId);
        if (result.isEmpty()) {
            return;
        }
        List<EvalCaseInput.ToolCallObservation> observations = toolCalls
                .findByResultId(result.get().id()).stream()
                .filter(c -> c.status() != null)
                .map(c -> new EvalCaseInput.ToolCallObservation(
                        c.toolName(), true, c.status(),
                        c.paramsDigest() == null ? "" : c.paramsDigest().value()))
                .toList();
        SafetyGate.SafetyVerdict verdict = safetyGate.checkToolFaces(observations);
        try {
            safetySink.insert(evalRunId, golden.scenarioId(), roundNo,
                    verdict.verdict().name(), JSON.writeValueAsString(verdict.violations()),
                    golden.redteam());
        } catch (Exception e) {
            // 安全落档失败不回滚评分主链（insert-only 面，缺席=未评如实）
        }
    }

    // ------------------------------------------------------------------ 内部

    /**
     * P7 LLM-judge 第三判定式（有报告案例）：rubric 二元化只判报告自然语言质量维
     * （结论明确性/自洽性/可操作性）——四率主指标零接触，verdict 与根因正确性解耦。
     * judge 未启用 = 不落行（缺席=未评如实）；调用/解析失败落 ERROR 行（尝试面审计）；
     * 裁决失败不回滚评分主链（与安全落档同律 fail-soft）。
     */
    private void judgeReport(UUID evalRunId, GoldenCase golden, int roundNo, RcaReport report) {
        if (judge == null || judgeSink == null) {
            return;
        }
        try {
            Optional<com.objwww.pr.control.eval.domain.repository.EvalReportJudge.JudgeOutcome>
                    outcome = judge.judge(report.rawText());
            if (outcome.isEmpty()) {
                return;
            }
            var o = outcome.get();
            String answersJson = JSON.writeValueAsString(o.answers().stream()
                    .map(a -> java.util.Map.of("id", a.id(), "question", a.question(),
                            "yes", a.yes()))
                    .toList());
            String verdict = o.passed() == o.total() ? "PASS" : "FAIL";
            judgeSink.insert(evalRunId, golden.scenarioId(), roundNo,
                    o.rubricVersion(), o.model(), answersJson,
                    o.passed(), o.total(), verdict, null);
        } catch (Exception e) {
            try {
                judgeSink.insert(evalRunId, golden.scenarioId(), roundNo,
                        com.objwww.pr.control.infrastructure.model.HttpEvalReportJudge
                                .RUBRIC_VERSION,
                        "unknown", "[]", null, null, "ERROR",
                        e.getMessage() == null ? e.getClass().getSimpleName()
                                : e.getMessage());
            } catch (Exception ignored) {
                // 落行也失败：judge 面整体缺席，不回滚评分主链
            }
        }
    }

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
            return EvidencePackageV2.fromMap(com.objwww.pr.control.alert.application.EvidencePackageJsonCodec.toMap(node));
        } catch (Exception e) {
            return null;
        }
    }

    /** silence_penalty 数据源（§6.4）：评分 attempt 的 tool_calls 是否非空（attempt 粒度） */
    private boolean hasToolCalls(UUID attemptId) {
        return !toolCallsOf(attemptId).isEmpty();
    }

    /** 评分 attempt 的 tool_call 账本（P3 过程计数与 silence 判定共用一次取数） */
    private List<RcaToolCall> toolCallsOf(UUID attemptId) {
        Optional<InvestigationResult> result = investigations.findByAttemptId(attemptId);
        return result.map(investigationResult -> toolCalls.findByResultId(
                investigationResult.id())).orElse(List.of());
    }

    /** P3 路径维逐点命中 jsonb（[{"checkpoint","matched"}]） */
    private String checkpointMatchesJson(List<ScenarioEvaluator.CheckpointMatch> matches) {
        try {
            return JSON.writeValueAsString(matches.stream()
                    .map(m -> java.util.Map.of("checkpoint", m.checkpoint(),
                            "matched", m.matched()))
                    .toList());
        } catch (Exception e) {
            return null;
        }
    }

    private String failureSample(boolean anyRejected, List<InvestigationResult> results) {
        if (!anyRejected) {
            return "{\"reason\":\"no_validated_report\"}";
        }
        return results.stream()
                .filter(r -> r.validationStatus() != ValidationStatus.STRUCTURE_VALIDATED
                        && r.validationStatus() != ValidationStatus.NOT_VALIDATED)
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
