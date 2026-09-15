package com.objwww.pr.control.alert.application.agent;

import com.objwww.pr.control.alert.application.AlertClock;
import com.objwww.pr.control.alert.domain.agent.PrimaryCheckpoint;
import com.objwww.pr.control.alert.domain.model.RcaRun;
import com.objwww.pr.control.alert.domain.model.RcaTask;
import com.objwww.pr.control.alert.domain.repository.PrimaryCheckpointRepository;
import com.objwww.pr.control.alert.domain.repository.RcaRunRepository;
import com.objwww.pr.control.alert.domain.repository.RcaTaskRepository;
import com.objwww.pr.control.alert.domain.repository.RunConfigEpochRepository;
import com.objwww.pr.control.infrastructure.observability.StructuredLog;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.support.TransactionOperations;

import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;

/**
 * 检查点提交围栏（CL-01，告警-Agent闭环修复 v1 §2）：运行路径唯一合法的检查点
 * 提交口——"短事务准入 → 外部执行 → 短事务提交"的提交半区。事务内按
 * <b>task → run → checkpoint</b> 统一锁序锁读并校验执行资格（WC-1：与 finishTask/
 * expireRun 的 task→run 全链一致），再以 revision 条件写
 * （影响行数必须 1）原子推进，任何失败回滚、调用者拿 STALE 结果立即退出本次驱动。
 *
 * <p>四类身份分型不可互代：leaseEpoch=执行所有权（task 表为事实源）、configEpoch=
 * 运行配置、checkpoint revision=提交修订、decisionSeq=业务决策序。本服务校验前两者，
 * 推进第三者，绝不触碰第四者的语义。
 *
 * <p>动作身份（actionKey）= 已取得执行资格的逻辑动作身份：重复提交先查该动作是否
 * 已落结果——相同动作返回 REPLAYED（当前检查点原样返回）；相同动作不同结果记一致性
 * 错误后仍按 REPLAYED 收敛（不覆盖既成结果）。仅覆盖最近一动作；更早的陈旧重放由
 * revision 栅栏拦截（STALE_REVISION）。
 *
 * <p>ponytail: 租约过期校验用应用时钟（AlertClock）——worker 与 PG 同机部署偏差
 * 毫秒级；出现跨机时钟漂移时把过期谓词下沉为 updateGuarded 的 now() 条件。
 */
public class PrimaryCheckpointCommitService {

    private static final Logger log =
            LoggerFactory.getLogger(PrimaryCheckpointCommitService.class);

    /** 提交围栏：谁（owner/leaseEpoch）以哪份配置（configEpoch）基于哪一修订提交 */
    public record CommitFence(UUID runId, UUID taskId, String owner,
                              long leaseEpoch, Long configEpoch, long expectedRevision) {

        public CommitFence {
            Objects.requireNonNull(runId, "runId");
            Objects.requireNonNull(taskId, "taskId");
        }
    }

    /** 合法提交操作（mutation 限定封闭集，调用方不得整对象覆盖不相关字段） */
    public enum CommitMutation {
        /** 单步推进（decisionSeq+1、steps+1、快照/记忆/lastError 回填） */
        STEP_COMPLETED,
        /** 决策序推进（DELEGATE 全拒：决策已出零获批） */
        DECISION_ADVANCED,
        /** 委派批获批 → WAITING_CHILDREN（子任务建行与检查点推进同事务） */
        DELEGATION_COMMITTED,
        /** FINAL 提案落检查点（含确定性兜底与模型失败收敛） */
        FINAL_PROPOSED,
        /** R5 同签名失败留痕（零推进，lastError 载体） */
        ERROR_RECORDED,
        /** CL-07 摘要消费指针推进（零推进字段，仅 current_summary_id） */
        SUMMARY_CONSUMED
    }

    /** 提交状态（APPLIED/REPLAYED 之外的每一态都要求调用者立即退出本次驱动） */
    public enum CommitStatus {
        APPLIED, REPLAYED, STALE_OWNER, STALE_REVISION, CONFIG_CHANGED, RUN_TERMINAL
    }

    public record CheckpointCommitResult(CommitStatus status, PrimaryCheckpoint checkpoint) {
    }

    /** 围栏拒绝（STALE/CONFIG_CHANGED/RUN_TERMINAL）：作为异常穿透事务触发回滚 */
    public static final class CommitRejectedException extends RuntimeException {

        private final CommitStatus status;

        public CommitRejectedException(CommitStatus status, String detail) {
            super(status + ": " + detail);
            this.status = status;
        }

        public CommitStatus status() {
            return status;
        }
    }

    private final RcaRunRepository runs;
    private final RcaTaskRepository tasks;
    private final PrimaryCheckpointRepository checkpoints;
    private final RunConfigEpochRepository epochs;
    private final AlertClock clock;
    private final TransactionOperations tx;
    /** CL-03 §3.1/§5.2：STEP_COMPLETED 提交事务内 append 候选记忆（可空=无持久面） */
    private final com.objwww.pr.control.alert.domain.repository.WorkingMemoryPort
            workingMemory;
    /** WC-5：迟到提交拒绝计数（STALE 族/RUN_TERMINAL；可空面 = NOOP） */
    private final com.objwww.pr.control.infrastructure.observability.AlertMetrics metrics;
    /** PA-A1：有效进展回写面（可空=未装配，进度观测关闭）。APPLIED 的推进型提交
     *  在同一事务内回写 rca_attempt.last_meaningful_progress_at——进展与状态推进
     *  原子成立，杜绝"检查点已推进而 progress 滞后"的假 stuck */
    private final com.objwww.pr.control.alert.domain.repository.RcaAttemptRepository attempts;

    /** 推进型提交（ERROR_RECORDED/SUMMARY_CONSUMED 是零推进留痕/指针移位，不算进展） */
    private static final java.util.Set<CommitMutation> ADVANCE_MUTATIONS = java.util.Set.of(
            CommitMutation.STEP_COMPLETED, CommitMutation.DECISION_ADVANCED,
            CommitMutation.DELEGATION_COMMITTED, CommitMutation.FINAL_PROPOSED);

    public PrimaryCheckpointCommitService(RcaRunRepository runs, RcaTaskRepository tasks,
            PrimaryCheckpointRepository checkpoints, RunConfigEpochRepository epochs,
            AlertClock clock, TransactionOperations tx) {
        this(runs, tasks, checkpoints, epochs, clock, tx, null);
    }

    public PrimaryCheckpointCommitService(RcaRunRepository runs, RcaTaskRepository tasks,
            PrimaryCheckpointRepository checkpoints, RunConfigEpochRepository epochs,
            AlertClock clock, TransactionOperations tx,
            com.objwww.pr.control.alert.domain.repository.WorkingMemoryPort workingMemory) {
        this(runs, tasks, checkpoints, epochs, clock, tx, workingMemory,
                com.objwww.pr.control.infrastructure.observability.AlertMetrics.NOOP);
    }

    public PrimaryCheckpointCommitService(RcaRunRepository runs, RcaTaskRepository tasks,
            PrimaryCheckpointRepository checkpoints, RunConfigEpochRepository epochs,
            AlertClock clock, TransactionOperations tx,
            com.objwww.pr.control.alert.domain.repository.WorkingMemoryPort workingMemory,
            com.objwww.pr.control.infrastructure.observability.AlertMetrics metrics) {
        this(runs, tasks, checkpoints, epochs, clock, tx, workingMemory, metrics, null);
    }

    /** PA-A1 全参形态：接 attempt 进度回写面（推进型 APPLIED 同事务回写） */
    public PrimaryCheckpointCommitService(RcaRunRepository runs, RcaTaskRepository tasks,
            PrimaryCheckpointRepository checkpoints, RunConfigEpochRepository epochs,
            AlertClock clock, TransactionOperations tx,
            com.objwww.pr.control.alert.domain.repository.WorkingMemoryPort workingMemory,
            com.objwww.pr.control.infrastructure.observability.AlertMetrics metrics,
            com.objwww.pr.control.alert.domain.repository.RcaAttemptRepository attempts) {
        this.runs = Objects.requireNonNull(runs, "runs");
        this.tasks = Objects.requireNonNull(tasks, "tasks");
        this.checkpoints = Objects.requireNonNull(checkpoints, "checkpoints");
        this.epochs = Objects.requireNonNull(epochs, "epochs");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.tx = Objects.requireNonNull(tx, "tx");
        this.workingMemory = workingMemory;
        this.metrics = Objects.requireNonNull(metrics, "metrics");
        this.attempts = attempts;
    }

    /**
     * CL-03 STEP_COMPLETED 专用提交：候选记忆（装配器无写入计算的累计产物）在
     * 提交事务内 append 深冻结并随检查点钉面 memory_id/digest。append 只发生在
     * APPLIED 之后（REPLAYED 零副作用——重驱的漂移候选被丢弃，§5.2），失败随事务
     * 整体回滚；同修订撞 (run,task,checkpoint_revision) 唯一键返回既有行——MC07
     * 幂等。CL-06 新协议行：schema_version=2、checkpoint_revision=事务内现行真
     * revision、parent_memory_id=推进前检查点所钉上一版（累计链）。
     */
    public CheckpointCommitResult commitStep(CommitFence fence, String actionKey,
            String snapshotDigest, String lastError,
            com.objwww.pr.control.alert.domain.agent.WorkingMemory candidate) {
        return tx.execute(status -> {
            // 推进前检查点（快照身份 = 真_revision，父链 = 其 memory_id）；
            // mutationFn 保持纯函数（REPLAYED 比较路径也会执行它，不得携带写副作用）
            com.objwww.pr.control.alert.domain.agent.PrimaryCheckpoint[] atAssembly =
                    new com.objwww.pr.control.alert.domain.agent.PrimaryCheckpoint[1];
            CheckpointCommitResult result = checkAndApply(fence, actionKey,
                    CommitMutation.STEP_COMPLETED, current -> {
                        atAssembly[0] = current;
                        return current.withStepAdvanced(snapshotDigest,
                                candidate == null ? null : candidate.id(),
                                candidate == null ? null : candidate.memoryDigest(),
                                lastError, clock.now());
                    });
            if (result.status() == CommitStatus.APPLIED && workingMemory != null
                    && candidate != null) {
                com.objwww.pr.control.alert.domain.agent.PrimaryCheckpoint at =
                        atAssembly[0];
                workingMemory.append(com.objwww.pr.control.alert.domain.agent
                        .WorkingMemory.ofV2(candidate.id(), candidate.runId(),
                        candidate.taskId(), at.revision(), candidate.slots(), null,
                        at.memoryId(), clock.now()));
            }
            return result;
        });
    }

    /**
     * 自含短事务提交（运行路径默认入口）：锁 run → task → checkpoint，校验资格，
     * mutationFn 生成新检查点，revision 条件写推进。事务外模型/工具调用严禁混入。
     */
    public CheckpointCommitResult commit(CommitFence fence, String actionKey,
            CommitMutation mutation, Function<PrimaryCheckpoint, PrimaryCheckpoint> mutationFn) {
        return tx.execute(status -> checkAndApply(fence, actionKey, mutation, mutationFn));
    }

    /**
     * 已在调用方事务内的围栏校验+推进（嵌入更大的同事务组合，如委派批建子任务）。
     * 拒绝以 {@link CommitRejectedException} 上抛触发调用方整体回滚。
     */
    public CheckpointCommitResult checkAndApply(CommitFence fence, String actionKey,
            CommitMutation mutation, Function<PrimaryCheckpoint, PrimaryCheckpoint> mutationFn) {
        // WC-1 真锁序：先锁 task 再锁 run——与 finishTask（LeaseFence task→run）及
        // expireRun（lockNonTerminalByRunIdForUpdate task→run）全链一致，消除
        // checkAndApply 与收尾/过期之间的 AB-BA 环（run→task 旧序 195 竞态实证风险）。
        // 锁获取序换向，校验次序不动（run 活跃优先于 task 身份——拒绝原因语义不变）。
        RcaTask task = tasks.findByIdForUpdate(fence.taskId()).orElseThrow(
                () -> new IllegalStateException("task 不存在: " + fence.taskId()));
        RcaRun run = runs.findByIdForUpdate(fence.runId()).orElseThrow(
                () -> new IllegalStateException("run 不存在: " + fence.runId()));
        // ① Run 仍活跃才可提交（Run 终止后禁止获得新执行资格）
        if (!run.state().isActive()) {
            reject(fence, actionKey, CommitStatus.RUN_TERMINAL,
                    "run state=" + run.state());
        }
        // ② task 身份与租约（task 表为事实源）
        if (!task.runId().equals(fence.runId())) {
            reject(fence, actionKey, CommitStatus.STALE_OWNER, "task 不属于该 run");
        }
        if (!Objects.equals(task.leaseOwner(), fence.owner())
                || task.leaseEpoch() != fence.leaseEpoch()) {
            reject(fence, actionKey, CommitStatus.STALE_OWNER,
                    "owner/epoch 已易主（现 owner=" + task.leaseOwner()
                            + " epoch=" + task.leaseEpoch() + "）");
        }
        if (task.leaseUntil() != null
                && task.leaseUntil().isBefore(clock.now())) {
            reject(fence, actionKey, CommitStatus.STALE_OWNER, "租约已过期");
        }
        if (com.objwww.pr.control.alert.domain.dag.DagTaskState
                .fromPersistent(task.state()).isTerminal()) {
            // 任务尚可提交校验：终态（DONE/DEAD/SKIPPED…）零提交；无租约初始化走
            // insertIfAbsent，不进本口
            reject(fence, actionKey, CommitStatus.STALE_OWNER,
                    "任务已终态（state=" + task.state() + "）");
        }
        // ③ configEpoch 一致性（运行配置身份；无代际史的存量 run 跳过）
        if (fence.configEpoch() != null) {
            Long current = epochs.findCurrent(fence.runId())
                    .map(e -> e.configEpoch()).orElse(null);
            if (current != null && !current.equals(fence.configEpoch())) {
                reject(fence, actionKey, CommitStatus.CONFIG_CHANGED,
                        "configEpoch " + fence.configEpoch() + " → " + current);
            }
        }
        // ④ 锁 checkpoint：revision + 动作重复状态
        PrimaryCheckpointRepository.CommitState state =
                checkpoints.findCommitStateForUpdate(fence.taskId());
        if (state == null) {
            throw new IllegalStateException("主任务检查点缺失: " + fence.taskId());
        }
        PrimaryCheckpoint current = state.checkpoint();
        if (current.revision() != fence.expectedRevision()) {
            reject(fence, actionKey, CommitStatus.STALE_REVISION,
                    "revision " + fence.expectedRevision() + " → " + current.revision());
        }
        if (actionKey != null && actionKey.equals(state.lastActionKey())) {
            String digest = actionDigest(mutation, mutationFn.apply(current));
            if (!digest.equals(state.lastActionDigest())) {
                // 相同动作不同结果：既成结果不动，一致性错误留痕（§2.2）
                StructuredLog.event(log, "CHECKPOINT_COMMIT_REJECTED",
                        Map.of("task_id", fence.taskId().toString(),
                                "action_key", actionKey,
                                "reason", "ACTION_RESULT_MISMATCH"));
                log.warn("同动作不同结果的一致性错误（按 REPLAYED 收敛）task={} action={}",
                        fence.taskId(), actionKey);
            }
            return new CheckpointCommitResult(CommitStatus.REPLAYED, current);
        }
        // ⑤ 生成新检查点并条件写推进（影响行数必须 1）
        PrimaryCheckpoint next = mutationFn.apply(current);
        if (next.revision() != current.revision()
                || !next.taskId().equals(current.taskId())) {
            throw new IllegalStateException(
                    "mutation 不得改动 taskId/revision: " + mutation);
        }
        long updated = checkpoints.updateGuarded(next, current.revision(), actionKey,
                actionDigest(mutation, next));
        if (updated != 1) {
            reject(fence, actionKey, CommitStatus.STALE_REVISION,
                    "条件写影响行数 " + updated);
        }
        // PA-A1：推进型提交在同一事务内回写有效进展（进度与状态推进原子成立）。
        // 回写失败（attempt 已终态等）不阻断提交——返回 false 是迟到竞争的诚实面
        if (attempts != null && ADVANCE_MUTATIONS.contains(mutation)) {
            attempts.markMeaningfulProgressByTask(fence.taskId(), clock.now());
        }
        return new CheckpointCommitResult(CommitStatus.APPLIED,
                next.withRevision(current.revision() + 1));
    }

    /**
     * 围栏校验用的动作结果摘要（同动作不同结果判定锚）。memoryId 不参与——它是
     * 每次装配新生成的身份，重放恒异；memoryDigest（槽内容的函数）才是"结果是否
     * 漂移"的判定面。
     */
    private String actionDigest(CommitMutation mutation, PrimaryCheckpoint next) {
        return com.objwww.pr.shared.Digest.sha256Of(mutation.name()
                + "|" + next.decisionSeq()
                + "|" + next.stepsUsed()
                + "|" + next.batchesUsed()
                + "|" + next.roundId()
                + "|" + next.phase()
                + "|" + (next.memoryDigest() == null ? "-" : next.memoryDigest())
                + "|" + (next.lastError() == null ? "-" : next.lastError())
                + "|" + next.finalClaims()
                + "|" + next.finalMissingInformation()
                + "|" + (next.currentSummaryId() == null ? "-" : next.currentSummaryId()))
                .value();
    }

    private void reject(CommitFence fence, String actionKey, CommitStatus status,
            String detail) {
        // WC-5：迟到提交拒绝计数（STALE 族/RUN_TERMINAL；CONFIG_CHANGED 是配置竞态
        // 不是迟到，不计入）
        if (status == CommitStatus.STALE_OWNER || status == CommitStatus.STALE_REVISION
                || status == CommitStatus.RUN_TERMINAL) {
            metrics.lateCommitRejected();
        }
        StructuredLog.event(log, "CHECKPOINT_COMMIT_REJECTED",
                Map.of("run_id", fence.runId().toString(),
                        "task_id", fence.taskId().toString(),
                        "action_key", String.valueOf(actionKey),
                        "reason", status.name()));
        throw new CommitRejectedException(status, detail);
    }

    /** UUID 缩写（日志/键辅助） */
    public static String shortId(UUID id) {
        return id == null ? "-" : id.toString().substring(0, 8);
    }
}
