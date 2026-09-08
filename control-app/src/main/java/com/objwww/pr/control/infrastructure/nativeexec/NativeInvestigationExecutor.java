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
import com.objwww.pr.control.alert.domain.repository.RcaRunRepository;
import com.objwww.pr.control.alert.domain.repository.RcaTaskRepository;
import com.objwww.pr.control.alert.domain.service.EvidencePackageValidator;
import com.objwww.pr.control.alert.domain.tool.ToolControlPlaneException;
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
 * 提案（active bundle {@code native.proposal} 段；缺失/非法 fail-closed，模型无
 * 调度权 INV-AM4-2 顺延）→ {@link DeterministicSupervisor#startRun} 落图 →
 * DAG 调查任务逐个驱动（复用 AM4 三 Agent 与只读工具面；单任务失败 = 缺源降级
 * DEAD 续跑；M6-01 只驱动 READY 态——崩溃孤儿 RUNNING 态 fail-closed 不续跑，
 * 孤儿领养归 M6-04 加固）→ 冻结证据快照（configDigest = 路由 bundle digest，
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
    private static final long RANGE_WINDOW_SECS = 600L;
    private static final String STEP = "30s";
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
    private final String metricsExpr;
    private final String toolRegistryDigest;
    private final AlertClock clock;

    public NativeInvestigationExecutor(ConfigBundleRepository bundles,
            DeterministicSupervisor supervisor, RcaTaskRepository tasks,
            RcaRunRepository runs, EvidenceRepository evidence,
            EvidenceSnapshotRepository snapshots, MetricsAgent metricsAgent,
            LogsAgent logsAgent, ChangeAgent changeAgent, NativeRcaAgent nativeRcaAgent,
            ClaimStore claims, EvidencePackageValidator validator,
            String metricsExpr, String toolRegistryDigest, AlertClock clock) {
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
    }

    @Override
    public ExecutionResult execute(RcaTask task, RcaRun run, Incident incident,
            RcaAttempt attempt, Runnable heartbeat) {
        // ① 身份/提案源 fail-closed：无路由 digest = 快照身份面缺失；无提案段 = 终态失败
        String configDigest = runs.findRoutingById(run.id())
                .map(RcaRunRepository.RoutingView::configDigest).orElse(null);
        if (configDigest == null || configDigest.isBlank()) {
            return ExecutionResult.terminal("CONFIG_DIGEST_MISSING",
                    "NATIVE run 无路由 configDigest（快照身份面缺失）: " + run.id());
        }
        Optional<Map<String, Object>> proposal = proposalOf();
        if (proposal.isEmpty()) {
            return ExecutionResult.terminal("PROPOSAL_MISSING",
                    "active bundle 缺 native.proposal 段（fail-closed，模型无调度权）");
        }

        // ② 启动（幂等）：提案非法 → Supervisor 已置 run FAILED，本侧终态诚实记账
        DeterministicSupervisor.StartResult started = supervisor.startRun(run.id(),
                proposal.get(), Set.of());
        if (started.outcome() == DeterministicSupervisor.StartOutcome.PROPOSAL_REJECTED) {
            return ExecutionResult.terminal("PROPOSAL_REJECTED", started.rejectReason());
        }
        heartbeat.run();

        // ③④ DAG 驱动 + 冻结证据快照（configDigest = 路由 bundle digest）
        long generation = run.generation();
        investigate(run.id(), generation, configDigest, heartbeat);
        String snapshotDigest = freezeSnapshot(run.id(), generation, configDigest);

        // ⑤ 推进入 REPORTING（全任务终态；未进入 = 链面异常，终态失败不产报告）
        supervisor.advance(run.id());
        RcaRunState state = runs.findById(run.id()).map(RcaRun::state).orElse(null);
        if (state != RcaRunState.REPORTING) {
            return ExecutionResult.terminal("REPORTING_NOT_ENTERED",
                    "advance 后 run 未入 REPORTING（state=" + state + "，DAG 未收敛）: "
                            + run.id());
        }

        // ⑥ 断言推导 + 装配 + 适配（确认根因/诚实 unknown 三态，FUT-12 语义分歧不回退）
        NativeRcaAgent.NativeResult nativeResult =
                nativeRcaAgent.investigate(run.id(), snapshotDigest, generation);
        List<ClaimVerdict> activeClaims = claims.findByRunId(run.id()).stream()
                .filter(row -> row.lifecycle() == ClaimLifecycle.ACTIVE)
                .map(NativeInvestigationExecutor::toVerdict)
                .toList();
        ReportAssembler.AssembledReport assembled = ReportAssembler.assemble(
                snapshotDigest, activeClaims);
        NativeReportAdapter.Adapted adapted = NativeReportAdapter.adapt(assembled);

        // ⑦ 自家包过自家验证链；SUCCEEDED 与 REJECTED_* 同权落档（INV-AM3-7）
        EvidencePackageValidator.Result validated = validator.validate(adapted.outerJson());
        boolean ok = validated.status() == ValidationStatus.STRUCTURE_VALIDATED;
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

    /** active bundle 的 native.proposal 段（无 bundle/无段/形状非法 = empty，fail-closed） */
    private Optional<Map<String, Object>> proposalOf() {
        return bundles.activeDigest().flatMap(active ->
                bundles.findByDigest(active).flatMap(bundle -> {
                    if (!(bundle.content().get(NATIVE_SECTION) instanceof Map<?, ?> section)) {
                        return Optional.<Map<String, Object>>empty();
                    }
                    if (!(section.get(PROPOSAL_KEY) instanceof Map<?, ?> raw)) {
                        return Optional.<Map<String, Object>>empty();
                    }
                    return Optional.of(asStringKeyMap(raw));
                }));
    }

    private static Map<String, Object> asStringKeyMap(Map<?, ?> raw) {
        Map<String, Object> out = new java.util.LinkedHashMap<>();
        for (Map.Entry<?, ?> e : raw.entrySet()) {
            out.put(String.valueOf(e.getKey()), e.getValue());
        }
        return out;
    }

    /** 三调查任务驱动（Am4ShadowTrigger 同语义：READY→LEASED→RUNNING→DONE/DEAD） */
    private void investigate(UUID runId, long generation, String snapshotDigest,
            Runnable heartbeat) {
        Instant end = clock.now();
        String endEpoch = Long.toString(end.getEpochSecond());
        String startEpoch = Long.toString(end.minusSeconds(RANGE_WINDOW_SECS).getEpochSecond());
        String timeRange = startEpoch + "/" + endEpoch;
        long callSeq = 0;
        for (RcaTask dagTask : tasks.findByRunId(runId)) {
            if (dagTask.taskKey().equals(RcaTask.NATIVE_INVESTIGATE)) {
                continue;   // driver task 本体不入 DAG 驱动
            }
            callSeq++;
            SingleToolEvidenceAgent.CallContext ctx = new SingleToolEvidenceAgent.CallContext(
                    runId, dagTask.id(), UUID.randomUUID(), callSeq, generation,
                    snapshotDigest, timeRange);
            drive(dagTask, ctx, startEpoch, endEpoch);
            heartbeat.run();
        }
    }

    /** 单任务驱动：成功 DONE；失败/拒绝/未注册 key = 缺源降级 DEAD 续跑；迁移失败跳过 */
    private void drive(RcaTask dagTask, SingleToolEvidenceAgent.CallContext ctx,
            String startEpoch, String endEpoch) {
        if (!tasks.transitionState(dagTask.id(), RcaTaskState.READY, RcaTaskState.LEASED)
                || !tasks.transitionState(dagTask.id(), RcaTaskState.LEASED,
                        RcaTaskState.RUNNING)) {
            log.warn("DAG 任务 {} 状态迁移失败（非 READY/已被驱动），跳过 key={}",
                    dagTask.id(), dagTask.taskKey());
            return;
        }
        SingleToolEvidenceAgent.AgentResult result;
        try {
            result = switch (dagTask.taskKey()) {
                case TASK_METRICS -> metricsAgent.investigate(ctx,
                        new MetricsAgent.MetricsQuery(metricsExpr, startEpoch, endEpoch, STEP));
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

    /** 冻结证据快照（M4-20 惯例：成员 = 本 run 全部证据 (type,payload_digest)） */
    private String freezeSnapshot(UUID runId, long generation, String configDigest) {
        List<EvidenceEnvelope> rows = evidence.findByRunId(runId);
        List<EvidenceSnapshotBuilder.Member> members = rows.stream()
                .map(e -> new EvidenceSnapshotBuilder.Member(e.evidenceType(), e.payloadDigest()))
                .toList();
        String digest = EvidenceSnapshotBuilder.digest(new EvidenceSnapshotBuilder.SnapshotInput(
                generation, configDigest, toolRegistryDigest, members));
        snapshots.freeze(new EvidenceSnapshotRepository.FrozenSnapshot(UUID.randomUUID(),
                runId, digest, generation, configDigest, toolRegistryDigest, null),
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
