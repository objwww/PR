package com.objwww.pr.control.alert.application;

import com.objwww.pr.control.alert.application.agent.AgentRegistry;
import com.objwww.pr.control.alert.domain.agent.AgentProfile;
import com.objwww.pr.control.alert.domain.agent.DelegationDecision;
import com.objwww.pr.control.alert.domain.agent.PrimaryCheckpoint;
import com.objwww.pr.control.alert.domain.agent.PrimaryDecision;
import com.objwww.pr.control.alert.domain.dag.DagPromotion;
import com.objwww.pr.control.alert.domain.dag.DagTaskState;
import com.objwww.pr.control.alert.domain.model.RcaRun;
import com.objwww.pr.control.alert.domain.model.RcaRunState;
import com.objwww.pr.control.alert.domain.model.RcaTask;
import com.objwww.pr.control.alert.domain.model.RcaTaskState;
import com.objwww.pr.control.alert.domain.model.TaskExecutionBinding;
import com.objwww.pr.control.alert.domain.repository.DelegationDecisionRepository;
import com.objwww.pr.control.alert.domain.repository.PrimaryCheckpointRepository;
import com.objwww.pr.control.alert.domain.repository.RcaRunRepository;
import com.objwww.pr.control.alert.domain.repository.RcaTaskRepository;
import com.objwww.pr.control.alert.domain.repository.TaskExecutionBindingRepository;
import com.objwww.pr.control.alert.domain.statemachine.RcaRunStateMachine;
import com.objwww.pr.control.infrastructure.observability.StructuredLog;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.transaction.support.TransactionOperations;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * DeterministicSupervisor（AM4 M4-26）：固定执行链的确定性推进器——
 * PLAN（{@link PlanCompiler} 双设防编译落库）→ 并行调查（DAG 任务，执行器族负责）→
 * REDUCE → VERIFY≤1（编译期已约束）→ ASSEMBLE → VALIDATE → PUBLISH。
 * 模型无调度权（INV-AM4-2）：本类只做编译、推进与链阶段状态迁移，全部确定性代码。
 *
 * <p>两个入口，均为幂等重驱动（验收②恢复测试的语义基础）：
 * <ul>
 *   <li>{@link #startRun} —— 启动：编译提案落任务图并放行根任务；run 已有任务时跳过编译
 *       只续驱（编译单事务，崩溃缝隙要么全有要么全无）；提案非法 → run FAILED
 *       （errorClass={@value #REJECT_ERROR_CLASS}）零落图 fail-closed；</li>
 *   <li>{@link #advance} —— 推进：终态回执后的图收敛（DagPromoter 不动点）+
 *       全任务终态 → run REPORTING（报告链阶段 REDUCE/ASSEMBLE/VALIDATE/PUBLISH 的
 *       消费点 = M4-27+ 执行器族与 M4-31+ 报告落档，不在本批）。</li>
 * </ul>
 *
 * <p>generation fence（INV-AM4-4）：run 不在活跃集（终态/被新代际取代）→ 零推进零迁移，
 * 死 run 的工作不复活。推进与迁移间的窗口无锁——即使竞态放行，claimNext 的活跃 run 栅栏
 * 与收尾 STALE 栅栏仍拦住执行面，不产生越权产出。
 *
 * <p>任务 SLA：编译出生 deadlineAt=Instant.MAX（永不到期）——链上 BLOCKED 等待期不计
 * SLA，过期语义（overdue 排序/EXPIRED）由 run 级策略面在执行器族接线时演进，本类不发明。
 * markRunRunning 不在本类：仍由 worker 首次领取路径负责（AM1 语义不变）。
 */
public class DeterministicSupervisor {

    private static final Logger log = LoggerFactory.getLogger(DeterministicSupervisor.class);

    /** 提案被确定性拒绝时落在 run.lastError 的封闭错误类 */
    public static final String REJECT_ERROR_CLASS = "PLAN_REJECTED";

    // ---------------------------------------------------------------- R7-X4/X11

    /** 委派批上限（§三 max_delegation_batches=2）与单批请求上限（一批 ≤2 个请求） */
    public static final int MAX_DELEGATION_BATCHES = 2;
    public static final int MAX_REQUESTS_PER_BATCH = 2;

    /** 拒绝原因封闭码（台账 reject_reason；裁决是确定性代码的输出，不发明自由文本） */
    public static final String REJ_BATCH_SHAPE = "BATCH_SHAPE";
    public static final String REJ_DELEGATION_BUDGET_EXHAUSTED = "DELEGATION_BUDGET_EXHAUSTED";
    public static final String REJ_GAP_ALREADY_ADJUDICATED = "GAP_ALREADY_ADJUDICATED";
    public static final String REJ_DUPLICATE_GAP_IN_BATCH = "DUPLICATE_GAP_IN_BATCH";
    public static final String REJ_ROLE_UNKNOWN = "ROLE_UNKNOWN";
    public static final String REJ_RUN_TASK_CAP = "RUN_TASK_CAP";

    /** 启动结果（观测/测试断言） */
    public enum StartOutcome {STARTED, ALREADY_STARTED, PROPOSAL_REJECTED}

    public record StartResult(StartOutcome outcome, String proposalDigest, String rejectReason) {
    }

    /** 推进结果：本轮放行/收敛的任务 UUID 集 + 是否本次进入 REPORTING */
    public record Advancement(Set<String> readied, Set<String> skippedConverged,
                              boolean reportingEntered) {
    }

    private final PlanCompiler compiler;
    private final DagExecutionService dag;
    private final RcaRunRepository runs;
    private final RcaTaskRepository tasks;
    private final TaskExecutionBindingRepository bindings;
    private final PrimaryCheckpointRepository checkpoints;
    private final DelegationDecisionRepository delegationDecisions;
    private final AgentRegistry agents;
    private final TransactionOperations tx;
    private final AlertClock clock;

    public DeterministicSupervisor(PlanCompiler compiler, DagExecutionService dag,
            RcaRunRepository runs, RcaTaskRepository tasks,
            TaskExecutionBindingRepository bindings,
            PrimaryCheckpointRepository checkpoints,
            DelegationDecisionRepository delegationDecisions,
            AgentRegistry agents,
            TransactionOperations tx, AlertClock clock) {
        this.compiler = Objects.requireNonNull(compiler);
        this.dag = Objects.requireNonNull(dag);
        this.runs = Objects.requireNonNull(runs);
        this.tasks = Objects.requireNonNull(tasks);
        this.bindings = Objects.requireNonNull(bindings, "bindings");
        this.checkpoints = Objects.requireNonNull(checkpoints, "checkpoints");
        this.delegationDecisions = Objects.requireNonNull(delegationDecisions, "delegationDecisions");
        this.agents = Objects.requireNonNull(agents, "agents");
        this.tx = Objects.requireNonNull(tx);
        this.clock = Objects.requireNonNull(clock);
    }

    /**
     * 启动（幂等）：活跃 run 编译提案落任务图并放行根任务；已有任务图 → 只续驱不重编译
     * （相同提案相同任务图由编译器 digest 保证，重入不产生重复任务/边）。
     *
     * @throws IllegalStateException run 不存在或已不活跃
     */
    public StartResult startRun(UUID runId, Map<String, Object> plannerOutput,
            Set<String> knownArtifacts) {
        RcaRun run = requireRun(runId);
        if (!run.state().isActive()) {
            throw new IllegalStateException(
                    "run 不活跃，拒绝启动: " + runId + "（state=" + run.state() + "）");
        }
        if (hasDagTasks(runId)) {
            advance(runId);
            return new StartResult(StartOutcome.ALREADY_STARTED, null, null);
        }
        PlanCompiler.PlanCompilation compiled;
        try {
            compiled = compiler.compile(runId, plannerOutput, knownArtifacts);
        } catch (IllegalArgumentException e) {
            rejectRun(runId, e.getMessage());
            return new StartResult(StartOutcome.PROPOSAL_REJECTED, null, e.getMessage());
        }
        advance(runId);
        return new StartResult(StartOutcome.STARTED, compiled.proposalDigest(), null);
    }

    /**
     * 推进（幂等，可任意次重入）：图收敛（BLOCKED→READY / 终局 SKIPPED）；
     * 全部任务终态且 run 仍活跃 → REPORTING（行锁下迁移，并发重入恰一次生效）。
     *
     * @throws IllegalStateException run 不存在
     */
    public Advancement advance(UUID runId) {
        RcaRun run = requireRun(runId);
        if (!run.state().isActive()) {
            return new Advancement(Set.of(), Set.of(), false);
        }
        DagPromotion promotion = dag.promoteOnTerminal(runId);
        List<RcaTask> dagTasks = dagTasks(runId);
        boolean allTerminal = !dagTasks.isEmpty() && dagTasks.stream()
                .allMatch(t -> DagTaskState.fromPersistent(t.state()).isTerminal());
        boolean reportingEntered = allTerminal && enterReporting(runId);
        return new Advancement(promotion.ready(), promotion.skipped(), reportingEntered);
    }

    /**
     * R7-X11 主模式启动（幂等）：只编译主节点（{@link PlanCompiler#compilePrimary}）
     * 并初始化检查点；已有任务图 → 只续驱不重编译不重置检查点。编译/检查点任一
     * 缺口的崩溃重驱动由本入口的幂等短路兜底。
     */
    public StartResult startPrimary(UUID runId, AgentProfile primaryProfile,
            Set<String> knownArtifacts) {
        RcaRun run = requireRun(runId);
        if (!run.state().isActive()) {
            throw new IllegalStateException(
                    "run 不活跃，拒绝启动: " + runId + "（state=" + run.state() + "）");
        }
        if (hasDagTasks(runId)) {
            advance(runId);
            return new StartResult(StartOutcome.ALREADY_STARTED, null, null);
        }
        PlanCompiler.PlanCompilation compiled;
        try {
            compiled = compiler.compilePrimary(runId, primaryProfile, knownArtifacts);
        } catch (IllegalArgumentException e) {
            rejectRun(runId, e.getMessage());
            return new StartResult(StartOutcome.PROPOSAL_REJECTED, null, e.getMessage());
        }
        UUID primaryTaskId = compiled.taskIds().get(RcaTask.PRIMARY_INVESTIGATE);
        if (checkpoints.findByTask(primaryTaskId).isEmpty()) {
            checkpoints.upsert(PrimaryCheckpoint.initial(primaryTaskId, runId, 0, clock.now()));
        }
        advance(runId);
        StructuredLog.event(log, "r7_primary_started",
                Map.of("run_id", runId.toString(),
                        "task_id", primaryTaskId.toString(),
                        "role", primaryProfile.name() + "@" + primaryProfile.version()));
        return new StartResult(StartOutcome.STARTED, compiled.proposalDigest(), null);
    }

    /**
     * R7-X4/X11 委派裁决（唯一建子任务路径，模型无调度权）：DELEGATE 批经确定性
     * 校验后逐请求 APPROVED（原子建 READY 子任务 + 冻结绑定 + 裁决行）/REJECTED
     * （封闭原因码）。全部裁决在单事务内；≥1 获批才推进检查点（batches/round/seq）
     * 并转入 WAITING_CHILDREN，全拒则状态不动（主循环继续出下一个决策）。
     *
     * <p>校验顺序（§三）：批形状 → 委派批预算 → 逐请求去重（批内/run 台账）→
     * 角色目录（requireByName 钉唯一版本，歧义=拒绝）→ run 任务上限。
     */
    public Adjudication adjudicateDelegation(UUID runId, UUID primaryTaskId,
            PrimaryDecision decision) {
        Objects.requireNonNull(decision, "decision");
        if (decision.branch() != PrimaryDecision.Branch.DELEGATE) {
            throw new IllegalArgumentException(
                    "委派裁决只接受 DELEGATE 分支，实际: " + decision.branchName());
        }
        Instant now = clock.now();
        List<DelegationDecision> recorded = new ArrayList<>();
        Set<String> persistedGaps = new HashSet<>();
        boolean batchAccepted = Boolean.TRUE.equals(tx.execute(status -> {
            PrimaryCheckpoint checkpoint = checkpoints.findByTask(primaryTaskId)
                    .orElseThrow(() -> new IllegalStateException(
                            "主任务检查点缺失: " + primaryTaskId));
            if (checkpoint.phase() != PrimaryCheckpoint.Phase.PRIMARY_READY) {
                throw new IllegalStateException("检查点相位非 PRIMARY_READY，拒绝裁决: "
                        + checkpoint.phase() + "（WAITING_CHILDREN 须先 wakePrimary）");
            }
            List<PrimaryDecision.DelegateRequest> requests = decision.delegate();
            if (requests.size() > MAX_REQUESTS_PER_BATCH) {
                recordRejections(runId, primaryTaskId, checkpoint, requests,
                        request -> REJ_BATCH_SHAPE, now, recorded, persistedGaps);
                return false;
            }
            if (checkpoint.batchesUsed() >= MAX_DELEGATION_BATCHES) {
                recordRejections(runId, primaryTaskId, checkpoint, requests,
                        request -> REJ_DELEGATION_BUDGET_EXHAUSTED, now, recorded,
                        persistedGaps);
                return false;
            }
            int newRound = checkpoint.roundId() + 1;
            int dagTaskCount = dagTasks(runId).size();
            int approvedCount = 0;
            Set<String> seenGaps = new HashSet<>();
            for (int i = 0; i < requests.size(); i++) {
                PrimaryDecision.DelegateRequest request = requests.get(i);
                String reject = null;
                AgentProfile profile = null;
                if (!seenGaps.add(request.gapId())) {
                    reject = REJ_DUPLICATE_GAP_IN_BATCH;
                } else if (delegationDecisions.findByRunAndGap(runId, request.gapId())
                        .isPresent()) {
                    reject = REJ_GAP_ALREADY_ADJUDICATED;
                } else {
                    try {
                        profile = agents.requireByName(request.roleId());
                        if (profile.phase()
                                != com.objwww.pr.control.alert.domain.agent.AgentPhase
                                        .INVESTIGATE) {
                            // 主 Agent 不是可委派专家（PRIMARY 只出决策，不接子任务）
                            reject = REJ_ROLE_UNKNOWN;
                            profile = null;
                        }
                    } catch (IllegalArgumentException e) {
                        reject = REJ_ROLE_UNKNOWN;
                    }
                }
                if (reject == null
                        && dagTaskCount + approvedCount + 1 > PlanCompiler.MAX_TASKS) {
                    reject = REJ_RUN_TASK_CAP;
                }
                if (reject != null) {
                    insertDecision(new DelegationDecision(UUID.randomUUID(), runId,
                            primaryTaskId, checkpoint.roundId(), i, request.gapId(),
                            request.roleId(), profile == null ? "" : profile.version(),
                            request.question(), DelegationDecision.Status.REJECTED,
                            reject, null, now), recorded, persistedGaps);
                    continue;
                }
                UUID childTaskId = UUID.randomUUID();
                String childKey = "DELEGATE-" + request.gapId();
                tasks.insert(new RcaTask(childTaskId, runId, childKey, RcaTaskState.READY,
                        5, now, now, Instant.MAX, null, null, 0, 0, 2, now, now, newRound));
                DelegationDecision approved = new DelegationDecision(UUID.randomUUID(),
                        runId, primaryTaskId, checkpoint.roundId(), i, request.gapId(),
                        profile.name(), profile.version(), request.question(),
                        DelegationDecision.Status.APPROVED, null, childTaskId, now);
                delegationDecisions.insert(approved);
                bindings.insert(new TaskExecutionBinding(childTaskId, runId, newRound,
                        childKey, profile.name(), profile.version(), profile.digest(),
                        agents.releaseDigest().orElse(null), null,
                        request.inputRefs(), profile.outputSchema(),
                        approved.id(), true,
                        TaskExecutionBinding.FailurePolicy.DEAD_ON_FAILURE, now));
                recorded.add(approved);
                approvedCount++;
            }
            if (approvedCount > 0) {
                checkpoints.upsert(new PrimaryCheckpoint(primaryTaskId, runId, newRound,
                        PrimaryCheckpoint.Phase.WAITING_CHILDREN, checkpoint.decisionSeq()
                                + requests.size(), checkpoint.stepsUsed(),
                        checkpoint.batchesUsed() + 1, checkpoint.inputSnapshotDigest(),
                        checkpoint.finalClaims(), checkpoint.finalMissingInformation(),
                        checkpoint.lastError(), now));
            }
            return approvedCount > 0;
        }));
        StructuredLog.event(log, "r7_delegation_adjudicated",
                Map.of("run_id", runId.toString(),
                        "task_id", primaryTaskId.toString(),
                        "requests", decision.delegate().size(),
                        "approved", recorded.stream().filter(d ->
                                d.status() == DelegationDecision.Status.APPROVED).count(),
                        "batch_accepted", batchAccepted));
        return new Adjudication(List.copyOf(recorded), batchAccepted);
    }

    /**
     * R7-X4 幂等唤醒：当前轮子任务全终态时把检查点 WAITING_CHILDREN → PRIMARY_READY
     *（CAS，可任意次重入）。子任务死亡也是终态（DEAD_ON_FAILURE 由主 Agent 在下一
     * 步看见并决策），不复活不重试——有界恢复归主 Agent 决策面。
     */
    public WakeOutcome wakePrimary(UUID runId, UUID primaryTaskId) {
        PrimaryCheckpoint checkpoint = checkpoints.findByTask(primaryTaskId)
                .orElseThrow(() -> new IllegalStateException(
                        "主任务检查点缺失: " + primaryTaskId));
        if (checkpoint.phase() != PrimaryCheckpoint.Phase.WAITING_CHILDREN) {
            return WakeOutcome.NOT_WAITING;
        }
        List<RcaTask> children = delegationDecisions
                .findByRunAndPrimaryTask(runId, primaryTaskId).stream()
                .filter(d -> d.status() == DelegationDecision.Status.APPROVED)
                .map(d -> tasks.findById(d.childTaskId()))
                .filter(java.util.Optional::isPresent)
                .map(java.util.Optional::get)
                // 只复判当前轮：裁决行记请求轮，子任务落新轮（round 隔离以任务为准）
                .filter(t -> t.roundId() == checkpoint.roundId())
                .toList();
        if (children.isEmpty() || children.stream().anyMatch(t ->
                !DagTaskState.fromPersistent(t.state()).isTerminal())) {
            return WakeOutcome.STILL_WAITING;
        }
        boolean woken = checkpoints.transitionPhase(primaryTaskId,
                PrimaryCheckpoint.Phase.WAITING_CHILDREN, PrimaryCheckpoint.Phase.PRIMARY_READY);
        return woken ? WakeOutcome.WOKEN : WakeOutcome.STILL_WAITING;
    }

    /** 裁决产物（观测/测试断言）：台账行 + 是否有获批（批被接受） */
    public record Adjudication(List<DelegationDecision> decisions, boolean batchAccepted) {
    }

    /** 唤醒结果 */
    public enum WakeOutcome {WOKEN, STILL_WAITING, NOT_WAITING}

    /** 整批同因拒绝（批形状/批预算违规——模型违约，逐请求落台账后原样拒绝） */
    private void recordRejections(UUID runId, UUID primaryTaskId, PrimaryCheckpoint checkpoint,
            List<PrimaryDecision.DelegateRequest> requests,
            java.util.function.Function<PrimaryDecision.DelegateRequest, String> reasonOf,
            Instant now, List<DelegationDecision> sink, Set<String> persistedGaps) {
        for (int i = 0; i < requests.size(); i++) {
            PrimaryDecision.DelegateRequest request = requests.get(i);
            insertDecision(new DelegationDecision(UUID.randomUUID(), runId, primaryTaskId,
                    checkpoint.roundId(), i, request.gapId(), request.roleId(), "",
                    request.question(), DelegationDecision.Status.REJECTED,
                    reasonOf.apply(request), null, now), sink, persistedGaps);
        }
    }

    /**
     * 台账写入：同事务内同 gap 只落一行（批内重复请求撞 uq(run,gap) 会中止 PG 事务，
     * 故先查本调用内已落集）；跨事务抢先由 DuplicateKeyException 兜底（以先到者为
     * 准，本副本只上报告不落行）。sink 记录本次调用的完整裁决结果（含未落行副本）。
     */
    private void insertDecision(DelegationDecision decision, List<DelegationDecision> sink,
            Set<String> persistedGaps) {
        if (!persistedGaps.add(decision.gapId())) {
            sink.add(decision);
            return;
        }
        try {
            delegationDecisions.insert(decision);
            sink.add(decision);
        } catch (DuplicateKeyException raced) {
            log.warn("委派裁决撞既成台账（gap={}），以先到者为准", decision.gapId());
            sink.add(decision);
        }
    }

    /**
     * DAG 任务面（M6-01）：剔除 driver task（NATIVE_INVESTIGATE = 铸造点预铸的
     * 执行权凭证，不参与图收敛/启动判定）——AM4 影子 run 无 driver，行为不变。
     */
    private List<RcaTask> dagTasks(UUID runId) {
        return tasks.findByRunId(runId).stream()
                .filter(t -> !t.taskKey().equals(RcaTask.NATIVE_INVESTIGATE))
                .toList();
    }

    private boolean hasDagTasks(UUID runId) {
        return !dagTasks(runId).isEmpty();
    }

    /** REPORTING 迁移：行锁串行化并发重入；run 已出活跃集则放弃（fence 优先） */
    private boolean enterReporting(UUID runId) {
        boolean entered = Boolean.TRUE.equals(tx.execute(status -> {
            RcaRun locked = runs.findByIdForUpdate(runId)
                    .orElseThrow(() -> new IllegalStateException("run 不存在: " + runId));
            if (locked.state() != RcaRunState.QUEUED && locked.state() != RcaRunState.RUNNING) {
                return false;
            }
            RcaRunStateMachine.requireTransition(locked.state(), RcaRunState.REPORTING);
            runs.update(withState(locked, RcaRunState.REPORTING, clock.now(), locked.lastError()));
            return true;
        }));
        if (entered) {
            StructuredLog.event(log, "am4_run_reporting",
                    Map.of("run_id", runId.toString()));
            log.info("run {} 全任务终态 → REPORTING（报告链组装）", runId);
        }
        return entered;
    }

    /** 提案被确定性拒绝 → run FAILED fail-closed（零任务零边已由编译器保证） */
    private void rejectRun(UUID runId, String reason) {
        tx.executeWithoutResult(status -> {
            RcaRun locked = runs.findByIdForUpdate(runId)
                    .orElseThrow(() -> new IllegalStateException("run 不存在: " + runId));
            if (!locked.state().isActive()) {
                return;
            }
            RcaRunStateMachine.requireTransition(locked.state(), RcaRunState.FAILED);
            runs.update(withState(locked, RcaRunState.FAILED, clock.now(), REJECT_ERROR_CLASS));
        });
        StructuredLog.event(log, "am4_plan_rejected",
                Map.of("run_id", runId.toString(), "reason", String.valueOf(reason)));
        log.warn("run {} 提案被确定性拒绝 → FAILED（{}）", runId, reason);
    }

    private RcaRun requireRun(UUID runId) {
        return runs.findById(runId)
                .orElseThrow(() -> new IllegalStateException("run 不存在: " + runId));
    }

    private static RcaRun withState(RcaRun run, RcaRunState state, Instant now, String error) {
        return new RcaRun(run.id(), run.incidentId(), run.generation(), run.trigger(), state,
                run.investigationHash(), run.createdAt(), now, run.startedAt(),
                state.isActive() ? null : now, error);
    }
}
