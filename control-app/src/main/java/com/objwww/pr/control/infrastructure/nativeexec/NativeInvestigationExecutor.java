package com.objwww.pr.control.infrastructure.nativeexec;

import com.objwww.pr.control.alert.application.AlertClock;
import com.objwww.pr.control.alert.application.DeterministicSupervisor;
import com.objwww.pr.control.alert.application.NativeReportAdapter;
import com.objwww.pr.control.alert.application.RcaTaskExecutor;
import com.objwww.pr.control.alert.application.agent.ChangeAgent;
import com.objwww.pr.control.alert.application.agent.LogsAgent;
import com.objwww.pr.control.alert.application.agent.MetricsAgent;
import com.objwww.pr.control.alert.application.agent.NativeRcaAgent;
import com.objwww.pr.control.alert.application.agent.SingleToolEvidenceAgent;
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
import com.objwww.pr.control.alert.domain.model.ValidationStatus;
import com.objwww.pr.control.alert.domain.identity.ConfigDigest;
import com.objwww.pr.control.alert.domain.identity.EvidenceSnapshotDigest;
import com.objwww.pr.control.alert.domain.identity.InvestigationInputDigest;
import com.objwww.pr.control.alert.domain.identity.InvestigationInputs;
import com.objwww.pr.control.alert.domain.repository.RcaRunRepository;
import com.objwww.pr.control.alert.domain.repository.RcaTaskRepository;
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

    private final ConfigBundleRepository bundles;
    private final DeterministicSupervisor supervisor;
    private final RcaTaskRepository tasks;
    private final RcaRunRepository runs;
    private final EvidenceRepository evidence;
    private final EvidenceSnapshotRepository snapshots;
    private final MetricsAgent metricsAgent;
    private final LogsAgent logsAgent;
    private final ChangeAgent changeAgent;
    private final NativeRcaAgent nativeRcaAgent;
    private final ClaimStore claims;
    private final EvidencePackageValidator validator;
    private final AlertMetrics metrics;
    private final String metricsExpr;
    private final String toolRegistryDigest;
    private final AlertClock clock;
    private final com.objwww.pr.control.alert.application.RunBudgetGate budgetGate;
    private final Map<com.objwww.pr.control.alert.domain.budget.BudgetKind, Long> budgetLimits;
    private final com.objwww.pr.control.alert.domain.tool.RcaToolInvocationLedger toolLedger;

    public NativeInvestigationExecutor(ConfigBundleRepository bundles,
            DeterministicSupervisor supervisor, RcaTaskRepository tasks,
            RcaRunRepository runs, EvidenceRepository evidence,
            EvidenceSnapshotRepository snapshots, MetricsAgent metricsAgent,
            LogsAgent logsAgent, ChangeAgent changeAgent, NativeRcaAgent nativeRcaAgent,
            ClaimStore claims, EvidencePackageValidator validator,
            String metricsExpr, String toolRegistryDigest, AlertClock clock,
            AlertMetrics metrics,
            com.objwww.pr.control.alert.application.RunBudgetGate budgetGate,
            Map<com.objwww.pr.control.alert.domain.budget.BudgetKind, Long> budgetLimits,
            com.objwww.pr.control.alert.domain.tool.RcaToolInvocationLedger toolLedger) {
        this.bundles = Objects.requireNonNull(bundles, "bundles");
        this.supervisor = Objects.requireNonNull(supervisor, "supervisor");
        this.tasks = Objects.requireNonNull(tasks, "tasks");
        this.runs = Objects.requireNonNull(runs, "runs");
        this.evidence = Objects.requireNonNull(evidence, "evidence");
        this.snapshots = Objects.requireNonNull(snapshots, "snapshots");
        this.metricsAgent = Objects.requireNonNull(metricsAgent, "metricsAgent");
        this.logsAgent = Objects.requireNonNull(logsAgent, "logsAgent");
        this.changeAgent = Objects.requireNonNull(changeAgent, "changeAgent");
        this.nativeRcaAgent = Objects.requireNonNull(nativeRcaAgent, "nativeRcaAgent");
        this.claims = Objects.requireNonNull(claims, "claims");
        this.validator = Objects.requireNonNull(validator, "validator");
        if (metricsExpr == null || metricsExpr.isBlank()) {
            throw new IllegalArgumentException("metricsExpr 不得为空（F1 症状指标数据源）");
        }
        this.metricsExpr = metricsExpr;
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
        budgetGate.openRun(run.id(), budgetLimits);
        Optional<Map<String, Object>> proposal = proposalOf(configDigest);
        if (proposal.isEmpty()) {
            return ExecutionResult.terminal("PROPOSAL_MISSING",
                    "路由 bundle 缺 native.proposal 段（fail-closed，模型无调度权，"
                            + "EN-03 按 Run 固定 digest 读、不读 active）: "
                            + configDigest.hex());
        }

        // ② 启动（幂等）：提案非法 → Supervisor 已置 run FAILED，本侧终态诚实记账
        DeterministicSupervisor.StartResult started = supervisor.startRun(run.id(),
                proposal.get(), Set.of());
        if (started.outcome() == DeterministicSupervisor.StartOutcome.PROPOSAL_REJECTED) {
            return ExecutionResult.terminal("PROPOSAL_REJECTED", started.rejectReason());
        }
        heartbeat.run();

        // ③④ DAG 驱动（冻结时间窗）+ 冻结证据快照（configDigest 只进快照身份面）
        long generation = run.generation();
        investigate(run.id(), attempt.id(), generation, inputDigest, routing, heartbeat);
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
        NativeRcaAgent.NativeResult nativeResult = nativeRcaAgent.investigate(run.id(),
                inputDigest, snapshotDigest, generation);
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
        log.info("native 全链完成 run={} reportOutcome={} claims={} validation={} 快照={}",
                run.id(), assembled.outcome(), nativeResult.verdicts().size(),
                validated.status(), snapshotDigest);
        AttemptArtifact artifact = new AttemptArtifact(
                validated.schemaVersion() > 0
                        ? validated.schemaVersion() : EvidencePackageV2.SCHEMA_VERSION,
                validated.status(), validated.errors(), validated.packageJson(),
                validated.redactedRawText(), validated.typedPackage(), List.of(),
                Digest.sha256Of(validated.redactedRawText() != null
                        ? validated.redactedRawText() : adapted.outerJson()),
                null, MODEL, null, null, null, true, null);
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
    private void investigate(UUID runId, UUID attemptId, long generation,
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
        List<RcaTask> dagTasks = tasks.findByRunId(runId).stream()
                .filter(t -> !t.taskKey().equals(RcaTask.NATIVE_INVESTIGATE)).toList();
        long callSeq = dagTasks.stream()
                .flatMap(t -> toolLedger.findRecoveryByTask(runId, t.id()).stream())
                .mapToLong(com.objwww.pr.control.alert.domain.tool.RcaToolInvocationLedger
                        .InvocationRecovery::callSeq)
                .max().orElse(0);
        for (RcaTask dagTask : dagTasks) {
            if (dagTask.state() == RcaTaskState.DONE
                    || dagTask.state() == RcaTaskState.DEAD) {
                continue;   // 阶段④：已提交任务不占 call_seq（计号=物理请求，非任务槽位）
            }
            callSeq++;
            SingleToolEvidenceAgent.CallContext ctx = new SingleToolEvidenceAgent.CallContext(
                    runId, dagTask.id(), attemptId, callSeq, generation,
                    inputDigest, timeRange);
            drive(dagTask, ctx, startEpoch, endEpoch);
            heartbeat.run();
        }
    }

    /**
     * 单任务驱动（EX-A3 四阶段恢复分诊，docs/告警-EXA3-可恢复驱动.md §2——恢复语义
     * 整段替换 v1.0 的"无回执按幂等键重驱"；领养面：LEASED/RUNNING 孤儿不再跳过）：
     *
     * <ul>
     *   <li>阶段④ 已提交（DONE）——跳过，结论重放读取既有 Claim/报告面；</li>
     *   <li>阶段③ 结果已落库（SUCCESS 行 + result_ref 在库证据）——幂等收尾
     *       RUNNING→DONE，零触网零新行零新证据；</li>
     *   <li>阶段② 发送后结果未知（PENDING 悬挂）——先 UNKNOWN 诚实归档（预算占用
     *       不动，PROVISIONAL 留对账），再以新 call_seq/新预算重驱一次物理请求
     *       （只读工具：同冻结窗、留新观察记录）；既有 UNKNOWN/悬空 SUCCESS 同走重驱；</li>
     *   <li>阶段① 未取得发送资格（无在途/未知行）——常规驱动；FAILED 回执孤儿=
     *       已知失败 → DEAD（缺源降级），不重复调用。</li>
     * </ul>
     * 不变量：恢复一遍后无永久 RUNNING；恢复不重复调用（账本行可证）。
     */
    private void drive(RcaTask dagTask, SingleToolEvidenceAgent.CallContext ctx,
            String startEpoch, String endEpoch) {
        if (dagTask.state() == RcaTaskState.DONE || dagTask.state() == RcaTaskState.DEAD) {
            return;   // 阶段④：任务与结果已提交（DEAD=缺源降级终态同不吃回）
        }
        var prior = toolLedger.findRecoveryByTask(ctx.runId(), dagTask.id()).stream()
                .toList();

        // 阶段③：结果已落库 → result_ref 幂等收尾，零触网
        var recovered = prior.stream().filter(r ->
                        r.state() == com.objwww.pr.control.alert.domain.tool.ToolInvocationState.SUCCESS
                                && r.resultRef() != null
                                && evidence.findById(r.resultRef()).isPresent())
                .findFirst();
        if (recovered.isPresent()) {
            if (tasks.transitionState(dagTask.id(), RcaTaskState.RUNNING, RcaTaskState.DONE)) {
                log.info("DAG 任务 {} 阶段③恢复：result_ref 幂等收尾（零触网）key={}",
                        dagTask.id(), dagTask.taskKey());
            }
            return;
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
            return;
        }

        // 阶段②：PENDING 悬挂 → UNKNOWN 归档（发送后结果未知；预算占用不动），
        // 随后新物理请求重驱（新 call_seq=新预算预留，不默认免费重发）
        for (var row : prior) {
            if (row.state() == com.objwww.pr.control.alert.domain.tool.ToolInvocationState.PENDING) {
                ledgerMarkUnknown(row);
            }
        }
        boolean knownFailed = prior.stream().allMatch(r ->
                r.state() == com.objwww.pr.control.alert.domain.tool.ToolInvocationState.FAILED);
        if (!prior.isEmpty() && knownFailed) {
            // 已知失败回执孤儿：降级 DEAD，不重复调用
            tasks.transitionState(dagTask.id(), RcaTaskState.RUNNING, RcaTaskState.DEAD);
            log.warn("DAG 任务 {} FAILED 回执孤儿 → DEAD（不重复调用）key={}",
                    dagTask.id(), dagTask.taskKey());
            return;
        }

        SingleToolEvidenceAgent.AgentResult result;
        try {
            result = switch (dagTask.taskKey()) {
                case TASK_METRICS -> metricsAgent.investigate(ctx,
                        new MetricsAgent.MetricsQuery(metricsExpr, startEpoch, endEpoch,
                                InvestigationInputs.STEP));
                case TASK_LOGS -> logsAgent.investigate(ctx,
                        new LogsAgent.LogsQuery(startEpoch, endEpoch));
                case TASK_CHANGE -> changeAgent.investigate(ctx,
                        new ChangeAgent.ChangeQuery(startEpoch, endEpoch));
                default -> null;   // 提案引用未注册调查任务：降级 DEAD，不阻断报告
            };
        } catch (ToolControlPlaneException denied) {
            tasks.transitionState(dagTask.id(), RcaTaskState.RUNNING, RcaTaskState.DEAD);
            log.warn("DAG 任务 {} 工具控制面拒绝（{}），降级 DEAD", dagTask.taskKey(),
                    denied.reason());
            return;
        }
        if (result == null || result.outcome() == SingleToolEvidenceAgent.AgentOutcome.FAILED) {
            tasks.transitionState(dagTask.id(), RcaTaskState.RUNNING, RcaTaskState.DEAD);
            return;
        }
        tasks.transitionState(dagTask.id(), RcaTaskState.RUNNING, RcaTaskState.DONE);
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
