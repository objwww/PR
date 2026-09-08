package com.objwww.pr.control.alert.application;

import com.objwww.pr.control.alert.domain.dag.DagPromotion;
import com.objwww.pr.control.alert.domain.dag.DagTaskState;
import com.objwww.pr.control.alert.domain.model.RcaRun;
import com.objwww.pr.control.alert.domain.model.RcaRunState;
import com.objwww.pr.control.alert.domain.model.RcaTask;
import com.objwww.pr.control.alert.domain.repository.RcaRunRepository;
import com.objwww.pr.control.alert.domain.repository.RcaTaskRepository;
import com.objwww.pr.control.alert.domain.statemachine.RcaRunStateMachine;
import com.objwww.pr.control.infrastructure.observability.StructuredLog;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.support.TransactionOperations;

import java.time.Instant;
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
    private final TransactionOperations tx;
    private final AlertClock clock;

    public DeterministicSupervisor(PlanCompiler compiler, DagExecutionService dag,
            RcaRunRepository runs, RcaTaskRepository tasks,
            TransactionOperations tx, AlertClock clock) {
        this.compiler = Objects.requireNonNull(compiler);
        this.dag = Objects.requireNonNull(dag);
        this.runs = Objects.requireNonNull(runs);
        this.tasks = Objects.requireNonNull(tasks);
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
