package com.objwww.pr.control.eval.application;

import com.objwww.pr.control.eval.domain.EvalCaseResult;
import com.objwww.pr.control.eval.domain.EvalRun;
import com.objwww.pr.control.eval.domain.EvalRunMetadata;
import com.objwww.pr.control.eval.domain.GoldenCase;
import com.objwww.pr.control.eval.domain.GoldenScenarioRegistry;
import com.objwww.pr.control.eval.domain.ScenarioMetrics;
import com.objwww.pr.control.eval.domain.repository.EvalRunRepository;
import com.objwww.pr.shared.Digest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * 5×2 串行批量评测（M3-17；§6.7/§6.8：场景并发=1，串行执行）。
 *
 * <p>冻结编排语义：
 * <ul>
 *   <li><b>next_round_gate</b>：上一轮恢复回执 criteriaMet+alertsResolved 双真才允许
 *       下一轮/下一场景——门未开则剩余轮记 TIMEOUT_OR_ABSENT（gate_blocked）不注入；</li>
 *   <li><b>失败不中断且落档</b>：单轮任何异常 = 该轮落失败样本，finally 解除注入，
 *       批继续；</li>
 *   <li>评分对象 = {@link SingleCaseScorer}（final-validated-report-v1，禁挑最优）；</li>
 *   <li>终局：原始计数聚合 + 三指标 + {@link BaselineReportGenerator} 基线报告 +
 *       EvalRun 终态一次性 CAS 回填。</li>
 * </ul>
 */
public class EvalBatchRunner {

    private static final Logger log = LoggerFactory.getLogger(EvalBatchRunner.class);

    private final GoldenScenarioRegistry registry;
    private final Map<String, ScenarioDriver> driversByRole;
    private final AlertProbe alertProbe;
    private final RcaRunResolver rcaRunResolver;
    private final SingleCaseScorer scorer;
    private final EvalRunRepository evalRuns;
    private final BaselineReportGenerator reportGenerator;
    private final EvalRunMetadata metadata;
    private final int roundsPerScenario;
    private final EvalClock clock;

    /** 测试可拨的钟（真栈 = 系统钟；sleep 是评测时间参数的执行面） */
    public interface EvalClock {
        Instant now();

        void sleepSeconds(long seconds);
    }

    public record BatchResult(UUID evalRunId,
                              ScenarioMetrics.Snapshot snapshot,
                              EvalRun.SymptomCounts symptomCounts,
                              Digest baselineReportDigest,
                              boolean finalized) {
    }

    public EvalBatchRunner(GoldenScenarioRegistry registry,
                           Map<String, ScenarioDriver> driversByRole,
                           AlertProbe alertProbe,
                           RcaRunResolver rcaRunResolver,
                           SingleCaseScorer scorer,
                           EvalRunRepository evalRuns,
                           BaselineReportGenerator reportGenerator,
                           EvalRunMetadata metadata,
                           int roundsPerScenario,
                           EvalClock clock) {
        this.registry = Objects.requireNonNull(registry);
        this.driversByRole = Objects.requireNonNull(driversByRole);
        this.alertProbe = Objects.requireNonNull(alertProbe);
        this.rcaRunResolver = Objects.requireNonNull(rcaRunResolver);
        this.scorer = Objects.requireNonNull(scorer);
        this.evalRuns = Objects.requireNonNull(evalRuns);
        this.reportGenerator = Objects.requireNonNull(reportGenerator);
        this.metadata = Objects.requireNonNull(metadata);
        this.roundsPerScenario = roundsPerScenario;
        this.clock = Objects.requireNonNull(clock);
    }

    public BatchResult runBatch() {
        UUID evalRunId = UUID.randomUUID();
        evalRuns.insertRunning(EvalRun.running(evalRunId, metadata, clock.now()));

        List<GoldenCase> scenarios = registry.scenarios();
        List<EvalCaseResult> results = new ArrayList<>();
        List<BaselineReportGenerator.CaseFailure> failures = new ArrayList<>();
        boolean gateOpen = true;
        try {
            for (GoldenCase golden : scenarios) {
                ScenarioDriver driver = driversByRole.get(golden.driver());
                if (driver == null) {
                    throw new IllegalStateException(
                            "未装配的驱动器: " + golden.driver() + "（场景 " + golden.scenarioId() + "）");
                }
                for (int round = 1; round <= roundsPerScenario; round++) {
                    if (!gateOpen) {
                        // 门未开：不注入，但案例行照常落档（gate_blocked），批不中断
                        EvalCaseResult blocked = gateBlocked(evalRunId, golden, round);
                        results.add(blocked);
                        persist(blocked);
                        failures.add(new BaselineReportGenerator.CaseFailure(golden.scenarioId(),
                                round, "TIMEOUT_OR_ABSENT", "{\"reason\":\"gate_blocked\"}"));
                        continue;
                    }
                    RoundOutcome outcome = runRound(evalRunId, golden, round, driver);
                    if (outcome.result() != null) {
                        results.add(outcome.result());
                    }
                    failures.addAll(outcome.failures());
                    gateOpen = outcome.gateOpen();
                }
            }
            return finalizeSucceeded(evalRunId, scenarios, results, failures);
        } catch (RuntimeException e) {
            EvalRun aborted = EvalRun.terminal(evalRunId, metadata, EvalRun.EvalRunState.FAILED,
                    clock.now(), clock.now(), null, null, null);
            evalRuns.finalizeOnce(aborted);
            throw e;
        }
    }

    private record RoundOutcome(EvalCaseResult result, boolean gateOpen,
                                List<BaselineReportGenerator.CaseFailure> failures) {
    }

    private RoundOutcome runRound(UUID evalRunId, GoldenCase golden, int round,
                                  ScenarioDriver driver) {
        List<BaselineReportGenerator.CaseFailure> failures = new ArrayList<>();
        ScenarioDriver.ActivationReceipt activation;
        Instant activatedAt = clock.now();
        try {
            activation = driver.activate(golden);
            activatedAt = clock.now();
        } catch (RuntimeException e) {
            log.warn("场景 {} 第 {} 轮注入失败: {}", golden.scenarioId(), round, e.getMessage());
            String sample = "{\"reason\":\"activate_failed\",\"error\":"
                    + quote(e.getMessage()) + "}";
            failures.add(new BaselineReportGenerator.CaseFailure(golden.scenarioId(), round,
                    "TIMEOUT_OR_ABSENT", sample));
            EvalCaseResult result = absentCase(evalRunId, golden, round, sample);
            persist(result);
            return new RoundOutcome(result, false, failures);
        }

        clock.sleepSeconds(golden.timing().preheatSeconds());
        EvalCaseResult result;
        boolean gateOpen;
        try {
            boolean fired = alertProbe.awaitAllFiring(golden.scenarioId(),
                    golden.timing().maxFiringWaitSeconds());
            if (!fired) {
                result = absentCase(evalRunId, golden, round,
                        "{\"reason\":\"alerts_not_firing\"}");
                failures.add(new BaselineReportGenerator.CaseFailure(golden.scenarioId(), round,
                        "TIMEOUT_OR_ABSENT", "{\"reason\":\"alerts_not_firing\"}"));
            } else {
                Optional<UUID> rcaRunId = rcaRunResolver.resolve(golden, activatedAt,
                        golden.timing().maxFiringWaitSeconds());
                result = rcaRunId.flatMap(id -> scorer.score(evalRunId, golden, round, id))
                        .orElseGet(() -> absentCase(evalRunId, golden, round,
                                "{\"reason\":\"run_not_found\"}"));
            }
        } finally {
            // 任何失败都执行 finally 清理（M3-17 冻结）：评分/解析抛出也必须解除注入
            gateOpen = deactivate(driver, golden, round, activation, failures);
        }
        persist(result);
        return new RoundOutcome(result, gateOpen, failures);
    }

    private boolean deactivate(ScenarioDriver driver, GoldenCase golden, int round,
                               ScenarioDriver.ActivationReceipt activation,
                               List<BaselineReportGenerator.CaseFailure> failures) {
        try {
            ScenarioDriver.RecoveryReceipt recovery = driver.deactivate(golden, activation);
            if (!(recovery.criteriaMet() && recovery.alertsResolved())) {
                failures.add(new BaselineReportGenerator.CaseFailure(golden.scenarioId(),
                        round, "RECOVERY", String.join(",", recovery.unmetCriteria())));
                return false;
            }
            return true;
        } catch (RuntimeException e) {
            log.warn("场景 {} 第 {} 轮恢复失败: {}", golden.scenarioId(), round, e.getMessage());
            return false;
        }
    }

    private BatchResult finalizeSucceeded(UUID evalRunId, List<GoldenCase> scenarios,
                                          List<EvalCaseResult> results,
                                          List<BaselineReportGenerator.CaseFailure> failures) {
        List<ScenarioMetrics.ScenarioScore> scores = results.stream()
                .map(r -> new ScenarioMetrics.ScenarioScore(r.scenarioId(), r.verdict(),
                        r.rootCauseHit()))
                .toList();
        EvalRun.SymptomCounts counts = new EvalRun.SymptomCounts(
                results.stream().mapToInt(EvalCaseResult::tpCount).sum(),
                results.stream().mapToInt(EvalCaseResult::fpCount).sum(),
                results.stream().mapToInt(EvalCaseResult::fnCount).sum());

        BaselineReportGenerator.BaselineReport report = reportGenerator.generate(
                metadata, scenarios, scores, failures, counts, clock.now());

        boolean finalized = evalRuns.finalizeOnce(EvalRun.terminal(evalRunId, metadata,
                EvalRun.EvalRunState.SUCCEEDED, clock.now(), clock.now(),
                ScenarioMetrics.of(scores), counts, report.reportDigest()));
        return new BatchResult(evalRunId, ScenarioMetrics.of(scores), counts,
                report.reportDigest(), finalized);
    }

    private void persist(EvalCaseResult result) {
        if (!evalRuns.insertCaseResult(result)) {
            log.warn("eval_case_result 重复写入被拒（不可覆盖语义）: {} r{}",
                    result.scenarioId(), result.roundNo());
        }
    }

    private EvalCaseResult absentCase(UUID evalRunId, GoldenCase golden, int round,
                                      String sampleJson) {
        return new EvalCaseResult(UUID.randomUUID(), evalRunId, golden.scenarioId(), round,
                FinalReportSelector.SELECTION_POLICY_VERSION, null, null, null,
                ScenarioMetrics.ScoringVerdict.TIMEOUT_OR_ABSENT, false,
                golden.expectedRootCause(), null,
                golden.expectedSymptomCodes(), List.of(), 0, 0,
                golden.expectedSymptomCodes().size(), null, false,
                sampleJson);
    }

    private EvalCaseResult gateBlocked(UUID evalRunId, GoldenCase golden, int round) {
        return absentCase(evalRunId, golden, round, "{\"reason\":\"gate_blocked\"}");
    }

    private static String quote(String value) {
        return "\"" + (value == null ? "" : value.replace("\\", "\\\\").replace("\"", "'"))
                + "\"";
    }
}
