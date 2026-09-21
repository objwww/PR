package com.objwww.pr.control.eval.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.objwww.pr.control.eval.domain.EvalRunMetadata;
import com.objwww.pr.control.eval.domain.GoldenCase;
import com.objwww.pr.control.eval.domain.GoldenScenarioRegistry;
import com.objwww.pr.control.eval.domain.model.EvalLaunchPlan;
import com.objwww.pr.control.eval.domain.model.EvalRunCommand;
import com.objwww.pr.control.eval.domain.repository.EvalPhaseEventSink;
import com.objwww.pr.control.eval.domain.repository.EvalPreregistrationSink;
import com.objwww.pr.control.eval.domain.repository.EvalRunCommandRepository;
import com.objwww.pr.control.eval.domain.repository.EvalRunRepository;

import java.util.Map;
import java.util.Objects;

/**
 * EV-04 发起执行器（eval worker 侧）：LAUNCH 命令 → 元数据叠加 + 生命周期挂点 →
 * 复用 {@link EvalBatchRunner} 跑批（方案 EV-04 卡"EvalRunnerMain/Config/BatchRunner
 * 抽出可复用启动路径"的落点——HTTP/worker 与旧 CLI 共用同一编排器，不另造执行路径）。
 *
 * <p>元数据叠加纪律：datasetVersion 恒取计划值；model/promptVersion 计划缺省 =
 * 沿用 env 十项元数据（候选内容 digest 面本期仍由 env 提供，EV-09 资产注册接线前
 * 不从请求体接受 digest 自报——诚实边界）；roundsPerScenario 缺省 = 装配默认。
 * 取消信号 = eval_run_command 受理面直读（worker 检查点，案例边界生效）。
 */
public class EvalLaunchExecutor implements EvalRunWorker.LaunchExecutor {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final GoldenScenarioRegistry registry;
    private final Map<String, ScenarioDriver> driversByRole;
    private final AlertProbe alertProbe;
    private final IncidentResolutionProbe incidentProbe;
    private final RcaRunResolver rcaRunResolver;
    private final SingleCaseScorer scorer;
    private final EvalRunRepository evalRuns;
    private final BaselineReportGenerator reportGenerator;
    private final EvalPhaseEventSink phaseSink;
    private final EvalRunCommandRepository commands;
    private final EvalRunMetadata baseMetadata;
    private final int defaultRounds;
    private final EvalBatchRunner.EvalClock clock;
    private final String workerId;
    private final EvalLaunchGate gate;
    /** EV-07 终态自动落档钩子（可空 = 不装配自动落档，测试对照面） */
    private final EvalComparisonAutoRecorder autoRecorder;
    /** BA-190：启动方注入的 run-tag（env 静态值）；空 = runner 批开始按 evalRunId 派生 */
    private final String configuredRunTag;
    /** ME-T12b：预登记落库面（V165；null = 不登记，runner 侧 fail-soft） */
    private final EvalPreregistrationSink preregistrationSink;

    public EvalLaunchExecutor(GoldenScenarioRegistry registry,
                              Map<String, ScenarioDriver> driversByRole,
                              AlertProbe alertProbe,
                              IncidentResolutionProbe incidentProbe,
                              RcaRunResolver rcaRunResolver,
                              SingleCaseScorer scorer,
                              EvalRunRepository evalRuns,
                              BaselineReportGenerator reportGenerator,
                              EvalPhaseEventSink phaseSink,
                              EvalRunCommandRepository commands,
                              EvalRunMetadata baseMetadata,
                              int defaultRounds,
                              EvalBatchRunner.EvalClock clock,
                              String workerId,
                              EvalLaunchGate gate,
                              EvalComparisonAutoRecorder autoRecorder) {
        this(registry, driversByRole, alertProbe, incidentProbe, rcaRunResolver, scorer,
                evalRuns, reportGenerator, phaseSink, commands, baseMetadata, defaultRounds,
                clock, workerId, gate, autoRecorder, "");
    }

    /** BA-190 全参形态：configuredRunTag（可空/空串 = 空 tag 兜底派生生效） */
    public EvalLaunchExecutor(GoldenScenarioRegistry registry,
                              Map<String, ScenarioDriver> driversByRole,
                              AlertProbe alertProbe,
                              IncidentResolutionProbe incidentProbe,
                              RcaRunResolver rcaRunResolver,
                              SingleCaseScorer scorer,
                              EvalRunRepository evalRuns,
                              BaselineReportGenerator reportGenerator,
                              EvalPhaseEventSink phaseSink,
                              EvalRunCommandRepository commands,
                              EvalRunMetadata baseMetadata,
                              int defaultRounds,
                              EvalBatchRunner.EvalClock clock,
                              String workerId,
                              EvalLaunchGate gate,
                              EvalComparisonAutoRecorder autoRecorder,
                              String configuredRunTag) {
        this(registry, driversByRole, alertProbe, incidentProbe, rcaRunResolver, scorer,
                evalRuns, reportGenerator, phaseSink, commands, baseMetadata, defaultRounds,
                clock, workerId, gate, autoRecorder, configuredRunTag, null);
    }

    /** ME-T12b 全参形态：preregistrationSink = 预登记落库面（可空 = 不登记，runner
     *  fail-soft；验收面读不到登记时质量面如实 INCONCLUSIVE 不猜） */
    public EvalLaunchExecutor(GoldenScenarioRegistry registry,
                              Map<String, ScenarioDriver> driversByRole,
                              AlertProbe alertProbe,
                              IncidentResolutionProbe incidentProbe,
                              RcaRunResolver rcaRunResolver,
                              SingleCaseScorer scorer,
                              EvalRunRepository evalRuns,
                              BaselineReportGenerator reportGenerator,
                              EvalPhaseEventSink phaseSink,
                              EvalRunCommandRepository commands,
                              EvalRunMetadata baseMetadata,
                              int defaultRounds,
                              EvalBatchRunner.EvalClock clock,
                              String workerId,
                              EvalLaunchGate gate,
                              EvalComparisonAutoRecorder autoRecorder,
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
        this.phaseSink = Objects.requireNonNull(phaseSink);
        this.commands = Objects.requireNonNull(commands);
        this.baseMetadata = Objects.requireNonNull(baseMetadata);
        this.defaultRounds = defaultRounds;
        this.clock = Objects.requireNonNull(clock);
        this.workerId = Objects.requireNonNull(workerId);
        this.gate = Objects.requireNonNull(gate);
        this.autoRecorder = autoRecorder;
        this.configuredRunTag = configuredRunTag == null ? "" : configuredRunTag;
        this.preregistrationSink = preregistrationSink;
    }

    /**
     * 执行一条已领取的 LAUNCH 命令（run id = 命令预定身份）；异常上抛归 worker 收口。
     * 领取后先过能力闸门复验（PAGE-03：服务层校验不可信作唯一防线；不支持的
     * 模式/配置在此拒绝，零驱动装配、零注入端口触达）。P6-G8：panel 非空时执行
     * 注册表先过滤（forPanel——SMOKE 只跑 panel=true 子集），registryDigest 承自
     * 过滤后注册表，可复现元数据如实反映执行子集。FUP-03：launch_plan 快照注入
     * caseKeys（panel 展开后的有效场景键集）——EV-07 对比门的冻结计划分母由此
     * 获得注入场景真分母（数据集案例键集不含 YAML 场景）。
     */
    public EvalBatchRunner.BatchResult execute(EvalRunCommand command) {
        EvalLaunchPlan plan = parsePlan(command.payloadJson());
        gate.check(plan);
        EvalRunMetadata overlaid = overlay(baseMetadata, plan);
        com.objwww.pr.control.eval.domain.GoldenScenarioRegistry effective =
                registry.forPanel(plan.panel());
        java.util.List<String> caseKeys = effective.scenarios().stream()
                .map(GoldenCase::scenarioId).sorted().toList();
        EvalBatchRunner.RunLifecycle lifecycle = new EvalBatchRunner.RunLifecycle(
                plan.mode(), plan.displayName(),
                withCaseKeys(command.payloadJson(), caseKeys), workerId,
                phaseSink, commands::cancelAccepted,
                autoRecorder == null ? null : autoRecorder::onTerminal);
        return new EvalBatchRunner(effective, driversByRole, alertProbe, incidentProbe,
                rcaRunResolver, scorer, evalRuns, reportGenerator, overlaid,
                plan.roundsPerScenario() == null ? defaultRounds : plan.roundsPerScenario(),
                clock, configuredRunTag, preregistrationSink)
                .runBatch(command.evalRunId(), lifecycle);
    }

    /**
     * FUP-03：launch_plan 快照注入 caseKeys（有序去重场景键集）。payload 解析失败
     * 原样返回（快照增强失败不阻断跑批——门降级回数据集键集，行为与旧版一致）。
     */
    static String withCaseKeys(String payloadJson, java.util.List<String> caseKeys) {
        try {
            JsonNode node = JSON.readTree(payloadJson);
            if (node instanceof com.fasterxml.jackson.databind.node.ObjectNode obj) {
                obj.set("caseKeys", JSON.valueToTree(caseKeys.stream().distinct().sorted()
                        .toList()));
                return JSON.writeValueAsString(obj);
            }
            return payloadJson;
        } catch (Exception e) {
            return payloadJson;
        }
    }

    /** 命令 payload → 计划（worker 侧复验：落库前的服务层校验不可信作唯一防线） */
    static EvalLaunchPlan parsePlan(String payloadJson) {
        try {
            JsonNode node = JSON.readTree(payloadJson);
            return new EvalLaunchPlan(
                    text(node, "displayName"), text(node, "mode"),
                    text(node, "datasetVersion"), text(node, "model"),
                    text(node, "promptVersion"),
                    number(node, "budgetMaxTokens"), intNumber(node, "maxConcurrency"),
                    number(node, "deadlineSeconds"), intNumber(node, "roundsPerScenario"),
                    text(node, "panel"));
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("launch_plan 反序列化失败: " + e.getMessage(), e);
        }
    }

    private static EvalRunMetadata overlay(EvalRunMetadata base, EvalLaunchPlan plan) {
        return new EvalRunMetadata(base.schemaVersion(), plan.datasetVersion(),
                base.registryDigest(), base.lexiconVersion(),
                plan.model() == null ? base.model() : plan.model(),
                plan.promptVersion() == null ? base.promptVersion() : plan.promptVersion(),
                base.promptDigest(), base.toolRegistryDigest(), base.temperature(),
                base.topP(), base.maxTokens(), base.requestedSeed(), base.effectiveSeed(),
                base.providerFingerprint(), base.alertRuleDigest(),
                base.scenarioDriverVersion(), base.graderVersion());
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? null : value.asText();
    }

    private static Long number(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? null : value.asLong();
    }

    private static Integer intNumber(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? null : value.asInt();
    }
}
