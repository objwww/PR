package com.objwww.pr.control.eval.application;

import com.objwww.pr.control.eval.domain.EvalCaseResult;
import com.objwww.pr.control.eval.domain.EvalRun;
import com.objwww.pr.control.eval.domain.EvalRunMetadata;
import com.objwww.pr.control.eval.domain.GoldenCase;
import com.objwww.pr.control.eval.domain.GoldenScenarioRegistry;
import com.objwww.pr.control.eval.domain.ScenarioMetrics;
import com.objwww.pr.control.eval.domain.repository.EvalPhaseEventSink;
import com.objwww.pr.control.eval.domain.repository.EvalRunRepository;
import com.objwww.pr.control.eval.domain.statemachine.EvalRunLifecycle;
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
 *   <li><b>prev_episode_resolved</b>：每轮注入前先确认目标 alertname 的 incident 已落
 *       RESOLVED（2026-09-09 S3 smoke 坐实：Prometheus resolved ≠ 链已消费 resolved，
 *       同指纹再 firing 被 AM repeat_interval 抑制且 incident 未 RESOLVED 不铸新 run）——
 *       预算耗尽该轮显式判 prev_round_not_resolved 并关门，禁止静默带残留现场注入；</li>
 *   <li><b>失败不中断且落档</b>：单轮任何异常 = 该轮落失败样本，finally 解除注入，
 *       批继续；</li>
 *   <li>评分对象 = {@link SingleCaseScorer}（final-validated-report-v1，禁挑最优）；</li>
 *   <li>终局：原始计数聚合 + 三指标 + {@link BaselineReportGenerator} 基线报告 +
 *       EvalRun 终态一次性 CAS 回填。</li>
 *   <li><b>EV-04 生命周期</b>：{@link RunLifecycle} 挂点承载 worker 形态的发起身份
 *       回填、eval_phase_event 阶段事件、案例边界取消检查点与 L 模式恢复核验；
 *       {@link #runBatch()}（noop 挂点）保持 M3 一次性跑批语义不变。</li>
 * </ul>
 */
public class EvalBatchRunner {

    private static final Logger log = LoggerFactory.getLogger(EvalBatchRunner.class);

    private final GoldenScenarioRegistry registry;
    private final Map<String, ScenarioDriver> driversByRole;
    private final AlertProbe alertProbe;
    private final IncidentResolutionProbe incidentProbe;
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

    /** EV-04 取消信号（worker 检查点读 eval_run_command 受理面；noop = 永不取消） */
    public interface CancelSignal {
        boolean cancelRequested(UUID evalRunId);
    }

    /**
     * EV-04 生命周期挂点（worker 驱动形态；{@link #noop()} = 旧 CLI 一次性跑批，
     * 零事件零身份回填零取消检查——M3 语义原样保留）：
     * <ul>
     *   <li>开跑即回填 display_name/mode/launch_plan 并落 PREPARING 事件；L 模式
     *       recovery_state=PENDING（恢复义务自此挂账）；</li>
     *   <li>各阶段迁移落 eval_phase_event（insert-only）；</li>
     *   <li>检查点语义：取消只在<b>案例边界</b>生效——在跑轮次走完全部 finally 解除
     *       注入，之后不再推进新案例；受理≠已停（读面 cancel_requested_at 先行）；</li>
     *   <li>L 模式取消强制恢复路径（EvalRunLifecycle.requiresRecovery）：RECOVERING
     *       事件 + recovery_state=RECOVERING → 全场景 incident RESOLVED 核验 →
     *       VERIFIED/FAILED → 才允许 FAILED 终态（EU14）。</li>
     * </ul>
     */
    public record RunLifecycle(String mode, String displayName, String launchPlanJson,
                               String workerId, EvalPhaseEventSink phaseSink,
                               CancelSignal cancelSignal) {

        /** 旧 CLI 形态：无身份回填、无事件、无取消 */
        public static RunLifecycle noop() {
            return new RunLifecycle(null, null, null, null, null, null);
        }

        boolean active() {
            return phaseSink != null;
        }

        boolean requiresRecovery() {
            return EvalRunLifecycle.requiresRecovery(mode);
        }

        boolean cancelRequested(UUID evalRunId) {
            return cancelSignal != null && cancelSignal.cancelRequested(evalRunId);
        }

        void onRunStarted(EvalRunRepository evalRuns, UUID evalRunId, Instant at) {
            if (!active()) {
                return;
            }
            evalRuns.applyLaunchIdentity(evalRunId, displayName, mode, launchPlanJson);
            record(evalRunId, "PREPARING", at, null);
            if (requiresRecovery()) {
                evalRuns.updateRecoveryState(evalRunId, EvalRunLifecycle.RECOVERY_PENDING);
            }
        }

        void record(UUID evalRunId, String phase, Instant at, String detailJson) {
            if (active()) {
                phaseSink.record(evalRunId, phase, at, workerId, detailJson);
            }
        }
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
                           IncidentResolutionProbe incidentProbe,
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
        this.incidentProbe = Objects.requireNonNull(incidentProbe);
        this.rcaRunResolver = Objects.requireNonNull(rcaRunResolver);
        this.scorer = Objects.requireNonNull(scorer);
        this.evalRuns = Objects.requireNonNull(evalRuns);
        this.reportGenerator = Objects.requireNonNull(reportGenerator);
        this.metadata = Objects.requireNonNull(metadata);
        this.roundsPerScenario = roundsPerScenario;
        this.clock = Objects.requireNonNull(clock);
    }

    /** 旧 CLI 一次性跑批（M3 语义原样）：随机 id、零生命周期挂点 */
    public BatchResult runBatch() {
        return runBatch(UUID.randomUUID(), RunLifecycle.noop());
    }

    /**
     * EV-04 worker 形态：预定 run id（= LAUNCH 命令的 evalRunId，崩溃重放稳定身份）
     * + 生命周期挂点（身份回填/阶段事件/取消检查点/L 恢复路径，见 {@link RunLifecycle}）。
     */
    public BatchResult runBatch(UUID evalRunId, RunLifecycle lifecycle) {
        Instant startedAt = clock.now();
        evalRuns.insertRunning(EvalRun.running(evalRunId, metadata, startedAt));
        lifecycle.onRunStarted(evalRuns, evalRunId, clock.now());

        List<GoldenCase> scenarios = registry.scenarios();
        List<EvalCaseResult> results = new ArrayList<>();
        List<BaselineReportGenerator.CaseFailure> failures = new ArrayList<>();
        boolean gateOpen = true;
        boolean cancelled = false;
        try {
            for (GoldenCase golden : scenarios) {
                ScenarioDriver driver = driversByRole.get(golden.driver());
                if (driver == null) {
                    throw new IllegalStateException(
                            "未装配的驱动器: " + golden.driver() + "（场景 " + golden.scenarioId() + "）");
                }
                for (int round = 1; round <= roundsPerScenario; round++) {
                    // EV-04 取消检查点：案例边界生效——在跑轮次走完后不再推进新案例
                    if (lifecycle.cancelRequested(evalRunId)) {
                        cancelled = true;
                        break;
                    }
                    if (!gateOpen) {
                        // 门未开：不注入，但案例行照常落档（gate_blocked），批不中断
                        EvalCaseResult blocked = gateBlocked(evalRunId, golden, round);
                        results.add(blocked);
                        persist(blocked);
                        failures.add(new BaselineReportGenerator.CaseFailure(golden.scenarioId(),
                                round, "TIMEOUT_OR_ABSENT", "{\"reason\":\"gate_blocked\"}"));
                        continue;
                    }
                    if (!prevEpisodeResolved(golden)) {
                        // 上轮 episode 残留（incident 未 RESOLVED）：显式判败 + 关门，
                        // 禁止静默带残留现场注入（S3 smoke R2 run_not_found 的修复面）
                        EvalCaseResult blocked = absentCase(evalRunId, golden, round,
                                "{\"reason\":\"prev_round_not_resolved\"}");
                        results.add(blocked);
                        persist(blocked);
                        failures.add(new BaselineReportGenerator.CaseFailure(golden.scenarioId(),
                                round, "TIMEOUT_OR_ABSENT",
                                "{\"reason\":\"prev_round_not_resolved\"}"));
                        gateOpen = false;
                        continue;
                    }
                    RoundOutcome outcome = runRound(evalRunId, golden, round, driver, lifecycle);
                    if (outcome.result() != null) {
                        results.add(outcome.result());
                    }
                    failures.addAll(outcome.failures());
                    gateOpen = outcome.gateOpen();
                }
                if (cancelled) {
                    break;
                }
            }
            if (cancelled) {
                return finalizeCancelled(evalRunId, startedAt, lifecycle, scenarios);
            }
            return finalizeSucceeded(evalRunId, startedAt, scenarios, results, failures,
                    lifecycle);
        } catch (RuntimeException e) {
            EvalRun aborted = EvalRun.terminal(evalRunId, metadata, EvalRun.EvalRunState.FAILED,
                    startedAt, clock.now(), null, null, null,
                    "batch_error:" + abbreviate(e.getMessage()));
            evalRuns.finalizeOnce(aborted);
            throw e;
        }
    }

    private record RoundOutcome(EvalCaseResult result, boolean gateOpen,
                                List<BaselineReportGenerator.CaseFailure> failures) {
    }

    /**
     * 注入前确认目标 alertname 的 incident episode 已关闭（RESOLVED/无行）。预算 =
     * 本轮 max_resolved_wait_seconds（覆盖 AM resolve_timeout + resolved webhook 链
     * 延迟，S3 smoke 实测 B 段 endsAt→resolved 投递 ~2.5min）；无期望症状码的场景
     * 无告警面，直接放行。
     */
    private boolean prevEpisodeResolved(GoldenCase golden) {
        if (golden.expectedSymptomCodes().isEmpty()) {
            return true;
        }
        String alertname = golden.expectedSymptomCodes().getFirst();
        boolean resolved = incidentProbe.awaitIncidentResolved(alertname,
                golden.timing().maxResolvedWaitSeconds());
        if (!resolved) {
            log.warn("场景 {} 注入前 incident 未 resolved（alertname={}，预算 {}s 耗尽）"
                    + "——本轮判 prev_round_not_resolved 并关门",
                    golden.scenarioId(), alertname, golden.timing().maxResolvedWaitSeconds());
        }
        return resolved;
    }

    private RoundOutcome runRound(UUID evalRunId, GoldenCase golden, int round,
                                  ScenarioDriver driver, RunLifecycle lifecycle) {
        List<BaselineReportGenerator.CaseFailure> failures = new ArrayList<>();
        ScenarioDriver.ActivationReceipt activation;
        Instant activatedAt = clock.now();
        try {
            lifecycle.record(evalRunId, "INJECTING", activatedAt, roundDetail(golden, round));
            activation = driver.activate(golden, round);
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
            lifecycle.record(evalRunId, "AWAITING_ALERT", clock.now(),
                    roundDetail(golden, round));
            boolean fired = alertProbe.awaitAllFiring(golden.scenarioId(),
                    golden.timing().maxFiringWaitSeconds());
            if (!fired) {
                result = absentCase(evalRunId, golden, round,
                        "{\"reason\":\"alerts_not_firing\"}");
                failures.add(new BaselineReportGenerator.CaseFailure(golden.scenarioId(), round,
                        "TIMEOUT_OR_ABSENT", "{\"reason\":\"alerts_not_firing\"}"));
            } else {
                // 等待窗口 = firing 等待 + hold（run 需到达终态才可评分——Holmes 调查
                // 需数分钟，故障在窗口内保持激活，deactivate 在评分后执行）
                int resolveTimeoutSeconds = golden.timing().maxFiringWaitSeconds()
                        + golden.timing().holdSeconds();
                lifecycle.record(evalRunId, "AWAITING_RCA", clock.now(),
                        roundDetail(golden, round));
                Optional<UUID> rcaRunId = rcaRunResolver.resolve(golden, round,
                        activatedAt, resolveTimeoutSeconds);
                lifecycle.record(evalRunId, "SCORING", clock.now(),
                        roundDetail(golden, round));
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

    private BatchResult finalizeSucceeded(UUID evalRunId, Instant startedAt,
                                          List<GoldenCase> scenarios,
                                          List<EvalCaseResult> results,
                                          List<BaselineReportGenerator.CaseFailure> failures,
                                          RunLifecycle lifecycle) {
        lifecycle.record(evalRunId, "FINALIZING", clock.now(), null);
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
                EvalRun.EvalRunState.SUCCEEDED, startedAt, clock.now(),
                ScenarioMetrics.of(scores), counts, report.reportDigest()));
        return new BatchResult(evalRunId, ScenarioMetrics.of(scores), counts,
                report.reportDigest(), finalized);
    }

    /**
     * EV-04 取消收尾（检查点语义：受理时正在跑的轮次已走完，此后零新案例）：
     * <ul>
     *   <li>E/B 模式：无现场恢复义务——直接 FAILED（reason=cancelled_by_operator，
     *       无指标快照、无基线报告——中途夭折形态，不伪装完整实验）；</li>
     *   <li>L 模式（EU14）：RECOVERING 事件 + recovery_state=RECOVERING → 全场景
     *       incident RESOLVED 核验（每场景预算 = 其 max_resolved_wait_seconds）→
     *       VERIFIED/FAILED 落账后才终态 FAILED——"实验结束不伪装现场恢复"。</li>
     * </ul>
     */
    private BatchResult finalizeCancelled(UUID evalRunId, Instant startedAt,
                                          RunLifecycle lifecycle,
                                          List<GoldenCase> scenarios) {
        String reason = EvalRunLifecycle.REASON_CANCELLED;
        if (lifecycle.requiresRecovery()) {
            lifecycle.record(evalRunId, "RECOVERING", clock.now(), "{\"reason\":\"cancel\"}");
            evalRuns.updateRecoveryState(evalRunId, EvalRunLifecycle.RECOVERY_RECOVERING);
            boolean verified = verifyRecovery(scenarios);
            evalRuns.updateRecoveryState(evalRunId, verified
                    ? EvalRunLifecycle.RECOVERY_VERIFIED : EvalRunLifecycle.RECOVERY_FAILED);
            reason = reason + ";recovery="
                    + (verified ? EvalRunLifecycle.RECOVERY_VERIFIED
                                : EvalRunLifecycle.RECOVERY_FAILED);
        }
        boolean finalized = evalRuns.finalizeOnce(EvalRun.terminal(evalRunId, metadata,
                EvalRun.EvalRunState.FAILED, startedAt, clock.now(),
                null, null, null, reason));
        return new BatchResult(evalRunId, null, null, null, finalized);
    }

    /** L 模式恢复核验：所有带告警面场景的目标 alertname incident 均已 RESOLVED */
    private boolean verifyRecovery(List<GoldenCase> scenarios) {
        boolean allResolved = true;
        for (GoldenCase golden : scenarios) {
            if (golden.expectedSymptomCodes().isEmpty()) {
                continue;
            }
            String alertname = golden.expectedSymptomCodes().getFirst();
            boolean resolved = incidentProbe.awaitIncidentResolved(alertname,
                    golden.timing().maxResolvedWaitSeconds());
            if (!resolved) {
                log.warn("L 模式取消恢复核验：场景 {} 的 incident 未 RESOLVED"
                        + "（alertname={}，预算 {}s 耗尽）",
                        golden.scenarioId(), alertname, golden.timing().maxResolvedWaitSeconds());
                allResolved = false;
            }
        }
        return allResolved;
    }

    private static String roundDetail(GoldenCase golden, int round) {
        return "{\"scenarioId\":" + quote(golden.scenarioId()) + ",\"round\":" + round + "}";
    }

    /** 卡因列截断（异常消息长度防御；terminal_reason 为 text 但日志/投影面要可读） */
    private static String abbreviate(String message) {
        if (message == null) {
            return "unknown";
        }
        return message.length() > 200 ? message.substring(0, 200) : message;
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
