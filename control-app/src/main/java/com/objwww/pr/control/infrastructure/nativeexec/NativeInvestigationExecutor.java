package com.objwww.pr.control.infrastructure.nativeexec;

import com.objwww.pr.control.alert.application.AlertClock;
import com.objwww.pr.control.alert.application.DeterministicSupervisor;
import com.objwww.pr.control.alert.application.NativeReportAdapter;
import com.objwww.pr.control.alert.application.RcaTaskExecutor;
import com.objwww.pr.control.alert.application.agent.AgentRegistry;
import com.objwww.pr.control.alert.application.agent.NativeRcaAgent;
import com.objwww.pr.control.alert.application.agent.PrimaryFinalClaimProjector;
import com.objwww.pr.control.alert.application.agent.RoleRunner;
import com.objwww.pr.control.alert.application.agent.RunnerDirectory;
import com.objwww.pr.control.alert.application.agent.SingleToolEvidenceAgent;
import com.objwww.pr.control.alert.domain.agent.AgentProfile;
import com.objwww.pr.control.alert.domain.agent.PrimaryCheckpoint;
import com.objwww.pr.control.alert.domain.agent.RoleRuntimeKind;
import com.objwww.pr.control.alert.domain.claim.ClaimLifecycle;
import com.objwww.pr.control.alert.domain.claim.ClaimStore;
import com.objwww.pr.control.alert.domain.claim.ClaimVerdict;
import com.objwww.pr.control.alert.domain.claim.ReportAssembler;
import com.objwww.pr.control.alert.domain.evidence.EvidenceEnvelope;
import com.objwww.pr.control.alert.domain.evidence.EvidenceRepository;
import com.objwww.pr.control.alert.domain.evidence.EvidenceSnapshotBuilder;
import com.objwww.pr.control.alert.domain.evidence.EvidenceSnapshotRepository;
import com.objwww.pr.control.alert.domain.model.EvidencePackageV2;
import com.objwww.pr.control.alert.domain.model.Incident;
import com.objwww.pr.control.alert.domain.model.RcaAttempt;
import com.objwww.pr.control.alert.domain.model.RcaRun;
import com.objwww.pr.control.alert.domain.model.RcaRunState;
import com.objwww.pr.control.alert.domain.model.RcaTask;
import com.objwww.pr.control.alert.domain.model.RcaTaskState;
import com.objwww.pr.control.alert.domain.model.TaskExecutionBinding;
import com.objwww.pr.control.alert.domain.model.ValidationStatus;
import com.objwww.pr.control.alert.domain.identity.ConfigDigest;
import com.objwww.pr.control.alert.domain.identity.EvidenceSnapshotDigest;
import com.objwww.pr.control.alert.domain.identity.InvestigationInputDigest;
import com.objwww.pr.control.alert.domain.identity.InvestigationInputs;
import com.objwww.pr.control.alert.domain.repository.RcaRunRepository;
import com.objwww.pr.control.alert.domain.repository.RcaTaskRepository;
import com.objwww.pr.control.alert.domain.repository.PrimaryCheckpointRepository;
import com.objwww.pr.control.alert.domain.repository.TaskExecutionBindingRepository;
import com.objwww.pr.control.alert.domain.service.EvidencePackageValidator;
import com.objwww.pr.control.alert.domain.tool.ToolControlPlaneException;
import com.objwww.pr.control.infrastructure.observability.AlertMetrics;
import com.objwww.pr.control.release.domain.model.ConfigBundle;
import com.objwww.pr.control.release.domain.repository.ConfigBundleRepository;
import com.objwww.pr.shared.Digest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Native 调查执行器（M6-01 落点 5，驱动模型）：worker 领取
 * {@code NATIVE_INVESTIGATE} driver task 后由本类驱动 Native 全链——
 * 提案（Run 固定路由 digest 的 bundle {@code native.proposal} 段；EN-03 准入固定：
 * drive 期不读 active，指针移动不改在跑 Run 的提案源；缺失/非法 fail-closed，模型无
 * 调度权 INV-AM4-2 顺延）→ {@link DeterministicSupervisor#startRun} 落图 →
 * DAG 调查任务逐个驱动（复用 AM4 三 Agent 与只读工具面；单任务失败 = 缺源降级
 * DEAD 续跑；EX-A3 F08/F09 四阶段恢复：READY 正常驱动，LEASED/RUNNING 崩溃孤儿
 * 领养分诊——已提交跳过/结果落库幂等收尾/发送后未知 UNKNOWN+新物理请求重驱/
 * FAILED 回执降级 DEAD，无永久 RUNNING）→ 冻结证据快照（configDigest = 路由 bundle digest，
 * Run 启动固定）→ advance 入 REPORTING → {@link NativeRcaAgent} 产 Claim →
 * {@link ReportAssembler} 组装 → {@link NativeReportAdapter} 适配 →
 * {@link ExecutionResult#success} 交 worker 复用 finishTask 收尾链落报告/发布
 * （FUT-49 共用出口，本类不直接接触报告发布 SQL）。
 *
 * <p>纪律：DAG 任务不经 claimNext 领取（driver task 独占 run 执行权，通用领取面被
 * task_key 过滤挡住，C-70）；适配包必须过自家验证链，REJECTED_* 同权落档
 * （INV-AM3-7）；PROPOSAL_REJECTED 例外：Supervisor 已置 run FAILED，finishTask
 * 走 STALE fence 分支诚实收敛。
 *
 * @author wanghua
 * @date 2026-09-08
 */
public class NativeInvestigationExecutor implements RcaTaskExecutor {

    private static final Logger log = LoggerFactory.getLogger(NativeInvestigationExecutor.class);

    /** 固定提案任务键（与 Am4ShadowTrigger 冻结提案同值；PlanCompiler 落图后按 key 对位） */
    public static final String TASK_METRICS = "investigate-metrics";
    public static final String TASK_LOGS = "investigate-logs";
    public static final String TASK_CHANGE = "investigate-change";

    private static final String NATIVE_SECTION = "native";
    private static final String PROPOSAL_KEY = "proposal";
    private static final String MODEL = "native-deterministic-v1";
    /**
     * 不动点 sweep 收敛上限（R7-X6 防御面）：旧兼容路由一轮收敛；主模式最多
     * 委派批数 2 轮 + 最终轮。8 = 大裕度，超出即诚实终态失败，不自旋。
     */
    private static final int MAX_DRIVE_SWEEPS = 8;

    private final ConfigBundleRepository bundles;
    private final DeterministicSupervisor supervisor;
    private final RcaTaskRepository tasks;
    private final RcaRunRepository runs;
    private final EvidenceRepository evidence;
    private final EvidenceSnapshotRepository snapshots;
    private final NativeRcaAgent nativeRcaAgent;
    private final ClaimStore claims;
    private final EvidencePackageValidator validator;
    private final AlertMetrics metrics;
    private final String toolRegistryDigest;
    private final AlertClock clock;
    private final com.objwww.pr.control.alert.application.RunBudgetGate budgetGate;
    private final Map<com.objwww.pr.control.alert.domain.budget.BudgetKind, Long> budgetLimits;
    private final com.objwww.pr.control.alert.domain.tool.RcaToolInvocationLedger toolLedger;
    private final TaskExecutionBindingRepository bindings;
    private final AgentRegistry agents;
    private final RunnerDirectory runners;
    private final PrimaryCheckpointRepository checkpoints;
    private final com.objwww.pr.control.alert.domain.agent.RcaModelCallLedger modelCalls;
    /** R7-X6：主模式 Profile（null = 主模式关闭，旧兼容路由行为零变化） */
    private final AgentProfile primaryProfile;
    /** MC21 回执准入面（null = legacy 装配，不生产回执，行为零变化） */
    private final com.objwww.pr.control.alert.application.agent.DelegationReceiptService
            delegationReceipts;

    public NativeInvestigationExecutor(ConfigBundleRepository bundles,
            DeterministicSupervisor supervisor, RcaTaskRepository tasks,
            RcaRunRepository runs, EvidenceRepository evidence,
            EvidenceSnapshotRepository snapshots, NativeRcaAgent nativeRcaAgent,
            ClaimStore claims, EvidencePackageValidator validator,
            String toolRegistryDigest, AlertClock clock,
            AlertMetrics metrics,
            com.objwww.pr.control.alert.application.RunBudgetGate budgetGate,
            Map<com.objwww.pr.control.alert.domain.budget.BudgetKind, Long> budgetLimits,
            com.objwww.pr.control.alert.domain.tool.RcaToolInvocationLedger toolLedger,
            TaskExecutionBindingRepository bindings, AgentRegistry agents,
            RunnerDirectory runners, PrimaryCheckpointRepository checkpoints,
            com.objwww.pr.control.alert.domain.agent.RcaModelCallLedger modelCalls,
            AgentProfile primaryProfile) {
        this(bundles, supervisor, tasks, runs, evidence, snapshots, nativeRcaAgent,
                claims, validator, toolRegistryDigest, clock, metrics, budgetGate,
                budgetLimits, toolLedger, bindings, agents, runners, checkpoints,
                modelCalls, primaryProfile, null);
    }

    /** 全参构造（MC21：回执生产面并集；delegationReceipts 可空=legacy 姿态） */
    public NativeInvestigationExecutor(ConfigBundleRepository bundles,
            DeterministicSupervisor supervisor, RcaTaskRepository tasks,
            RcaRunRepository runs, EvidenceRepository evidence,
            EvidenceSnapshotRepository snapshots, NativeRcaAgent nativeRcaAgent,
            ClaimStore claims, EvidencePackageValidator validator,
            String toolRegistryDigest, AlertClock clock,
            AlertMetrics metrics,
            com.objwww.pr.control.alert.application.RunBudgetGate budgetGate,
            Map<com.objwww.pr.control.alert.domain.budget.BudgetKind, Long> budgetLimits,
            com.objwww.pr.control.alert.domain.tool.RcaToolInvocationLedger toolLedger,
            TaskExecutionBindingRepository bindings, AgentRegistry agents,
            RunnerDirectory runners, PrimaryCheckpointRepository checkpoints,
            com.objwww.pr.control.alert.domain.agent.RcaModelCallLedger modelCalls,
            AgentProfile primaryProfile,
            com.objwww.pr.control.alert.application.agent.DelegationReceiptService
                    delegationReceipts) {
        this.bundles = Objects.requireNonNull(bundles, "bundles");
        this.supervisor = Objects.requireNonNull(supervisor, "supervisor");
        this.tasks = Objects.requireNonNull(tasks, "tasks");
        this.runs = Objects.requireNonNull(runs, "runs");
        this.evidence = Objects.requireNonNull(evidence, "evidence");
        this.snapshots = Objects.requireNonNull(snapshots, "snapshots");
        this.nativeRcaAgent = Objects.requireNonNull(nativeRcaAgent, "nativeRcaAgent");
        this.claims = Objects.requireNonNull(claims, "claims");
        this.validator = Objects.requireNonNull(validator, "validator");
        if (toolRegistryDigest == null || toolRegistryDigest.isBlank()) {
            throw new IllegalArgumentException("toolRegistryDigest 不得为空（快照身份面）");
        }
        this.toolRegistryDigest = toolRegistryDigest;
        this.clock = Objects.requireNonNull(clock, "clock");
        this.metrics = Objects.requireNonNull(metrics, "metrics");
        // EX-A1：全系统唯一预算所有者的 run 开局准入面（F15；agent 侧 TOOL_CALL 硬闸）
        this.budgetGate = Objects.requireNonNull(budgetGate, "budgetGate");
        this.budgetLimits = Objects.requireNonNull(budgetLimits, "budgetLimits");
        // EX-A3（F08/F09）：恢复分诊的 checkpoint 读面（账本=P1-03 持久事实源）
        this.toolLedger = Objects.requireNonNull(toolLedger, "toolLedger");
        // R7-X2：分派面 = 持久绑定.roleId + Profile.runtime_kind（运行器目录注入）
        this.bindings = Objects.requireNonNull(bindings, "bindings");
        this.agents = Objects.requireNonNull(agents, "agents");
        this.runners = Objects.requireNonNull(runners, "runners");
        // R7-X6：主模式 FINAL 提案的报告相位消费面（检查点读回）
        this.checkpoints = Objects.requireNonNull(checkpoints, "checkpoints");
        // R6/EV-06：主模式终态化 usage_json 回填源（rca_model_call attempt 聚合）
        this.modelCalls = Objects.requireNonNull(modelCalls, "modelCalls");
        this.primaryProfile = primaryProfile;
        this.delegationReceipts = delegationReceipts;
    }

    @Override
    public ExecutionResult execute(RcaTask task, RcaRun run, Incident incident,
            RcaAttempt attempt, Runnable heartbeat) {
        long beginNanos = System.nanoTime();
        // ① 身份/提案源 fail-closed：无路由 digest = 快照身份面缺失；无提案段 = 终态失败
        // （EX-A0 F04：configDigest 只作配置身份；输入身份/时间窗从 Run 冻结列读）
        RcaRunRepository.RoutingView routing =
                runs.findRoutingById(run.id()).orElse(null);
        if (routing == null || routing.configDigest() == null
                || routing.configDigest().isBlank()) {
            return ExecutionResult.terminal("CONFIG_DIGEST_MISSING",
                    "NATIVE run 无路由 configDigest（快照身份面缺失）: " + run.id());
        }
        ConfigDigest configDigest = new ConfigDigest(routing.configDigest());
        InvestigationInputDigest inputDigest = routing.investigationInputDigest() == null
                ? null : new InvestigationInputDigest(routing.investigationInputDigest());
        boolean frozenWindow = routing.windowStart() != null && routing.windowEnd() != null;
        if (inputDigest != null && !frozenWindow) {
            return ExecutionResult.terminal("INVESTIGATION_IDENTITY_INCONSISTENT",
                    "run 冻结输入身份在場而时间窗缺失（铸造面损坏）: " + run.id());
        }

        // EX-A1 F15：run 开局限额一次落账（幂等；消费点=agent 的 TOOL_CALL 硬闸）
        // R7-X6 真窗修复（195 实证 BA 卡）：主模式模型调用走 TOKEN 维预留，限额面必须
        // 并入 primaryProfile.budgetLimits（TOKEN=主模式新增维）。putIfAbsent 语义 =
        // 旧四维限额值逐字节不变，只补主模式新增维；InMemory 账本缺行放行 / PG 缺行
        // fail-closed 的假件语义分叉已登记（CI 绿掩盖本面根因）。
        Map<com.objwww.pr.control.alert.domain.budget.BudgetKind, Long> openLimits = budgetLimits;
        if (primaryProfile != null) {
            openLimits = new LinkedHashMap<>(budgetLimits);
            primaryProfile.budgetLimits().forEach(openLimits::putIfAbsent);
        }
        budgetGate.openRun(run.id(), openLimits);
        // ② 启动（幂等）：R7-X6 主模式（primaryProfile 在场）只编译主节点——提案段
        // 非主模式编译源；旧兼容路由 = Run 固定 digest 的 native.proposal 段三角色 DAG
        // （EN-03 准入固定：drive 期按铸造时路由 digest 读，不读 active——指针移动
        // 不改在跑 Run 的提案源，S11/P09/R07）
        DeterministicSupervisor.StartResult started;
        if (primaryProfile != null) {
            started = supervisor.startPrimary(run.id(), primaryProfile, Set.of());
        } else {
            Optional<Map<String, Object>> proposal = proposalOf(configDigest);
            if (proposal.isEmpty()) {
                return ExecutionResult.terminal("PROPOSAL_MISSING",
                        "路由 bundle 缺 native.proposal 段（fail-closed，模型无调度权，"
                                + "EN-03 按 Run 固定 digest 读、不读 active）: "
                                + configDigest.hex());
            }
            started = supervisor.startRun(run.id(), proposal.get(), Set.of());
        }
        if (started.outcome() == DeterministicSupervisor.StartOutcome.PROPOSAL_REJECTED) {
            return ExecutionResult.terminal("PROPOSAL_REJECTED", started.rejectReason());
        }
        heartbeat.run();

        // ③④ DAG 驱动（不动点 sweep：主模式委派批会中途生长子任务）+ 冻结证据快照
        long generation = run.generation();
        String timeRange = investigate(run.id(), attempt.id(), generation, inputDigest,
                routing, heartbeat);
        EvidenceSnapshotDigest snapshotDigest =
                freezeSnapshot(run.id(), generation, configDigest.hex());

        // ⑤ 推进入 REPORTING（全任务终态；未进入 = 链面异常，终态失败不产报告）
        supervisor.advance(run.id());
        RcaRunState state = runs.findById(run.id()).map(RcaRun::state).orElse(null);
        if (state != RcaRunState.REPORTING) {
            return ExecutionResult.terminal("REPORTING_NOT_ENTERED",
                    "advance 后 run 未入 REPORTING（state=" + state + "，DAG 未收敛）: "
                            + run.id());
        }

        // ⑥ 断言推导 + 装配 + 适配（确认根因/诚实 unknown 三态，FUT-12 语义分歧不回退）
        // EX-A0：输入比对输入、Claim 绑定输出快照——两身份分型，混用编译期拒绝
        // R7-X6 主模式：FINAL 提案已过 PrimaryClaimAdmission 代码准入、落检查点——
        // 报告相位只做检查点→ClaimStore 确定性投影（不走证据推导的 Reducer 面）
        int projected;
        if (primaryProfile != null) {
            RcaTask primaryTask = primaryTaskOf(run.id()).orElseThrow(
                    () -> new IllegalStateException("主模式 run 缺主任务行: " + run.id()));
            PrimaryCheckpoint checkpoint = checkpoints.findByTask(primaryTask.id())
                    .orElseThrow(() -> new IllegalStateException(
                            "主任务检查点缺失: " + primaryTask.id()));
            projected = new PrimaryFinalClaimProjector(claims, evidence).project(run.id(),
                    checkpoint, snapshotDigest.hex(), generation, timeRange);
        } else {
            projected = -1;
        }
        NativeRcaAgent.NativeResult nativeResult = primaryProfile == null
                ? nativeRcaAgent.investigate(run.id(), inputDigest, snapshotDigest, generation)
                : null;
        List<ClaimVerdict> activeClaims = claims.findByRunId(run.id()).stream()
                .filter(row -> row.lifecycle() == ClaimLifecycle.ACTIVE)
                .map(NativeInvestigationExecutor::toVerdict)
                .toList();
        ReportAssembler.AssembledReport assembled = ReportAssembler.assemble(
                snapshotDigest.hex(), activeClaims);
        NativeReportAdapter.Adapted adapted = NativeReportAdapter.adapt(assembled);

        // ⑦ 自家包过自家验证链；SUCCEEDED 与 REJECTED_* 同权落档（INV-AM3-7）
        EvidencePackageValidator.Result validated = validator.validate(adapted.outerJson());
        boolean ok = validated.status() == ValidationStatus.STRUCTURE_VALIDATED;
        // M6-02 观察面成账：NATIVE 侧 attempt 指标与 HOLMES 同名同维（engine 分桶）
        metrics.attemptFinished(validated.status().name(), "NATIVE");
        metrics.attemptLatency((System.nanoTime() - beginNanos) / 1_000_000L, "NATIVE");
        log.info("native 全链完成 run={} reportOutcome={} claims={} validation={} 快照={} 主模式={}",
                run.id(), assembled.outcome(),
                primaryProfile == null ? nativeResult.verdicts().size() : projected,
                validated.status(), snapshotDigest, primaryProfile != null);
        // R6/EV-06：usage_json 回填（原写死 null/usage_missing=true）——本 attempt 的
        // rca_model_call 行聚合（RcaAttemptUsage 诚实规则：UNKNOWN/usage_missing 行
        // taint 整 attempt；FAILED/无行不编数）
        com.objwww.pr.control.alert.domain.agent.RcaAttemptUsage.Aggregated usage =
                com.objwww.pr.control.alert.domain.agent.RcaAttemptUsage.aggregate(
                        modelCalls.listSettledUsageByRunId(run.id()).stream()
                                .filter(row -> attempt.id().equals(row.attemptId()))
                                .toList());
        AttemptArtifact artifact = new AttemptArtifact(
                validated.schemaVersion() > 0
                        ? validated.schemaVersion() : EvidencePackageV2.SCHEMA_VERSION,
                validated.status(), validated.errors(), validated.packageJson(),
                validated.redactedRawText(), validated.typedPackage(), List.of(),
                Digest.sha256Of(validated.redactedRawText() != null
                        ? validated.redactedRawText() : adapted.outerJson()),
                null, MODEL, usage.promptTokens(), usage.completionTokens(),
                usage.totalTokens(), usage.usageMissing(), null);
        return ok ? ExecutionResult.success(artifact)
                : ExecutionResult.terminalWithArtifact("ADAPTER_PACKAGE_REJECTED",
                        String.join("; ", validated.errors()), artifact);
    }

    // ------------------------------------------------------------------ 内部

    /**
     * Run 固定 digest 的 bundle 的 native.proposal 段（EN-03 准入固定：drive 期一律
     * 按铸造时路由 digest 读，禁读 active——指针移动不改在跑 Run 的提案源，
     * S11/P09/R07；无 bundle/无段/形状非法 = empty，fail-closed）。
     */
    private Optional<Map<String, Object>> proposalOf(ConfigDigest configDigest) {
        return bundles.findByDigest(new Digest(configDigest.hex())).flatMap(bundle -> {
            if (!(bundle.content().get(NATIVE_SECTION) instanceof Map<?, ?> section)) {
                return Optional.<Map<String, Object>>empty();
            }
            if (!(section.get(PROPOSAL_KEY) instanceof Map<?, ?> raw)) {
                return Optional.<Map<String, Object>>empty();
            }
            return Optional.of(asStringKeyMap(raw));
        });
    }

    private static Map<String, Object> asStringKeyMap(Map<?, ?> raw) {
        Map<String, Object> out = new java.util.LinkedHashMap<>();
        for (Map.Entry<?, ?> e : raw.entrySet()) {
            out.put(String.valueOf(e.getKey()), e.getValue());
        }
        return out;
    }

    /**
     * 三调查任务驱动（Am4ShadowTrigger 同语义：READY→LEASED→RUNNING→DONE/DEAD）。
     * EX-A0 F14：时间窗 = Run 冻结列（铸点 [铸造时刻-600s, 铸造时刻]），执行期禁止
     * 静默改取"执行时最近十分钟"；存量行（冻结列缺席）回退旧行为并 WARN 留痕。
     * EX-A4a（F16）：attemptId = worker 持久铸造的驱动 attempt（RcaWorker 一 attempt
     * 一记录锚）——账本行引用真实持久 attempt，零随机 UUID 幽灵引用。
     * EX-A3（F08）：call_seq 跨 attempt 单调——本轮从既有 checkpoint 最大值续起。
     */
    /**
     * DAG 任务驱动到不动点（R7-X6）：单遍扫描改为 sweep 循环——主模式委派批在驱动
     * 途中原子生长 READY 子任务，扫描清单必须每轮重读；一轮无任何任务状态迁移即
     * 收敛（委派批生长子任务行即进展；等待重驱的 STILL_WAITING 虽计一次进展，子
     * 任务当轮/次轮即结清，收敛面不变，sweep 上限兜底防无限等待）。旧兼容路由
     * 单轮即收敛（三任务互不依赖），行为与单遍等价。返回冻结时间窗串（报告相位
     * 投影复用）。
     */
    private String investigate(UUID runId, UUID attemptId, long generation,
            InvestigationInputDigest inputDigest, RcaRunRepository.RoutingView routing,
            Runnable heartbeat) {
        Instant end = routing.windowEnd() != null ? routing.windowEnd() : clock.now();
        if (routing.windowStart() == null || routing.windowEnd() == null) {
            log.warn("run {} 无冻结时间窗（存量行兼容面），回退执行时窗口——F14 冻结语义缺失",
                    runId);
        }
        Instant start = routing.windowStart() != null
                ? routing.windowStart() : end.minusSeconds(InvestigationInputs.RANGE_WINDOW_SECS);
        String endEpoch = Long.toString(end.getEpochSecond());
        String startEpoch = Long.toString(start.getEpochSecond());
        String timeRange = startEpoch + "/" + endEpoch;
        long callSeq = tasks.findByRunId(runId).stream()
                .filter(t -> !t.taskKey().equals(RcaTask.NATIVE_INVESTIGATE))
                .flatMap(t -> toolLedger.findRecoveryByTask(runId, t.id()).stream())
                .mapToLong(com.objwww.pr.control.alert.domain.tool.RcaToolInvocationLedger
                        .InvocationRecovery::callSeq)
                .max().orElse(0);
        boolean progress = true;
        int sweeps = 0;
        while (progress) {
            if (++sweeps > MAX_DRIVE_SWEEPS) {
                log.warn("run {} 驱动 sweep 上限（{}）到达，停止（不动点未收敛，诚实终态失败）",
                        runId, MAX_DRIVE_SWEEPS);
                break;
            }
            progress = false;
            for (RcaTask dagTask : drivableTasks(runId)) {
                callSeq++;
                SingleToolEvidenceAgent.CallContext ctx = new SingleToolEvidenceAgent.CallContext(
                        runId, dagTask.id(), attemptId, callSeq, generation,
                        inputDigest, timeRange);
                progress |= drive(dagTask, ctx, startEpoch, endEpoch);
                heartbeat.run();
            }
        }
        return timeRange;
    }

    /** 可驱动任务清单（每 sweep 重读：委派批子任务中途生长；终态任务不占位） */
    private List<RcaTask> drivableTasks(UUID runId) {
        return tasks.findByRunId(runId).stream()
                .filter(t -> !t.taskKey().equals(RcaTask.NATIVE_INVESTIGATE))
                .filter(t -> t.state() != RcaTaskState.DONE
                        && t.state() != RcaTaskState.DEAD)
                .toList();
    }

    /** 主任务行定位（主模式报告相位投影面） */
    private java.util.Optional<RcaTask> primaryTaskOf(UUID runId) {
        return tasks.findByRunId(runId).stream()
                .filter(t -> t.taskKey().equals(RcaTask.PRIMARY_INVESTIGATE))
                .findFirst();
    }

    /**
     * 单任务驱动（EX-A3 四阶段恢复分诊，docs/告警-EXA3-可恢复驱动.md §2——恢复语义
     * 整段替换 v1.0 的"无回执按幂等键重驱"；领养面：LEASED/RUNNING 孤儿不再跳过）：
     *
     * <ul>
     *   <li>阶段④ 已提交（DONE）——跳过，结论重放读取既有 Claim/报告面；</li>
     *   <li>阶段③ 结果已落库（SUCCESS 行 + result_ref 在库证据）——幂等收尾
     *       RUNNING→DONE，零触网零新行零新证据；<b>仅兼容单工具任务</b>
     *       （BA-118：主任务的直查回执是步级证据不是结论，主任务由检查点相位续驱）；</li>
     *   <li>阶段② 发送后结果未知（PENDING 悬挂）——先 UNKNOWN 诚实归档（预算占用
     *       不动，PROVISIONAL 留对账），再以新 call_seq/新预算重驱一次物理请求
     *       （只读工具：同冻结窗、留新观察记录；主任务=按检查点重决策续驱）；
     *       既有 UNKNOWN/悬空 SUCCESS 同走重驱；</li>
     *   <li>阶段① 未取得发送资格（无在途/未知行）——常规驱动；FAILED 回执孤儿=
     *       已知失败 → DEAD（缺源降级，仅兼容单工具任务；主任务 FAILED 行是模型
     *       可见步级失败，恢复面不代判终态），不重复调用。</li>
     * </ul>
     * 不变量：恢复一遍后无永久 RUNNING；恢复不重复调用（账本行可证）。
     *
     * @return 本任务是否发生了任务行状态迁移（sweep 收敛判定：无迁移 = 一轮无进展）
     */
    private boolean drive(RcaTask dagTask, SingleToolEvidenceAgent.CallContext ctx,
            String startEpoch, String endEpoch) {
        if (dagTask.state() == RcaTaskState.DONE || dagTask.state() == RcaTaskState.DEAD) {
            return false;   // 阶段④：任务与结果已提交（DEAD=缺源降级终态同不吃回）
        }
        var prior = toolLedger.findRecoveryByTask(ctx.runId(), dagTask.id()).stream()
                .toList();

        // BA-118：恢复分诊需要任务种类，绑定/角色/运行器解析前移到分诊前——主任务
        // （BOUNDED_LLM）的直查成功回执只是步级证据，不是任务结论；修复前阶段③把它
        // 误当单工具任务零触网 RUNNING→DONE 收尾，checkpoint 冻结 WAITING_CHILDREN、
        // final_claims 空，run 无收敛结论即 SUCCEEDED（SIGKILL 打进委派等待窗实证）。
        // 阶段③幂等收尾与"已知失败回执孤儿 → DEAD"自此仅适用 DETERMINISTIC_SINGLE_TOOL；
        // 主任务领养后直进 drivePrimary（检查点相位续驱，V88 信封反馈不丢）。
        TaskExecutionBinding binding = bindings.findByTask(dagTask.id()).orElse(null);
        if (binding == null) {
            tasks.transitionState(dagTask.id(), RcaTaskState.RUNNING, RcaTaskState.DEAD);
            log.warn("DAG 任务 {} 绑定缺席（不猜角色）→ DEAD key={}",
                    dagTask.id(), dagTask.taskKey());
            return true;
        }
        AgentProfile profile;
        RoleRunner runner;
        try {
            profile = agents.requireExact(binding.roleId(), binding.roleVersion(),
                    binding.roleDigest());
            runner = runners.requireFor(profile);
        } catch (IllegalArgumentException
                | RunnerDirectory.CapabilityUnavailableException e) {
            tasks.transitionState(dagTask.id(), RcaTaskState.RUNNING, RcaTaskState.DEAD);
            log.warn("DAG 任务 {} 角色/运行器解析拒绝 → DEAD key={} 原因: {}",
                    dagTask.id(), dagTask.taskKey(), e.getMessage());
            submitDelegationReceipt(dagTask, binding, false, List.of(),
                    "子任务失败：角色/运行器解析拒绝（" + e.getMessage() + "），未产出结论");
            return true;
        }
        boolean primaryMode = RoleRuntimeKind.BOUNDED_LLM.equals(profile.runtimeKind());

        // 阶段③：结果已落库 → result_ref 幂等收尾，零触网（仅兼容单工具任务）
        if (!primaryMode) {
            var recovered = prior.stream().filter(r ->
                            r.state() == com.objwww.pr.control.alert.domain.tool.ToolInvocationState.SUCCESS
                                    && r.resultRef() != null
                                    && evidence.findById(r.resultRef()).isPresent())
                    .findFirst();
            if (recovered.isPresent()) {
                boolean settled = tasks.transitionState(dagTask.id(),
                        RcaTaskState.RUNNING, RcaTaskState.DONE);
                if (settled) {
                    log.info("DAG 任务 {} 阶段③恢复：result_ref 幂等收尾（零触网）key={}",
                            dagTask.id(), dagTask.taskKey());
                    // MC21：恢复重驱=同结果重投（同 messageId）→ 准入幂等恰一次合并
                    submitDelegationReceipt(dagTask, binding, true,
                            List.of(recovered.get().resultRef()), null);
                }
                return settled;
            }
        }

        // 领养准入：READY 走正常迁移；LEASED/RUNNING 孤儿接管（driver 独占保证单驱动）
        boolean adopted = dagTask.state() == RcaTaskState.RUNNING;
        if (dagTask.state() == RcaTaskState.LEASED) {
            adopted = tasks.transitionState(dagTask.id(), RcaTaskState.LEASED,
                    RcaTaskState.RUNNING);
        } else if (!adopted) {
            adopted = tasks.transitionState(dagTask.id(), RcaTaskState.READY, RcaTaskState.LEASED)
                    && tasks.transitionState(dagTask.id(), RcaTaskState.LEASED,
                            RcaTaskState.RUNNING);
        }
        if (!adopted) {
            log.warn("DAG 任务 {} 状态迁移失败（非 READY/LEASED/RUNNING），跳过 key={}",
                    dagTask.id(), dagTask.taskKey());
            return false;
        }

        // 阶段②：PENDING 悬挂 → UNKNOWN 归档（发送后结果未知；预算占用不动），
        // 随后新物理请求重驱（新 call_seq=新预算预留，不默认免费重发）
        for (var row : prior) {
            if (row.state() == com.objwww.pr.control.alert.domain.tool.ToolInvocationState.PENDING) {
                ledgerMarkUnknown(row);
            }
        }
        // "已知失败回执孤儿 → DEAD"仅适用兼容单工具任务（一次物理查询结局即终态）；
        // 主任务的 FAILED 行是模型可见的步级失败（TOOL_RETRYABLE:* 族），是否重试
        // 由 drivePrimary 的决策循环按检查点判定，恢复面不得代判终态（BA-118）
        if (!primaryMode) {
            boolean knownFailed = prior.stream().allMatch(r ->
                    r.state() == com.objwww.pr.control.alert.domain.tool.ToolInvocationState.FAILED);
            if (!prior.isEmpty() && knownFailed) {
                // 已知失败回执孤儿：降级 DEAD，不重复调用
                tasks.transitionState(dagTask.id(), RcaTaskState.RUNNING, RcaTaskState.DEAD);
                log.warn("DAG 任务 {} FAILED 回执孤儿 → DEAD（不重复调用）key={}",
                        dagTask.id(), dagTask.taskKey());
                submitDelegationReceipt(dagTask, binding, false, List.of(),
                        "子任务失败：工具查询历史全败（FAILED 回执孤儿降级），未产出结论");
                return true;
            }
        }

        // R7-X2：分派面 = 持久绑定.roleId + Profile.runtime_kind（业务 taskKey 不再
        // 承担角色身份）；绑定/角色/运行器解析已前移至恢复分诊前（BA-118），
        // 此处直接进入分派
        RoleRunner.RoleDriveRequest request = new RoleRunner.RoleDriveRequest(dagTask,
                binding, profile, ctx, startEpoch, endEpoch);
        // R7-X6：主 Runner 面走不动点循环（一步一决策，直到委派等待/FINAL/耗尽）；
        // 兼容单工具面保持单驱语义（一次物理查询，结局即终态）
        if (primaryMode) {
            return drivePrimary(dagTask, runner, request);
        }
        RoleRunner.RoleDriveResult result;
        try {
            result = runner.drive(request);
        } catch (ToolControlPlaneException denied) {
            tasks.transitionState(dagTask.id(), RcaTaskState.RUNNING, RcaTaskState.DEAD);
            log.warn("DAG 任务 {} 工具控制面拒绝（{}），降级 DEAD", dagTask.taskKey(),
                    denied.reason());
            submitDelegationReceipt(dagTask, binding, false, List.of(),
                    "子任务失败：工具控制面拒绝（" + denied.reason() + "），未产出结论");
            return true;
        }
        if (result.outcome() == RoleRunner.RoleDriveOutcome.FAILED) {
            tasks.transitionState(dagTask.id(), RcaTaskState.RUNNING, RcaTaskState.DEAD);
            submitDelegationReceipt(dagTask, binding, false, List.of(),
                    "子任务失败（" + result.reason() + "），未产出结论——结构化缺口如实上呈");
            return true;
        }
        boolean done = tasks.transitionState(dagTask.id(), RcaTaskState.RUNNING,
                RcaTaskState.DONE);
        if (done) {
            submitDelegationReceipt(dagTask, binding, true, result.evidenceIds(), null);
        }
        return done;
    }

    /**
     * MC21 回执生产：委派子任务（binding.parentRequestId=裁决行 id）终态即提交
     * 结构化回执——messageId 按 (childTaskId, attemptCount) 确定性铸造，恢复重驱
     * 同键重投由准入幂等短路（恰一次合并）。回执是台账叠面，任务状态仍是事实源：
     * 提交失败只 log-warn，不翻转任务结局。
     */
    private void submitDelegationReceipt(RcaTask task, TaskExecutionBinding binding,
            boolean success, List<UUID> evidenceRefs, String failureNote) {
        if (delegationReceipts == null || binding.parentRequestId() == null) {
            return;
        }
        UUID messageId = UUID.nameUUIDFromBytes(("r7-receipt:" + task.id() + ":"
                + task.attemptCount()).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        List<String> refs = evidenceRefs.stream().map(UUID::toString).toList();
        List<String> findings = success
                ? (refs.isEmpty()
                        ? List.of("child DONE（零数据：查询成功但无证据行）")
                        : refs.stream().map(ref -> "evidence:" + ref).toList())
                : List.of();
        List<String> missing = success ? List.of()
                : List.of(failureNote == null ? "子任务失败，未产出结论" : failureNote);
        try {
            var verdict = delegationReceipts.submit(
                    new com.objwww.pr.control.alert.application.agent
                            .DelegationReceiptService.Submission(
                            messageId, task.runId(), binding.parentRequestId(),
                            task.id(), binding.roundId(),
                            success
                                    ? com.objwww.pr.control.alert.domain.agent
                                            .DelegationReceipt.ChildStatus.SUCCEEDED
                                    : com.objwww.pr.control.alert.domain.agent
                                            .DelegationReceipt.ChildStatus.FAILED,
                            findings, refs, List.of(), missing));
            log.info("委派回执已提交 child={} admission={} duplicate={}",
                    task.id(), verdict.receipt().admission(), verdict.duplicate());
        } catch (RuntimeException e) {
            log.warn("委派回执提交失败（不打断任务结局）child={}: {}",
                    task.id(), e.getMessage());
        }
    }

    /**
     * 主 Runner 不动点循环（R7-X6）：{@link BoundedLlmRoleRunner} 一步一互斥决策——
     * TOOL_CALL 取证/计步重驱类失败继续循环（每类都已在运行器内计步，步数耗尽由
     * 运行器兜底确定性 FINAL）；委派批获批 → WAITING_CHILDREN 返回 true（子任务行
     * 已生长=行迁移进展；任务行保持 RUNNING，等待态在检查点相位，恢复领养面已覆盖），
     * sweep 转驱子任务后由下一轮唤醒续走；FINAL_READY → DONE（报告相位消费检查点
     * 提案）。DELEGATE 全拒不消耗步数（X4 裁定"全拒状态不动"），防御性迭代上限兜住
     * 模型空转（超出 = DEAD，不静默无限烧模型）。
     */
    private boolean drivePrimary(RcaTask dagTask, RoleRunner runner,
            RoleRunner.RoleDriveRequest request) {
        int maxIterations = request.profile().maxSteps() * 2
                + DeterministicSupervisor.MAX_DELEGATION_BATCHES + 4;
        for (int i = 0; i < maxIterations; i++) {
            RoleRunner.RoleDriveResult result;
            try {
                result = runner.drive(request);
            } catch (ToolControlPlaneException denied) {
                tasks.transitionState(dagTask.id(), RcaTaskState.RUNNING, RcaTaskState.DEAD);
                log.warn("主任务 {} 工具控制面拒绝（{}）→ DEAD", dagTask.id(), denied.reason());
                return true;
            }
            switch (result.outcome()) {
                case FINAL_READY -> {
                    tasks.transitionState(dagTask.id(), RcaTaskState.RUNNING,
                            RcaTaskState.DONE);
                    log.info("主任务 FINAL 提案就绪 → DONE task={} reason={}",
                            dagTask.id(), result.reason());
                    return true;
                }
                case WAITING_CHILDREN -> {
                    // 委派批已原子生长 READY 子任务行 = 行迁移进展（真 true）；后续
                    // STILL_WAITING 重驱虽计进展，但子任务同/次 sweep 即结清，不动点
                    // 收敛不受影响——sweep 上限兜底防无限等待（诚实终态失败）
                    log.info("主任务转委派等待（sweep 转驱子任务，结清后唤醒）task={} reason={}",
                            dagTask.id(), result.reason());
                    return true;
                }
                case FAILED -> {
                    if (!isPrimaryRetryable(result.reason())) {
                        tasks.transitionState(dagTask.id(), RcaTaskState.RUNNING,
                                RcaTaskState.DEAD);
                        log.warn("主任务 {} 不可重试失败（{}）→ DEAD", dagTask.id(),
                                result.reason());
                        return true;
                    }
                    break;   // 计步重驱类：DECISION_UNPARSEABLE/TOOL_NOT_ALLOWED/TOOL_RETRYABLE:*
                }
                default -> {
                    // EVIDENCE_PRODUCED / DELEGATE_REJECTED / NO_DATA——有界继续
                }
            }
        }
        tasks.transitionState(dagTask.id(), RcaTaskState.RUNNING, RcaTaskState.DEAD);
        log.warn("主任务 {} 驱动迭代上限（{}，DELEGATE 空转防御）→ DEAD", dagTask.id(),
                maxIterations);
        return true;
    }

    /** 主 Runner 可重试失败封闭集（每一类都已在运行器内计步，步数耗尽兜底保终止） */
    private static boolean isPrimaryRetryable(String reason) {
        return reason != null && (reason.equals("DECISION_UNPARSEABLE")
                || reason.equals("TOOL_NOT_ALLOWED")
                || reason.startsWith("TOOL_RETRYABLE"));
    }

    private void ledgerMarkUnknown(
            com.objwww.pr.control.alert.domain.tool.RcaToolInvocationLedger.InvocationRecovery row) {
        toolLedger.fail(row.operationId(),
                com.objwww.pr.control.alert.domain.tool.ToolInvocationState.UNKNOWN,
                com.objwww.pr.control.alert.domain.tool.ToolReasonCode.TRANSPORT_UNKNOWN);
        log.warn("阶段②恢复：悬挂 PENDING → UNKNOWN（发送后结果未知，预算占用保留）op={}",
                row.operationId());
    }

    /** 冻结证据快照（M4-20 惯例：成员 = 本 run 全部证据 (type,payload_digest)） */
    private EvidenceSnapshotDigest freezeSnapshot(UUID runId, long generation,
            String configDigest) {
        List<EvidenceEnvelope> rows = evidence.findByRunId(runId);
        List<EvidenceSnapshotBuilder.Member> members = rows.stream()
                .map(e -> new EvidenceSnapshotBuilder.Member(e.evidenceType(), e.payloadDigest()))
                .toList();
        EvidenceSnapshotDigest digest = EvidenceSnapshotBuilder.digest(
                new EvidenceSnapshotBuilder.SnapshotInput(
                        generation, configDigest, toolRegistryDigest, members));
        snapshots.freeze(new EvidenceSnapshotRepository.FrozenSnapshot(UUID.randomUUID(),
                        runId, digest.hex(), generation, configDigest, toolRegistryDigest, null),
                rows.stream().map(e -> new EvidenceSnapshotRepository.SnapshotMemberRow(
                        e.evidenceId(), e.evidenceType(), e.payloadDigest())).toList());
        return digest;
    }

    private static ClaimVerdict toVerdict(ClaimStore.ClaimRow row) {
        return new ClaimVerdict(row.claimKey(), row.scope(), row.timeRange(),
                row.observedGeneration(), row.snapshotDigest(), row.status(),
                row.evidenceBasis(), row.sources(), row.reason(), row.evidenceRefs(),
                row.policyVersion());
    }
}
