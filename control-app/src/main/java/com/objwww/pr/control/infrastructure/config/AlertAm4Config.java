package com.objwww.pr.control.infrastructure.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.objwww.pr.control.alert.application.AlertClock;
import com.objwww.pr.control.alert.application.DagExecutionService;
import com.objwww.pr.control.alert.application.DeterministicSupervisor;
import com.objwww.pr.control.alert.application.PlanCompiler;
import com.objwww.pr.control.alert.application.agent.AgentRegistry;
import com.objwww.pr.control.alert.application.agent.ChangeAgent;
import com.objwww.pr.control.alert.application.agent.LogsAgent;
import com.objwww.pr.control.alert.application.agent.MetricsAgent;
import com.objwww.pr.control.alert.application.agent.NativeRcaAgent;
import com.objwww.pr.control.alert.application.replay.AgentReplayRunner;
import com.objwww.pr.control.alert.application.replay.ReadOnlyToolFace;
import com.objwww.pr.control.alert.application.replay.SnapshotShadowRouter;
import com.objwww.pr.control.alert.application.tool.ReplayToolGateway;
import com.objwww.pr.control.alert.application.tool.ToolGateway;
import com.objwww.pr.control.alert.application.tool.ToolInvoker;
import com.objwww.pr.control.alert.application.tool.ToolRegistry;
import com.objwww.pr.control.alert.domain.agent.AgentProfile;
import com.objwww.pr.control.alert.domain.budget.BudgetKind;
import com.objwww.pr.control.alert.domain.claim.ClaimReducer;
import com.objwww.pr.control.alert.domain.claim.ClaimStore;
import com.objwww.pr.control.alert.domain.evidence.EvidenceRepository;
import com.objwww.pr.control.alert.domain.repository.RcaRunRepository;
import com.objwww.pr.control.alert.domain.repository.RcaTaskRepository;
import com.objwww.pr.control.alert.domain.repository.TaskEdgeRepository;
import com.objwww.pr.control.alert.domain.tool.RcaToolInvocationLedger;
import com.objwww.pr.control.alert.domain.tool.ToolPolicy;
import com.objwww.pr.control.infrastructure.persistence.PostgresToolReplayStore;
import com.objwww.pr.control.infrastructure.tool.ChangeQueryExecutor;
import com.objwww.pr.control.infrastructure.tool.PrometheusQueryExecutor;
import com.objwww.pr.control.infrastructure.tool.LogQueryExecutor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionOperations;

import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;

/**
 * AM4 Native 影子链装配（docker profile 手工装配，同 {@link AlertFlowConfig} 惯例）：
 * Supervisor/AgentRegistry/三 Agent/影子工具面/回放面/Claim 归并全部 bean 化，
 * E2E-M4-08（REPLAY_ONLY 回放对拍）与 E2E-M4-09（Online Read Shadow）由 195 部署
 * 配方按组件公开入口组装触发（触发器设计留白 = G2 终裁开放项，本装配不发明入口）。
 *
 * <p>装配纪律（AM4 技术方案 §2）：
 * <ul>
 *   <li>影子面与生产主链物理隔离：三 Agent 的工具出口只绑影子只读工具面
 *       （仅 R0/R1 + REDTEAM 物理禁入 + 独立 slot/限流），Holmes 主链零改动；</li>
 *   <li>回放面（{@link AgentReplayRunner}）为 E2E-M4-08 专用：账本落 V19
 *       rca_tool_replay，MISS 绝不降级活执行；</li>
 *   <li>Logs/Change 无冻结实时源（评审 P0-7）：fixture 即数据面（classpath 冻结
 *       样本，E2E 冻结时以带 content digest 的 manifest 替换）；</li>
 *   <li>不切主：本装配零报告零发布出口（Candidate 增量 0 由 E2E-M4-09 断言兜底）。</li>
 * </ul>
 *
 * @author wanghua
 * @date 2026-09-05
 */
@Configuration
@Profile("docker")
public class AlertAm4Config {

    private static final String PROMETHEUS_BASE_URL_KEY =
            "${app.alert.am4.prometheus.base-url:http://prometheus:9090}";
    /** EX-B2：logs 真实源（Loki 试验资源，契约 §3 准入条件）；fixture 退役
     * （Phase 3 门第 1 条：生产 profile 零 Replay 挂点——logs/change 全真源） */
    private static final String LOGS_LOKI_BASE_URL_KEY =
            "${app.alert.am4.logs.loki.base-url:http://loki:3100}";
    private static final String LOGS_SERVICE_ALLOWLIST_KEY =
            "${app.alert.am4.logs.service-allowlist:control-app,checkout,frontend,recommendation}";
    /** EX-B1：change fixture 已迁 test 资源（生产镜像零 change 假件，P1-03） */
    static final String CHANGE_FIXTURE_CLASSPATH = "am4/fixtures/change-query.json";
    /** EX-B1 change.query 服务白名单（越出 = INVALID_ARGS；缺省仅自身，fail-closed） */
    private static final String CHANGE_SERVICE_ALLOWLIST_KEY =
            "${app.alert.am4.change.service-allowlist:control-app}";
    private static final String ALLOWED_TOOLS_KEY =
            "${app.alert.am4.allowed-tools:prometheus.query,logs.query,change.query}";
    private static final String SHADOW_MAX_CALLS_KEY =
            "${app.alert.am4.shadow.max-calls-per-window:60}";
    private static final String SHADOW_WINDOW_MILLIS_KEY =
            "${app.alert.am4.shadow.window-millis:60000}";
    private static final String SHADOW_POOL_SIZE_KEY =
            "${app.alert.am4.shadow.pool-size:2}";
    private static final String TOOL_TIMEOUT_KEY =
            "${app.alert.am4.tool.timeout-millis:4000}";
    private static final String TOOL_RESULT_LIMIT_KEY =
            "${app.alert.am4.tool.result-limit-bytes:65536}";
    private static final String PROMPT_VERSION_KEY =
            "${app.alert.am4.agent.prompt-version:am4-native-v1}";
    private static final String BUDGET_STEP_KEY = "${app.alert.am4.budget.step:8}";
    private static final String BUDGET_TOOL_CALLS_KEY =
            "${app.alert.am4.budget.tool-calls:4}";
    private static final String BUDGET_EVIDENCES_KEY =
            "${app.alert.am4.budget.evidences:8}";
    private static final String BUDGET_SUBTASKS_KEY =
            "${app.alert.am4.budget.subtasks:1}";
    /** EX-A1 熔断阈值（签名级连续无进展；≤0 回退 5） */
    private static final String DOOM_MAX_NO_PROGRESS_KEY =
            "${app.alert.am4.doom-loop.max-consecutive-no-progress:5}";
    private static final String DOOM_POLICY_VERSION = "am4-doom-v1";
    private static final String REDUCER_ALLOWLIST_KEY =
            "${app.alert.am4.reducer.readonly-allowlist:holmes,prometheus}";
    private static final String REDUCER_POLICY_VERSION_KEY =
            "${app.alert.am4.reducer.policy-version:am4-g2-policy}";

    private static final String OUTPUT_SCHEMA_TYPE = "type";
    private static final String OUTPUT_SCHEMA_OBJECT = "object";
    private static final String AGENT_VERSION = "1";
    private static final long SHADOW_WINDOW_MILLIS_DEFAULT = 60_000L;
    private static final long SHADOW_MAX_CALLS_DEFAULT = 60L;
    private static final int SHADOW_POOL_SIZE_DEFAULT = 2;
    /** EX-A4a（F17）bulkhead 队列容量（满即 Abort 拒绝，不静默排队） */
    private static final int SHADOW_QUEUE_CAPACITY = 16;

    // ------------------------------------------------------------------ 工具面

    /**
     * EX-A1 预算门（F15）：全系统唯一预算所有者的 bean 装配——消费点为三 Agent 的
     * TOOL_CALL 硬闸与 executor 的 run 开局限额（openRun）。账本本体在
     * PersistenceConfig（V13）。
     */
    @Bean
    public com.objwww.pr.control.alert.application.RunBudgetGate runBudgetGate(
            com.objwww.pr.control.alert.domain.budget.RunBudgetLedger runBudgetLedger) {
        return new com.objwww.pr.control.alert.application.RunBudgetGate(runBudgetLedger);
    }

    /** EX-A1 熔断门：签名级连续无进展熔断（粘滞，人工/新代际解除；轮询豁免集空） */
    @Bean
    public com.objwww.pr.control.alert.domain.budget.DoomLoopGuard am4DoomLoopGuard(
            @Value(DOOM_MAX_NO_PROGRESS_KEY) long maxConsecutiveNoProgress) {
        long threshold = maxConsecutiveNoProgress > 0 ? maxConsecutiveNoProgress : 5L;
        return new com.objwww.pr.control.alert.domain.budget.DoomLoopGuard(
                new com.objwww.pr.control.alert.domain.budget.DoomLoopGuard.Policy(
                        threshold, DOOM_POLICY_VERSION, java.util.Set.of()));
    }

    /** EX-A1 run 开局限额四维（既有 budget.* 键；openRun 逐维幂等 upsert） */
    @Bean
    public java.util.Map<com.objwww.pr.control.alert.domain.budget.BudgetKind, Long>
            am4BudgetLimits(@Value(BUDGET_STEP_KEY) long budgetStep,
                    @Value(BUDGET_TOOL_CALLS_KEY) long budgetToolCalls,
                    @Value(BUDGET_EVIDENCES_KEY) long budgetEvidences,
                    @Value(BUDGET_SUBTASKS_KEY) long budgetSubtasks) {
        java.util.Map<com.objwww.pr.control.alert.domain.budget.BudgetKind, Long> limits =
                new java.util.LinkedHashMap<>();
        limits.put(com.objwww.pr.control.alert.domain.budget.BudgetKind.STEP, budgetStep);
        limits.put(com.objwww.pr.control.alert.domain.budget.BudgetKind.TOOL_CALL, budgetToolCalls);
        limits.put(com.objwww.pr.control.alert.domain.budget.BudgetKind.EVIDENCE, budgetEvidences);
        limits.put(com.objwww.pr.control.alert.domain.budget.BudgetKind.SUBTASK, budgetSubtasks);
        return limits;
    }

    /**
     * 生产工具注册面（EX-B2 后全真源，Phase 3 门第 1 条达成）：prometheus.query 真查 +
     * logs.query 真 Loki（试验资源，盘点门签字后换绑）+ change.query 真实变更源
     * （EX-B1 换绑：V40 change_event 只读查询）——零 ReplayToolExecutor 挂点。
     */
    @Bean
    public ToolRegistry am4ToolRegistry(
            @Value(PROMETHEUS_BASE_URL_KEY) String prometheusBaseUrl,
            @Value(TOOL_TIMEOUT_KEY) long timeoutMillis,
            @Value(TOOL_RESULT_LIMIT_KEY) long resultLimitBytes,
            JdbcClient jdbc,
            @Value(LOGS_LOKI_BASE_URL_KEY) String lokiBaseUrl,
            @Value(LOGS_SERVICE_ALLOWLIST_KEY) String logsServiceAllowlist,
            @Value(CHANGE_SERVICE_ALLOWLIST_KEY) String changeServiceAllowlist) {
        return new ToolRegistry(List.of(
                new ToolRegistry.Registration(
                        MetricsAgent.toolDefinition(timeoutMillis, resultLimitBytes),
                        new PrometheusQueryExecutor(prometheusBaseUrl)),
                new ToolRegistry.Registration(
                        LogsAgent.toolDefinition(timeoutMillis, resultLimitBytes),
                        new LogQueryExecutor(lokiBaseUrl,
                                Set.of(logsServiceAllowlist.split(",")))),
                new ToolRegistry.Registration(
                        ChangeAgent.toolDefinition(timeoutMillis, resultLimitBytes),
                        new ChangeQueryExecutor(jdbc,
                                Set.of(changeServiceAllowlist.split(","))))));
    }

    /** 工具策略（M4-16）：空策略硬失败在 ToolPolicy 构造期兜底 */
    @Bean
    public ToolPolicy am4ToolPolicy(
            @Value(ALLOWED_TOOLS_KEY) String allowedTools) {
        return new ToolPolicy(Set.of(allowedTools.split(",")));
    }

    /** V19 精确回放账本（M4-32 的 PG 面） */
    @Bean
    public PostgresToolReplayStore am4ToolReplayStore(JdbcClient jdbc) {
        return new PostgresToolReplayStore(jdbc);
    }

    /** 回放网关（M4-32）：镜像活网关纪律，MISS 绝不降级活执行 */
    @Bean
    public ReplayToolGateway am4ReplayGateway(ToolRegistry am4ToolRegistry,
            PostgresToolReplayStore am4ToolReplayStore) {
        return new ReplayToolGateway(am4ToolRegistry, am4ToolReplayStore);
    }

    /** 回放 runner（M4-33）：E2E-M4-08 回放链的工具出口（覆盖率/Token 申报面） */
    @Bean
    public AgentReplayRunner am4ReplayRunner(ReplayToolGateway am4ReplayGateway) {
        return new AgentReplayRunner(am4ReplayGateway);
    }

    /**
     * 影子面独立调用池（独立并发槽；容器关停时回收）。
     * EX-A4a（F17）bulkhead：FixedThreadPool 的无界队列换 ArrayBlockingQueue(16)
     * + Abort——满即拒绝，Gateway 显式映射模型可见族背压文案，不静默排队。
     */
    @Bean(destroyMethod = "shutdown")
    public ExecutorService am4ShadowPool(
            @Value(SHADOW_POOL_SIZE_KEY) int poolSize) {
        int threads = poolSize > 0 ? poolSize : SHADOW_POOL_SIZE_DEFAULT;
        java.util.concurrent.ThreadPoolExecutor pool = new java.util.concurrent.ThreadPoolExecutor(
                threads, threads, 0L, java.util.concurrent.TimeUnit.MILLISECONDS,
                new java.util.concurrent.ArrayBlockingQueue<>(SHADOW_QUEUE_CAPACITY),
                new java.util.concurrent.ThreadPoolExecutor.AbortPolicy());
        pool.allowCoreThreadTimeOut(false);
        return pool;
    }

    /**
     * 影子在线只读工具面（M4-35 → M6-01 落点 ⑦）：真实类型 ReadOnlyToolFace。
     * redteamOnly=false = canary 期策略位（REDTEAM 双闸从结构强制降为策略开关，
     * 落点 ③；装配硬接线，bundle 化归 M6-03）——R0/R1 裁剪/独立池/限流/预算门不变。
     */
    @Bean
    public ReadOnlyToolFace am4ShadowToolFace(ToolRegistry am4ToolRegistry,
            ToolPolicy am4ToolPolicy, ExecutorService am4ShadowPool,
            @Value(SHADOW_MAX_CALLS_KEY) long maxCallsPerWindow,
            @Value(SHADOW_WINDOW_MILLIS_KEY) long windowMillis) {
        long calls = maxCallsPerWindow > 0 ? maxCallsPerWindow : SHADOW_MAX_CALLS_DEFAULT;
        long window = windowMillis > 0 ? windowMillis : SHADOW_WINDOW_MILLIS_DEFAULT;
        return new ReadOnlyToolFace(am4ToolRegistry, am4ToolPolicy, am4ShadowPool,
                calls, window, Clock.systemUTC(), false);
    }

    /** 影子对照路由器（M4-34）：同 digest 盖章/独立预算/失败隔离，无发布出口 */
    @Bean
    public SnapshotShadowRouter am4ShadowRouter() {
        return new SnapshotShadowRouter();
    }

    // ------------------------------------------------------------------ Agent 面

    /** Agent 注册表（M4-24）：三固定 Agent，启动期 fail-fast，运行期不可生 */
    @Bean
    public AgentRegistry am4AgentRegistry(
            @Value(PROMPT_VERSION_KEY) String promptVersion,
            @Value(BUDGET_STEP_KEY) long budgetStep,
            @Value(BUDGET_TOOL_CALLS_KEY) long budgetToolCalls,
            @Value(BUDGET_EVIDENCES_KEY) long budgetEvidences,
            @Value(BUDGET_SUBTASKS_KEY) long budgetSubtasks) {
        Map<BudgetKind, Long> budgetLimits = new LinkedHashMap<>();
        budgetLimits.put(BudgetKind.STEP, budgetStep);
        budgetLimits.put(BudgetKind.TOOL_CALL, budgetToolCalls);
        budgetLimits.put(BudgetKind.EVIDENCE, budgetEvidences);
        budgetLimits.put(BudgetKind.SUBTASK, budgetSubtasks);
        Map<String, Object> outputSchema = Map.of(OUTPUT_SCHEMA_TYPE, OUTPUT_SCHEMA_OBJECT);
        AgentProfile metrics = new AgentProfile("metrics", AGENT_VERSION,
                "native-metrics", promptVersion,
                Set.of(MetricsAgent.TOOL_NAME), budgetLimits, outputSchema);
        AgentProfile logs = new AgentProfile("logs", AGENT_VERSION,
                "native-logs", promptVersion,
                Set.of(LogsAgent.TOOL_NAME), budgetLimits, outputSchema);
        AgentProfile change = new AgentProfile("change", AGENT_VERSION,
                "native-change", promptVersion,
                Set.of(ChangeAgent.TOOL_NAME), budgetLimits, outputSchema);
        return new AgentRegistry(List.of(metrics, logs, change));
    }

    /** Metrics Agent（在线影子形态：工具出口 = 影子面；EX-A1 全参=预算门+熔断门） */
    @Bean
    public MetricsAgent am4MetricsAgent(AgentRegistry am4AgentRegistry,
            ReadOnlyToolFace am4ShadowToolFace, EvidenceRepository evidenceRepository,
            RcaToolInvocationLedger rcaToolInvocationLedger, ObjectMapper objectMapper,
            com.objwww.pr.control.alert.application.RunBudgetGate runBudgetGate,
            com.objwww.pr.control.alert.domain.budget.DoomLoopGuard am4DoomLoopGuard) {
        return new MetricsAgent(am4AgentRegistry.require("metrics", AGENT_VERSION),
                am4ShadowToolFace.readOnlyView(), am4ShadowToolFace,
                evidenceRepository, rcaToolInvocationLedger, objectMapper,
                runBudgetGate, am4DoomLoopGuard);
    }

    /** Logs Agent（在线影子形态） */
    @Bean
    public LogsAgent am4LogsAgent(AgentRegistry am4AgentRegistry,
            ReadOnlyToolFace am4ShadowToolFace, EvidenceRepository evidenceRepository,
            RcaToolInvocationLedger rcaToolInvocationLedger, ObjectMapper objectMapper,
            com.objwww.pr.control.alert.application.RunBudgetGate runBudgetGate,
            com.objwww.pr.control.alert.domain.budget.DoomLoopGuard am4DoomLoopGuard) {
        return new LogsAgent(am4AgentRegistry.require("logs", AGENT_VERSION),
                am4ShadowToolFace.readOnlyView(), am4ShadowToolFace,
                evidenceRepository, rcaToolInvocationLedger, objectMapper,
                runBudgetGate, am4DoomLoopGuard);
    }

    /** Change Agent（在线影子形态） */
    @Bean
    public ChangeAgent am4ChangeAgent(AgentRegistry am4AgentRegistry,
            ReadOnlyToolFace am4ShadowToolFace, EvidenceRepository evidenceRepository,
            RcaToolInvocationLedger rcaToolInvocationLedger, ObjectMapper objectMapper,
            com.objwww.pr.control.alert.application.RunBudgetGate runBudgetGate,
            com.objwww.pr.control.alert.domain.budget.DoomLoopGuard am4DoomLoopGuard) {
        return new ChangeAgent(am4AgentRegistry.require("change", AGENT_VERSION),
                am4ShadowToolFace.readOnlyView(), am4ShadowToolFace,
                evidenceRepository, rcaToolInvocationLedger, objectMapper,
                runBudgetGate, am4DoomLoopGuard);
    }

    /** Native RCA Agent（M4-30）：消费结构化黑板出 Claim，不直接发布报告；EX-A4a（F05）黑板=冻结快照成员 */
    @Bean
    public NativeRcaAgent am4NativeRcaAgent(EvidenceRepository evidenceRepository,
            com.objwww.pr.control.alert.domain.evidence.EvidenceSnapshotRepository
                    evidenceSnapshotRepository,
            ClaimStore claimStore, ClaimReducer am4ClaimReducer) {
        return new NativeRcaAgent(evidenceRepository, evidenceSnapshotRepository,
                claimStore, am4ClaimReducer);
    }

    /** Claim 归并（M4-22）：规则驱动消重/冲突/覆盖，降级续跑白名单配置化 */
    @Bean
    public ClaimReducer am4ClaimReducer(
            @Value(REDUCER_ALLOWLIST_KEY) String allowlist,
            @Value(REDUCER_POLICY_VERSION_KEY) String policyVersion) {
        return new ClaimReducer(Set.of(allowlist.split(",")), policyVersion);
    }

    // ------------------------------------------------------------------ DAG 面

    /** 确定性 Supervisor（M4-25/26）：模型无调度权，恢复入口只有 advance */
    @Bean
    public DeterministicSupervisor am4DeterministicSupervisor(
            AgentRegistry am4AgentRegistry,
            RcaTaskRepository rcaTaskRepository,
            TaskEdgeRepository taskEdgeRepository,
            RcaRunRepository rcaRunRepository,
            TransactionOperations tx,
            DagExecutionService dagExecutionService) {
        PlanCompiler compiler = new PlanCompiler(am4AgentRegistry, rcaTaskRepository,
                taskEdgeRepository, tx);
        return new DeterministicSupervisor(compiler, dagExecutionService,
                rcaRunRepository, rcaTaskRepository, tx, AlertClock.system());
    }

    // ------------------------------------------------------------------ 内部
}
