package com.objwww.pr.control.alert.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.objwww.pr.control.alert.application.agent.AgentRegistry;
import com.objwww.pr.control.alert.domain.agent.RcaModelCallLedger;
import com.objwww.pr.control.alert.domain.dag.PlanProposal;
import com.objwww.pr.control.alert.domain.event.RcaEventAppender;
import com.objwww.pr.control.alert.domain.model.ConfigSwitchRequest;
import com.objwww.pr.control.alert.domain.model.OperatorCommand;
import com.objwww.pr.control.alert.domain.model.RcaTask;
import com.objwww.pr.control.alert.domain.model.RcaTaskState;
import com.objwww.pr.control.alert.domain.repository.OperatorCommandRepository;
import com.objwww.pr.control.alert.domain.repository.RcaRunRepository;
import com.objwww.pr.control.alert.domain.repository.RcaTaskRepository;
import com.objwww.pr.control.alert.domain.repository.RunConfigEpochRepository;
import com.objwww.pr.control.release.domain.model.ConfigBundle;
import com.objwww.pr.control.release.domain.model.ReleaseQualification;
import com.objwww.pr.control.release.domain.repository.ConfigBundleRepository;
import com.objwww.pr.control.release.domain.repository.ReleaseQualificationRepository;
import com.objwww.pr.shared.Digest;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionOperations;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * 运行中热更新服务（EN-04，增强线方案 §185/§199/§225/§227/§229/§231/§235；卡面
 * "扩CommandService"沿既有命令账本复用：同一 operator_command 表、同一幂等锚
 * (run_id, command_type, idempotency_key)，本类只承载 CONFIG_SWITCH 的专有语义，
 * CommandService 的 Cancel/Hint/Feedback 依赖面零膨胀）。
 *
 * <p>两阶段（先持久化再生效）：{@link #submit} 落 PERSISTED 行后立刻做<b>快败校验</b>
 * （revision/活跃态/deadline/代际一致/目标资产与资格/兼容性——不依赖安全点的判定
 * 即刻结算，命令不悬挂在必败态），通过后 WAITING_SAFE_POINT；{@link #applyAtSafePoint}
 * 由<b>持租 driver</b> 在 round 边界续走（§225 首期安全点 = 完整 round + 无在飞模型
 * 动作 + driver 持租；调度闸 = 账本 epoch 栅栏，H04 无计数检查空窗）。
 *
 * <p>应用事务（§229 锁序）：锁 Run 行 → 校验 revision/活跃/代际/deadline → 资格
 * 事务内复验（P07 同律）→ 兼容核验（H13：DAG/输出 schema/工具权限不扩）→ 追加
 * epoch 历史（UNIQUE(run_id, config_epoch) 守卫，H05/H10 败者零副作用）→
 * CONFIG_SWITCH_APPLIED 状态事实事件（join 同事务）→ 命令推进 APPLIED → 提交。
 * 物理调用期间不持数据库锁（发送资格在账本 open 的短事务内取得）。
 */
public class RunConfigSwitchService {

    private static final ObjectMapper JSON = new ObjectMapper();

    /** 非终态任务 = 在飞工具/drive 语义（H03b 全 Run 安全点谓词） */
    private static final Set<RcaTaskState> TASK_TERMINAL = Set.of(
            RcaTaskState.DONE, RcaTaskState.CANCELLED, RcaTaskState.DEAD,
            RcaTaskState.SKIPPED, RcaTaskState.FAILED_TERMINAL, RcaTaskState.STALE);

    private final OperatorCommandRepository commands;
    private final RcaRunRepository runs;
    private final RcaTaskRepository tasks;
    private final RunConfigEpochRepository epochs;
    private final ConfigBundleRepository bundles;
    private final ReleaseQualificationRepository qualifications;
    private final AgentRegistry agents;
    private final RcaModelCallLedger ledger;
    private final RcaEventAppender appender;
    private final TransactionOperations tx;
    private final Supplier<Instant> now;

    public RunConfigSwitchService(OperatorCommandRepository commands,
            RcaRunRepository runs, RcaTaskRepository tasks,
            RunConfigEpochRepository epochs, ConfigBundleRepository bundles,
            ReleaseQualificationRepository qualifications, AgentRegistry agents,
            RcaModelCallLedger ledger, RcaEventAppender appender,
            TransactionOperations tx, Supplier<Instant> now) {
        this.commands = Objects.requireNonNull(commands, "commands");
        this.runs = Objects.requireNonNull(runs, "runs");
        this.tasks = Objects.requireNonNull(tasks, "tasks");
        this.epochs = Objects.requireNonNull(epochs, "epochs");
        this.bundles = Objects.requireNonNull(bundles, "bundles");
        this.qualifications = Objects.requireNonNull(qualifications, "qualifications");
        this.agents = Objects.requireNonNull(agents, "agents");
        this.ledger = Objects.requireNonNull(ledger, "ledger");
        this.appender = Objects.requireNonNull(appender, "appender");
        this.tx = Objects.requireNonNull(tx, "tx");
        this.now = Objects.requireNonNull(now, "now");
    }

    /** 结果面：state=终态或 WAITING_SAFE_POINT；reason=拒绝/等待/过期的人读原因（可查询） */
    public record Result(UUID commandId, OperatorCommand.State state, boolean replayed,
            String reason) {
    }

    // ------------------------------------------------------------ 提交/应用面

    /**
     * §227 提交（先持久化再生效）：同幂等键重放返回原命令行（含原拒绝态）；
     * 快败校验即结算，通过则 WAITING_SAFE_POINT 等 driver 应用。
     */
    public Result submit(UUID runId, String idempotencyKey, ConfigSwitchRequest request,
            String actor) {
        Optional<OperatorCommand> existing =
                commands.find(runId, OperatorCommand.Type.CONFIG_SWITCH, idempotencyKey);
        if (existing.isPresent()) {
            return settle(existing.get(), true);
        }
        OperatorCommand fresh = new OperatorCommand(UUID.randomUUID(), runId,
                OperatorCommand.Type.CONFIG_SWITCH, idempotencyKey,
                request.expectedRevision(), request.toPayload(),
                OperatorCommand.State.PERSISTED, actor, now.get(), null);
        try {
            commands.insert(fresh);
        } catch (org.springframework.dao.DuplicateKeyException e) {
            OperatorCommand winner = commands.find(runId,
                    OperatorCommand.Type.CONFIG_SWITCH, idempotencyKey)
                    .orElseThrow(() -> new IllegalStateException(
                            "uq 冲突后命令行不可见: " + runId + "/" + idempotencyKey));
            return settle(winner, true);
        }
        return evaluate(fresh, ConfigSwitchRequest.fromPayload(fresh.payload()),
                Optional.empty());
    }

    /**
     * driver 安全点应用（H01/H08/H09）：持租 driver 在 round 边界对既有命令续走
     * §229 应用事务；终态行原样返回（H06/H15 幂等）。
     */
    public Result applyAtSafePoint(UUID runId, String idempotencyKey, UUID driverTaskId,
            long driverLeaseEpoch) {
        OperatorCommand cmd = commands.find(runId, OperatorCommand.Type.CONFIG_SWITCH,
                idempotencyKey).orElseThrow(() -> new IllegalStateException(
                "切换命令不存在（须先 submit）: " + runId + "/" + idempotencyKey));
        if (cmd.state().isTerminal()) {
            return settle(cmd, true);
        }
        return evaluate(cmd, ConfigSwitchRequest.fromPayload(cmd.payload()),
                Optional.of(new DriverContext(driverTaskId, driverLeaseEpoch)));
    }

    /** H15：WAITING 命令运维撤回——待命令不再应用（终态后幂等重放原样返回） */
    public Result cancelWaiting(UUID runId, String idempotencyKey, String by,
            String reason) {
        OperatorCommand cmd = commands.find(runId, OperatorCommand.Type.CONFIG_SWITCH,
                idempotencyKey).orElseThrow(() -> new IllegalStateException(
                "切换命令不存在: " + runId + "/" + idempotencyKey));
        if (cmd.state().isTerminal()) {
            return settle(cmd, true);
        }
        boolean advanced = tx.execute((TransactionCallback<Boolean>) status ->
                commands.advanceState(cmd.id(), cmd.state(),
                        OperatorCommand.State.CANCELLED, null));
        return advanced
                ? new Result(cmd.id(), OperatorCommand.State.CANCELLED, false,
                "撤回: " + reason + "（by " + by + "）")
                : settle(reload(cmd), true);
    }

    /** H14 巡回面：deadline 已过的 WAITING 行翻 EXPIRED（调度接线归部署窗） */
    public List<OperatorCommand> expireOverdue() {
        List<OperatorCommand> flipped = new java.util.ArrayList<>();
        for (OperatorCommand cmd : commands.findWaitingOverdue(now.get())) {
            if (commands.advanceState(cmd.id(), OperatorCommand.State.WAITING_SAFE_POINT,
                    OperatorCommand.State.EXPIRED, now.get())) {
                flipped.add(cmd.withState(OperatorCommand.State.EXPIRED, now.get()));
            }
        }
        return flipped;
    }

    // ------------------------------------------------------------ 查询/标记面

    /** H16：MIXED_CONFIG 判定（§235）——代际史 >1 行 = 混合版本 Run */
    public boolean mixedConfig(UUID runId) {
        return epochs.history(runId).size() > 1;
    }

    /** H16 报告列 epoch/轮次的行源（§185 config_epoch→release_digest 追加史） */
    public List<RunConfigEpochRepository.EpochRow> history(UUID runId) {
        return epochs.history(runId);
    }

    // ------------------------------------------------------------ 内部

    /** §229 应用事务：run 行锁先行（与账本 open、事件追加共享锁序，无死锁无空窗） */
    private Result evaluate(OperatorCommand cmd, ConfigSwitchRequest request,
            Optional<DriverContext> driver) {
        return tx.execute((TransactionCallback<Result>) status -> {
            // ① 锁 Run 行（§229 锁序统一：findByIdForUpdate FOR UPDATE）+ 活跃守卫
            // （H07：Cancel 先落 = Run 终态后无新资格）
            var lockedRun = runs.findByIdForUpdate(cmd.runId()).orElse(null);
            if (lockedRun == null) {
                return reject(cmd, OperatorCommand.State.REJECTED_FORBIDDEN,
                        "run 不存在");
            }
            if (!lockedRun.state().isActive()) {
                return reject(cmd, OperatorCommand.State.REJECTED_FORBIDDEN,
                        "Run 已终态（取消后无新资格）");
            }
            // ② 修订锚（M4-10 last_event_seq；陈旧 = 零副作用 REJECTED_STALE）
            long revision = runs.currentRevision(cmd.runId()).orElse(-1);
            if (revision != request.expectedRevision()) {
                return reject(cmd, OperatorCommand.State.REJECTED_STALE,
                        "expectedRevision 陈旧（期望 " + request.expectedRevision()
                                + " 实际 " + revision + "）");
            }
            // ④ 命令期限（H14：deadline 前无安全点 = EXPIRED 可查询）
            if (!now.get().isBefore(request.deadline())) {
                return reject(cmd, OperatorCommand.State.EXPIRED,
                        "命令 deadline 已过（" + request.deadline() + "）仍未达安全点");
            }
            // ⑤ 代际面：无史 fail-closed；expectedConfigEpoch 与当前代际一致才可切
            RunConfigEpochRepository.EpochRow current =
                    epochs.findCurrent(cmd.runId()).orElse(null);
            if (current == null) {
                return reject(cmd, OperatorCommand.State.REJECTED_FORBIDDEN,
                        "run 无配置代际史（EN-04 前铸造的存量 run 不支持切换）");
            }
            if (current.configEpoch() != request.expectedConfigEpoch()) {
                return reject(cmd, OperatorCommand.State.REJECTED_STALE,
                        "expectedConfigEpoch 陈旧（期望 " + request.expectedConfigEpoch()
                                + " 实际 " + current.configEpoch() + "）");
            }
            long targetEpoch = current.configEpoch() + 1;
            // ⑥ 目标资产在场 + ⑦ 资格事务内复验（P07 同律；H15 撤销即拒）
            Digest targetDigest = new Digest(request.targetReleaseDigest());
            if (bundles.findByDigest(targetDigest).isEmpty()) {
                return reject(cmd, OperatorCommand.State.REJECTED_FORBIDDEN,
                        "目标 bundle 缺失（资产不可达）: " + request.targetReleaseDigest());
            }
            ReleaseQualification qualification =
                    qualifications.findUnrevokedFor(targetDigest).orElse(null);
            if (qualification == null || !qualification.passQualified()) {
                return reject(cmd, OperatorCommand.State.REJECTED_FORBIDDEN,
                        "目标版本资格复验失败（无未撤销 PASS 证明，撤销不能被冻结掩盖）");
            }
            // ⑧ 兼容核验（H13：DAG/输出 schema/工具权限不扩，否则关联新调查）
            String incompatible = compatibility(current.releaseDigest(),
                    request.targetReleaseDigest());
            if (incompatible != null) {
                return reject(cmd, OperatorCommand.State.REJECTED_FORBIDDEN, incompatible);
            }
            // ⑨ 安全点（§225）——driver 上下文缺席（submit 快败段）只到 WAITING
            if (driver.isEmpty()) {
                advanceToWaiting(cmd);
                return new Result(cmd.id(), OperatorCommand.State.WAITING_SAFE_POINT,
                        false, "已受理，等待 driver 安全点（完整 round + 无在飞动作 + 持租）");
            }
            String unsafe = safePointBlocker(cmd.runId(), driver.get());
            if (unsafe != null) {
                advanceToWaiting(cmd);
                return new Result(cmd.id(), OperatorCommand.State.WAITING_SAFE_POINT,
                        false, unsafe);
            }
            // ⑩ 追加代际史（UNIQUE(run_id, config_epoch) 守卫：并发胜者已在 = 败者）
            if (!epochs.append(cmd.runId(), targetEpoch,
                    request.targetReleaseDigest(), cmd.id(), cmd.actor(),
                    request.reason())) {
                return reject(cmd, OperatorCommand.State.REJECTED_STALE,
                        "并发切换已应用（epoch " + targetEpoch + " 已在史），不跳两级");
            }
            // ⑪ 状态事实事件（join 同事务，H10 提交前杀=全回滚）
            appender.append(cmd.runId(), new RcaEventAppender.EventDraft(
                    switchEventId(cmd.id()), "CONFIG_SWITCH_APPLIED",
                    eventJson(cmd, targetEpoch, request.targetReleaseDigest())));
            // ⑫ 命令推进 APPLIED（同事务；0 行 = 并发已收敛，整体回滚保单写者）
            if (!commands.advanceState(cmd.id(), cmd.state(),
                    OperatorCommand.State.APPLIED, now.get())) {
                status.setRollbackOnly();
                return settle(reload(cmd), true);
            }
            return new Result(cmd.id(), OperatorCommand.State.APPLIED, false, null);
        });
    }

    /** §225 首期安全点谓词：返回 null = 达安全点；否则返回阻塞原因（WAITING 依据） */
    private String safePointBlocker(UUID runId, DriverContext driver) {
        // ① driver 持租（H08：失租者不能应用；新 driver 重领后按持久命令恢复）
        RcaTask task = tasks.findById(driver.taskId()).orElse(null);
        Instant nowAt = now.get();
        if (task == null || !task.runId().equals(runId)
                || task.leaseOwner() == null || task.leaseUntil() == null
                || !nowAt.isBefore(task.leaseUntil())
                || task.leaseEpoch() != driver.leaseEpoch()
                || !(task.state() == RcaTaskState.RUNNING
                || task.state() == RcaTaskState.LEASED)) {
            return "driver 租约无效（失租/漂移，H08：旧 driver 不能应用）";
        }
        // ② 完整 round：非 driver 任务全终态（在飞工具/drive 阻塞）
        boolean siblingActive = tasks.findByRunId(runId).stream()
                .anyMatch(t -> !t.id().equals(driver.taskId())
                        && !TASK_TERMINAL.contains(t.state()));
        if (siblingActive) {
            return "尚有非终态任务（完整 round 未结束）";
        }
        // ③ 无在飞模型动作（PENDING/UNKNOWN 双态保守读；H02/H03/H11）
        if (!ledger.findUnsettledByRun(runId).isEmpty()) {
            return "尚有在飞模型动作（PENDING/UNKNOWN 未结算）";
        }
        return null;
    }

    /**
     * H13 兼容核验：目标提案对当前代际提案——DAG 节点集/边集一致、同名角色、输出
     * schema 等同、工具权限不扩。漂移即拒（关联新调查），不静默转换。返回 null = 兼容。
     */
    private String compatibility(String currentDigestHex, String targetDigestHex) {
        ConfigBundle current = bundles.findByDigest(new Digest(currentDigestHex))
                .orElse(null);
        if (current == null) {
            return "当前代际 bundle 不可读，兼容性无法核验: " + currentDigestHex;
        }
        PlanProposal currentProposal;
        PlanProposal targetProposal;
        try {
            currentProposal = PlanProposal.parse(proposalOf(current, currentDigestHex));
            targetProposal = PlanProposal.parse(
                    proposalOf(bundles.findByDigest(new Digest(targetDigestHex))
                            .orElseThrow(), targetDigestHex));
        } catch (IllegalArgumentException e) {
            return "目标提案不可解析（" + e.getMessage() + "），关联新调查";
        }
        if (!keysOf(currentProposal).equals(keysOf(targetProposal))
                || !edgesOf(currentProposal).equals(edgesOf(targetProposal))) {
            return "目标变更超范围（DAG 形状漂移），原 Run 切换拒绝——关联新调查";
        }
        for (PlanProposal.PlanTask targetTask : targetProposal.tasks()) {
            PlanProposal.PlanTask currentTask = currentProposal.tasks().stream()
                    .filter(t -> t.key().equals(targetTask.key())).findFirst()
                    .orElseThrow();
            int targetAt = targetTask.type().indexOf('@');
            int currentAt = currentTask.type().indexOf('@');
            if (!targetTask.type().substring(0, targetAt)
                    .equals(currentTask.type().substring(0, currentAt))) {
                return "目标变更超范围（节点 " + targetTask.key() + " 角色漂移），关联新调查";
            }
            com.objwww.pr.control.alert.domain.agent.AgentProfile targetProfile;
            com.objwww.pr.control.alert.domain.agent.AgentProfile currentProfile;
            try {
                targetProfile = agents.require(
                        targetTask.type().substring(0, targetAt),
                        targetTask.type().substring(targetAt + 1));
                currentProfile = agents.require(
                        currentTask.type().substring(0, currentAt),
                        currentTask.type().substring(currentAt + 1));
            } catch (IllegalArgumentException e) {
                return "角色不可解析（" + e.getMessage() + "），关联新调查";
            }
            if (!currentProfile.toolAllowlist().containsAll(targetProfile.toolAllowlist())) {
                return "目标变更超范围（扩工具权限），原 Run 切换拒绝——关联新调查";
            }
            if (!currentProfile.outputSchema().equals(targetProfile.outputSchema())) {
                return "目标变更超范围（输出 schema 变更），原 Run 切换拒绝——关联新调查";
            }
        }
        return null;
    }

    private static Map<String, Object> proposalOf(ConfigBundle bundle, String digestHex) {
        if (!(bundle.content().get("native") instanceof Map<?, ?> nativeSection)
                || !(nativeSection.get("proposal") instanceof Map<?, ?> raw)) {
            throw new IllegalArgumentException(
                    "bundle 缺 native.proposal 段: " + digestHex);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        for (Map.Entry<?, ?> e : raw.entrySet()) {
            out.put(String.valueOf(e.getKey()), e.getValue());
        }
        return out;
    }

    private static Set<String> keysOf(PlanProposal proposal) {
        Set<String> keys = new LinkedHashSet<>();
        proposal.tasks().forEach(t -> keys.add(t.key()));
        return keys;
    }

    private static Set<String> edgesOf(PlanProposal proposal) {
        Set<String> edges = new LinkedHashSet<>();
        proposal.edges().forEach(e -> edges.add(e.from() + "->" + e.to()
                + ":" + e.dependency()));
        return edges;
    }

    private void advanceToWaiting(OperatorCommand cmd) {
        if (cmd.state() == OperatorCommand.State.PERSISTED) {
            commands.advanceState(cmd.id(), cmd.state(),
                    OperatorCommand.State.WAITING_SAFE_POINT, null);
        }
    }

    private Result reject(OperatorCommand cmd, OperatorCommand.State state,
            String reason) {
        commands.advanceState(cmd.id(), cmd.state(), state, now.get());
        return new Result(cmd.id(), state, false, reason);
    }

    private Result settle(OperatorCommand row, boolean replayed) {
        return new Result(row.id(), row.state(), replayed, null);
    }

    private OperatorCommand reload(OperatorCommand cmd) {
        return commands.find(cmd.runId(), cmd.type(), cmd.idempotencyKey())
                .orElse(cmd);
    }

    /** 事件幂等锚：确定性 eventId——同命令重放零重复事件（H06 DB 面兜底） */
    private static UUID switchEventId(UUID commandId) {
        return UUID.nameUUIDFromBytes(("en04-config-switch:" + commandId).getBytes(
                java.nio.charset.StandardCharsets.UTF_8));
    }

    private static String eventJson(OperatorCommand cmd, long targetEpoch,
            String targetDigest) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("target_epoch", targetEpoch);
        payload.put("target_release_digest", targetDigest);
        payload.put("source_command_id", cmd.id().toString());
        payload.put("applied_by", cmd.actor());
        try {
            return JSON.writeValueAsString(payload);
        } catch (Exception e) {
            throw new IllegalStateException("切换事件序列化失败", e);
        }
    }

    /** driver 应用上下文（持租身份；§225 driver 持租 = 安全点第三条件） */
    private record DriverContext(UUID taskId, long leaseEpoch) {
    }
}
