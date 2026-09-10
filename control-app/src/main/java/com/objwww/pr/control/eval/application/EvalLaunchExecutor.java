package com.objwww.pr.control.eval.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.objwww.pr.control.eval.domain.EvalRunMetadata;
import com.objwww.pr.control.eval.domain.GoldenScenarioRegistry;
import com.objwww.pr.control.eval.domain.model.EvalLaunchPlan;
import com.objwww.pr.control.eval.domain.model.EvalRunCommand;
import com.objwww.pr.control.eval.domain.repository.EvalPhaseEventSink;
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
                              String workerId) {
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
    }

    /** 执行一条已领取的 LAUNCH 命令（run id = 命令预定身份）；异常上抛归 worker 收口 */
    public EvalBatchRunner.BatchResult execute(EvalRunCommand command) {
        EvalLaunchPlan plan = parsePlan(command.payloadJson());
        EvalRunMetadata overlaid = overlay(baseMetadata, plan);
        EvalBatchRunner.RunLifecycle lifecycle = new EvalBatchRunner.RunLifecycle(
                plan.mode(), plan.displayName(), command.payloadJson(), workerId,
                phaseSink, commands::cancelAccepted);
        return new EvalBatchRunner(registry, driversByRole, alertProbe, incidentProbe,
                rcaRunResolver, scorer, evalRuns, reportGenerator, overlaid,
                plan.roundsPerScenario() == null ? defaultRounds : plan.roundsPerScenario(),
                clock)
                .runBatch(command.evalRunId(), lifecycle);
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
                    number(node, "deadlineSeconds"), intNumber(node, "roundsPerScenario"));
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
                base.scenarioDriverVersion());
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
