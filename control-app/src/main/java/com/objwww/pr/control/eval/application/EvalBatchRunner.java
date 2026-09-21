package com.objwww.pr.control.eval.application;

import com.objwww.pr.control.eval.domain.EvalCaseResult;
import com.objwww.pr.control.eval.domain.EvalRun;
import com.objwww.pr.control.eval.domain.EvalRunMetadata;
import com.objwww.pr.control.eval.domain.GoldenCase;
import com.objwww.pr.control.eval.domain.GoldenScenarioRegistry;
import com.objwww.pr.control.eval.domain.ScenarioMetrics;
import com.objwww.pr.control.eval.domain.repository.EvalCaseSafetySink;
import com.objwww.pr.control.eval.domain.repository.EvalPhaseEventSink;
import com.objwww.pr.control.eval.domain.repository.EvalPreregistrationSink;
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
 *   <li><b>回放单次测量边界</b>（2026-09-17 北极星三批六连 TIMEOUT_OR_ABSENT 定谳）：
 *       冻结载荷重放对同一 incident_key 撞上管线三道正确性闸（alert_event 去重 /
 *       迟到 firing 不复活 / 材料哈希去重），第 2 轮起结构上不可能铸新 run——
 *       replay 案例轮次裁剪为 1（{@link #effectiveRounds}），不再烧
 *       maxFiringWait+hold 预算报假红；多轮测量走故障注入造新材料，不走载荷重放。</li>
 *   <li><b>EV-04 生命周期</b>：{@link RunLifecycle} 挂点承载 worker 形态的发起身份
 *       回填、eval_phase_event 阶段事件、案例边界取消检查点与 L 模式恢复核验；
 *       {@link #runBatch()}（noop 挂点）保持 M3 一次性跑批语义不变。</li>
 *   <li><b>BA-190 run-tag 空值兜底</b>：批开始计算有效 run-tag（配置非空 = 配置值；
 *       空 = "r"+evalRunId 前 12 位 hex（BA-191 由 8 位加宽），{@link EvalRunTags}），
 *       注入面与解析面共用；
 *       <b>零注入全灭诚实终态</b>：全批无一轮真实激活（全 gate_blocked/activate_failed）
 *       → FAILED + 中文卡因，不伪装 SUCCEEDED（efde9e17/44f220ef 假绿定谳）。</li>
 *   <li><b>D03 缺席案例安全终态</b>：未注入轮（gate_blocked/prev_round_not_resolved/
 *       activate_failed）落 NOT_APPLICABLE（合理 NA）；已注入但无 trace
 *       （alerts_not_firing/run_not_found）落 NOT_ASSESSED——缺证据不冒充零违规。</li>
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
    /** BA-190：启动方注入的 run-tag（env 静态值）；空 = 批开始时按 evalRunId 派生兜底 */
    private final String configuredRunTag;
    /** ME-T12b：预登记落库面（可空 = 不登记，旧装配零漂移；验收面缺席如实 INCONCLUSIVE） */
    private final EvalPreregistrationSink preregistrationSink;

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
                               CancelSignal cancelSignal,
                               java.util.function.Consumer<UUID> terminalHook) {

        /** 旧 CLI 形态：无身份回填、无事件、无取消、无终态钩子 */
        public static RunLifecycle noop() {
            return new RunLifecycle(null, null, null, null, null, null, null);
        }

        /** 兼容 EV-07 自动落档前形态（6 参——无终态钩子） */
        public RunLifecycle(String mode, String displayName, String launchPlanJson,
                            String workerId, EvalPhaseEventSink phaseSink,
                            CancelSignal cancelSignal) {
            this(mode, displayName, launchPlanJson, workerId, phaseSink, cancelSignal, null);
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

        /** EV-07 终态钩子（自动对比落档；仅 finalizeOnce 真实迁移成功后调用） */
        void onTerminal(UUID evalRunId) {
            if (terminalHook != null) {
                terminalHook.accept(evalRunId);
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
        this(registry, driversByRole, alertProbe, incidentProbe, rcaRunResolver, scorer,
                evalRuns, reportGenerator, metadata, roundsPerScenario, clock, "");
    }

    /** BA-190 全参形态：configuredRunTag = 启动方注入的 run-tag（可空/空串 = 批开始
     *  时按 evalRunId 派生逐批唯一兜底 tag，见 {@link EvalRunTags}） */
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
                           EvalClock clock,
                           String configuredRunTag) {
        this(registry, driversByRole, alertProbe, incidentProbe, rcaRunResolver, scorer,
                evalRuns, reportGenerator, metadata, roundsPerScenario, clock,
                configuredRunTag, null);
    }

    /** ME-T12b 全参形态：preregistrationSink = 预登记落库面（V165；null = 不登记，
     *  旧装配/测试兼容——验收面读不到登记时质量面如实 INCONCLUSIVE 不猜） */
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
                           EvalClock clock,
                           String configuredRunTag,
                           EvalPreregistrationSink preregistrationSink) {
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
        this.configuredRunTag = configuredRunTag == null ? "" : configuredRunTag;
        this.preregistrationSink = preregistrationSink;
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
        recordPreregistration(evalRunId);
        lifecycle.onRunStarted(evalRuns, evalRunId, clock.now());

        // BA-190 run-tag 空值兜底：配置 tag 空 → 按 evalRunId 派生逐批唯一有效 tag，
        // 注入面（driver 拷贝）与解析面（resolver 拷贝）共用同一值——空 tag 退化的
        // chaos-eval-{sid}-r{round} 撞 uq_chaos_scenario 永存台账的链路自此封死
        // （2026-09-19 批件 efde9e17/44f220ef 全灭假绿定谳）
        String effectiveRunTag = EvalRunTags.effective(configuredRunTag, evalRunId);
        if (!effectiveRunTag.equals(configuredRunTag)) {
            log.info("run-tag 未注入或剔除后为空：批 {} 按 evalRunId 派生有效 run-tag {}",
                    evalRunId, effectiveRunTag);
        }
        Map<String, ScenarioDriver> effectiveDrivers = new java.util.LinkedHashMap<>();
        driversByRole.forEach((role, driver) ->
                effectiveDrivers.put(role, driver.withRunTag(effectiveRunTag)));
        RcaRunResolver effectiveResolver = rcaRunResolver.withRunTag(effectiveRunTag);

        List<GoldenCase> scenarios = registry.scenarios();
        // 防假绿预检：批件用到的驱动器逐个自检（如 chaos token 未注入），不满足
        // 在首案注入前抛错 → catch 面落 FAILED（batch_error:preflight…），
        // 不允许全案 TIMEOUT_OR_ABSENT 后零分"SUCCEEDED"
        scenarios.stream().map(golden -> effectiveDrivers.get(golden.driver()))
                .filter(Objects::nonNull).distinct()
                .forEach(ScenarioDriver::preflight);
        List<EvalCaseResult> results = new ArrayList<>();
        List<BaselineReportGenerator.CaseFailure> failures = new ArrayList<>();
        boolean gateOpen = true;
        boolean cancelled = false;
        boolean anyActivated = false;
        try {
            for (GoldenCase golden : scenarios) {
                ScenarioDriver driver = effectiveDrivers.get(golden.driver());
                if (driver == null) {
                    throw new IllegalStateException(
                            "未装配的驱动器: " + golden.driver() + "（场景 " + golden.scenarioId() + "）");
                }
                int effectiveRounds = effectiveRounds(golden);
                for (int round = 1; round <= effectiveRounds; round++) {
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
                        // D03：未注入轮无观测义务 → 安全面 NOT_APPLICABLE（合理 NA，
                        // 不计未评）
                        scorer.recordSafetyOutcome(evalRunId, golden, round,
                                EvalCaseSafetySink.VERDICT_NOT_APPLICABLE);
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
                        scorer.recordSafetyOutcome(evalRunId, golden, round,
                                EvalCaseSafetySink.VERDICT_NOT_APPLICABLE);
                        failures.add(new BaselineReportGenerator.CaseFailure(golden.scenarioId(),
                                round, "TIMEOUT_OR_ABSENT",
                                "{\"reason\":\"prev_round_not_resolved\"}"));
                        gateOpen = false;
                        continue;
                    }
                    RoundOutcome outcome = runRound(evalRunId, golden, round, driver,
                            lifecycle, effectiveResolver);
                    if (outcome.result() != null) {
                        results.add(outcome.result());
                    }
                    failures.addAll(outcome.failures());
                    gateOpen = outcome.gateOpen();
                    anyActivated = anyActivated || outcome.activated();
                }
                if (cancelled) {
                    break;
                }
            }
            if (cancelled) {
                return finalizeCancelled(evalRunId, startedAt, lifecycle, scenarios);
            }
            return finalizeSucceeded(evalRunId, startedAt, scenarios, results, failures,
                    lifecycle, anyActivated);
        } catch (RuntimeException e) {
            EvalRun aborted = EvalRun.terminal(evalRunId, metadata, EvalRun.EvalRunState.FAILED,
                    startedAt, clock.now(), null, null, null,
                    "batch_error:" + abbreviate(e.getMessage()));
            if (evalRuns.finalizeOnce(aborted)) {
                lifecycle.onTerminal(evalRunId);
            }
            throw e;
        }
    }

    /** activated = 本轮真实注入成功（BA-190 零注入全灭判定的输入；回放载荷重投同计） */
    private record RoundOutcome(EvalCaseResult result, boolean gateOpen,
                                List<BaselineReportGenerator.CaseFailure> failures,
                                boolean activated) {
    }

    /**
     * 回放案例单次测量边界：冻结载荷重投对同一 incident_key 撞上管线三道正确性闸
     * （alert_event 去重 / 迟到 firing 不复活 / 材料哈希去重），第 2 轮起结构上不可能
     * 铸新 run——裁剪为单轮，诚实落一档测量，不再烧 maxFiringWait+hold 预算报假红
     * run_not_found。多轮/稳定性测量走故障注入造新材料（注入场景不受此限）。
     */
    private int effectiveRounds(GoldenCase golden) {
        if (golden.replay() && roundsPerScenario > 1) {
            log.info("场景 {} 为回放形态：单次测量边界生效，轮次 {} 裁剪为 1",
                    golden.scenarioId(), roundsPerScenario);
            return 1;
        }
        return roundsPerScenario;
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
                                  ScenarioDriver driver, RunLifecycle lifecycle,
                                  RcaRunResolver resolver) {
        List<BaselineReportGenerator.CaseFailure> failures = new ArrayList<>();
        ScenarioDriver.ActivationReceipt activation;
        Instant activatedAt = clock.now();
        try {
            lifecycle.record(evalRunId, golden.replay() ? "REPLAYING" : "INJECTING",
                    activatedAt, roundDetail(golden, round));
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
            // D03：注入失败=未注入，安全面 NOT_APPLICABLE（无观测义务）
            scorer.recordSafetyOutcome(evalRunId, golden, round,
                    EvalCaseSafetySink.VERDICT_NOT_APPLICABLE);
            return new RoundOutcome(result, false, failures, false);
        }

        clock.sleepSeconds(golden.timing().preheatSeconds());
        EvalCaseResult result;
        boolean gateOpen;
        Optional<UUID> rcaRunId = Optional.empty();
        try {
            // P2 回放分支：冻结载荷已重投（activate），告警面 = DB incident 新
            // episode，不经 Prometheus——跳过 firing 探针直接等新 run 终态；
            // 预算沿用 firingWait+hold（调查时长主导）。注入场景原语义不变。
            boolean fired = true;
            if (!golden.replay()) {
                lifecycle.record(evalRunId, "AWAITING_ALERT", clock.now(),
                        roundDetail(golden, round));
                fired = alertProbe.awaitAllFiring(golden.scenarioId(),
                        golden.timing().maxFiringWaitSeconds());
            }
            if (!fired) {
                result = absentCase(evalRunId, golden, round,
                        "{\"reason\":\"alerts_not_firing\"}");
                // D03：已注入但无告警无 trace——安全面 NOT_ASSESSED（trace 缺失，
                // 不得显示零违规通过）
                scorer.recordSafetyOutcome(evalRunId, golden, round,
                        EvalCaseSafetySink.VERDICT_NOT_ASSESSED);
                failures.add(new BaselineReportGenerator.CaseFailure(golden.scenarioId(), round,
                        "TIMEOUT_OR_ABSENT", "{\"reason\":\"alerts_not_firing\"}"));
            } else {
                // 等待窗口 = firing 等待 + hold（run 需到达终态才可评分——Holmes 调查
                // 需数分钟，故障在窗口内保持激活，deactivate 在评分后执行）
                int resolveTimeoutSeconds = golden.timing().maxFiringWaitSeconds()
                        + golden.timing().holdSeconds();
                lifecycle.record(evalRunId, "AWAITING_RCA", clock.now(),
                        roundDetail(golden, round));
                Optional<UUID> resolved = resolver.resolve(golden, round,
                        activatedAt, resolveTimeoutSeconds);
                rcaRunId = resolved;
                lifecycle.record(evalRunId, "SCORING", clock.now(),
                        roundDetail(golden, round));
                Optional<EvalCaseResult> scored = resolved.flatMap(
                        id -> scorer.score(evalRunId, golden, round, id));
                if (scored.isEmpty()) {
                    // D03：已注入但 run 不可解析——trace 缺失 → NOT_ASSESSED
                    scorer.recordSafetyOutcome(evalRunId, golden, round,
                            EvalCaseSafetySink.VERDICT_NOT_ASSESSED);
                }
                result = scored.orElseGet(() -> absentCase(evalRunId, golden, round,
                        "{\"reason\":\"run_not_found\"}"));
            }
        } finally {
            // 任何失败都执行 finally 清理（M3-17 冻结）：评分/解析抛出也必须解除注入
            gateOpen = deactivate(driver, golden, round, activation, failures);
        }
        persist(result);
        // ME-T04（D04）：案例终态行为评测落档（V159 case_result_id 外键次序 =
        // 先 persist 案例行）；rcaRunId 缺席 = 已注入但无 trace，依赖轨迹的检查
        // NOT_ASSESSED 不猜通过（EV-05 同律）；activate_failed 未注入轮不落行为行
        scorer.recordBehavior(evalRunId, golden, round, rcaRunId.orElse(null), result.id());
        // ME-T12a（D05）：案例终态死循环评测落档（V162 同键面次序与 fail-soft 纪律；
        // 未注入轮同样不落行）
        scorer.recordLoop(evalRunId, golden, round, rcaRunId.orElse(null), result.id());
        // ME-T12a（D07）：案例终态协作评测落档（V163 同键面次序与 fail-soft 纪律）
        scorer.recordCollab(evalRunId, golden, round, rcaRunId.orElse(null), result.id());
        // ME-T12a（D08）：案例终态上下文漂移评测落档（V164 同键面次序与 fail-soft 纪律；
        // 无压缩事件 = 摘要缺席如实 NA，不猜）
        scorer.recordDrift(evalRunId, golden, round, rcaRunId.orElse(null), result.id());
        return new RoundOutcome(result, gateOpen, failures, true);
    }

    /**
     * ME-T12b（D09）预登记落档：eval_run 落行后立写（判定锚跑批之前冻结——
     * 受理 HTTP 链早于 run 行存在，外键次序决定写点只能在批起始）。min_clusters
     * 取 {@link com.objwww.pr.control.eval.domain.service.PairedTrialStats#MIN_CLUSTERS}
     * （比较门簇数下限同源常量——登记时刻快照，未来门阈调整不回溯旧批锚）；
     * digest = sha256(runId|minClusters|registeredAt 截断到秒)。落库失败记 warn
     * 不阻发批（fail-soft 同安全落档律；验收面读不到登记时质量面如实
     * INCONCLUSIVE/PREREGISTRATION_MISSING 不猜）。
     */
    void recordPreregistration(UUID evalRunId) {
        if (preregistrationSink == null) {
            return;
        }
        try {
            int minClusters =
                    com.objwww.pr.control.eval.domain.service.PairedTrialStats.MIN_CLUSTERS;
            Instant registeredAt = clock.now()
                    .truncatedTo(java.time.temporal.ChronoUnit.SECONDS);
            String digest = Digest.sha256Of("eval-prereg/v1|runId=" + evalRunId
                    + "|minClusters=" + minClusters + "|registeredAt=" + registeredAt).value();
            preregistrationSink.insert(evalRunId, minClusters, digest, registeredAt);
        } catch (Exception e) {
            log.warn("eval 预登记落档失败（不阻发批；验收面质量锚缺席如实 INCONCLUSIVE）"
                    + " run={}：{}", evalRunId, e.toString());
        }
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
                                          RunLifecycle lifecycle,
                                          boolean anyActivated) {
        lifecycle.record(evalRunId, "FINALIZING", clock.now(), null);
        if (!anyActivated && !results.isEmpty()) {
            // BA-190 零注入全灭诚实终态（2026-09-19 批件 efde9e17/44f220ef 定谳）：
            // 全部案例轮次都是 gate_blocked/activate_failed/prev_round_not_resolved——
            // 零真实注入、零真实调查，测量无效，不得伪装 SUCCEEDED 假绿。与中途门关闭
            // 的老语义严格区分：只要有一轮真实激活（部分 gate_blocked）仍走原
            // SUCCEEDED 聚合。FAILED 不填指标/基线报告（夭折形态，不伪装完整实验）。
            String firstSample = failures.isEmpty() ? null : failures.getFirst().failureSampleJson();
            String reason = "全部案例注入失败（首案失败样本：" + abbreviate(firstSample)
                    + "）——批件零真实注入、未产生任何真实调查，测量无效；"
                    + "请检查 run-tag 唯一性或冲突会话后重发（未注入 run-tag 时系统自动按批派生唯一 tag）";
            log.warn("批 {} 零注入全灭：{} 个案例轮次无一真实激活，终态 FAILED（{}）",
                    evalRunId, results.size(), reason);
            boolean finalized = evalRuns.finalizeOnce(EvalRun.terminal(evalRunId, metadata,
                    EvalRun.EvalRunState.FAILED, startedAt, clock.now(),
                    null, null, null, reason));
            if (finalized) {
                lifecycle.onTerminal(evalRunId);
            }
            return new BatchResult(evalRunId, null, null, null, finalized);
        }
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
        if (finalized) {
            lifecycle.onTerminal(evalRunId);
        }
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
        if (finalized) {
            lifecycle.onTerminal(evalRunId);
        }
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
                sampleJson, null, null, null, null, null, null, null, null, null,
                golden.difficulty());
    }

    private EvalCaseResult gateBlocked(UUID evalRunId, GoldenCase golden, int round) {
        return absentCase(evalRunId, golden, round, "{\"reason\":\"gate_blocked\"}");
    }

    /**
     * failure_sample 的 JSON 字符串面（195 E2E 实证修正）：必须转义控制字符——
     * 旧实现只处理反斜杠（引号直接换成单引号），异常消息里的裸换行（0x0a）使
     * jsonb 入库报 "Character with value 0x0a must be escaped" 而炸掉落档面。
     */
    static String quote(String value) {
        StringBuilder sb = new StringBuilder("\"");
        String v = value == null ? "" : value;
        for (int i = 0; i < v.length(); i++) {
            char c = v.charAt(i);
            switch (c) {
                case '\\' -> sb.append("\\\\");
                case '"' -> sb.append("\\\"");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        return sb.append('"').toString();
    }
}
