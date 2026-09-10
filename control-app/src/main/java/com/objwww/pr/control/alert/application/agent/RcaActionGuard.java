package com.objwww.pr.control.alert.application.agent;

import com.objwww.pr.control.alert.application.RunBudgetGate;
import com.objwww.pr.control.alert.domain.agent.AgentProfile;
import com.objwww.pr.control.alert.domain.agent.RcaModelCallContext;
import com.objwww.pr.control.alert.domain.agent.RcaModelCallException;
import com.objwww.pr.control.alert.domain.agent.RcaModelOutcome;
import com.objwww.pr.control.alert.domain.budget.BudgetKind;
import com.objwww.pr.control.alert.domain.budget.ReservationKey;
import com.objwww.pr.control.alert.domain.model.RcaRun;
import com.objwww.pr.control.alert.domain.model.RcaTask;
import com.objwww.pr.control.alert.domain.repository.RcaRunRepository;
import com.objwww.pr.control.alert.domain.repository.RcaTaskRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * RCA 动作守卫（R7a-2，v2.1 §六 固定顺序的模型路径组装件）：
 * Run 活跃 → 未取消（终态即含）→ generation → leaseEpoch → deadline →
 * <b>角色版本获准</b>（绑定 digest 对注册表 requireExact，漂移=冒名顶替拒绝）→
 * <b>原子预留预算</b>（RunBudgetGate 多维准入，TOKEN 维）→ <b>写调用记录并取得
 * 发送资格</b>（RcaModelGateway 账本先行）→ 执行 → 有界读取 → 结算（usage 实扣/
 * 确证未发出 release/其余 provisional 保守占用）。取消提交后不得取得新发送资格
 * 由①②前置保证；工具路径不走本类（既有 SingleToolEvidenceAgent 入口即固定顺序，
 * 角色新增不产生第二条执行入口）。
 */
public class RcaActionGuard {

    /** 守卫拒绝封闭码（§六顺序名对齐） */
    public static final String REJ_RUN_NOT_ACTIVE = "RUN_NOT_ACTIVE";
    public static final String REJ_GENERATION_FENCE = "GENERATION_FENCE";
    public static final String REJ_LEASE_FENCE = "LEASE_FENCE";
    public static final String REJ_DEADLINE = "DEADLINE_EXCEEDED";
    public static final String REJ_ROLE_NOT_ADMITTED = "ROLE_NOT_ADMITTED";

    private final RcaRunRepository runs;
    private final RcaTaskRepository tasks;
    private final AgentRegistry agents;
    private final RunBudgetGate budgetGate;
    private final RcaModelGateway modelGateway;
    private final Clock clock;

    public RcaActionGuard(RcaRunRepository runs, RcaTaskRepository tasks,
            AgentRegistry agents, RunBudgetGate budgetGate, RcaModelGateway modelGateway,
            Clock clock) {
        this.runs = Objects.requireNonNull(runs, "runs");
        this.tasks = Objects.requireNonNull(tasks, "tasks");
        this.agents = Objects.requireNonNull(agents, "agents");
        this.budgetGate = Objects.requireNonNull(budgetGate, "budgetGate");
        this.modelGateway = Objects.requireNonNull(modelGateway, "modelGateway");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /**
     * 守卫下的模型动作（§六固定顺序）。
     *
     * @throws RcaActionGuardReject 顺序前置任一失败（零预算预留零触网）
     * @throws BudgetExhaustedException 预算准入拒绝（零触网）
     * @throws RcaModelCallException 账本/发送面失败（原因码封闭）
     */
    public RcaModelOutcome guardedModelCall(ModelAction action, String prompt, int maxTokens,
            long tokenEstimate) {
        // ① Run 活跃 + ② 未取消（isActive 覆盖 CANCELLED/FAILED/SUCCEEDED 终态族）
        RcaRun run = runs.findById(action.runId()).orElseThrow(() ->
                new RcaActionGuardReject(REJ_RUN_NOT_ACTIVE, "run 不存在: " + action.runId()));
        if (!run.state().isActive()) {
            throw new RcaActionGuardReject(REJ_RUN_NOT_ACTIVE,
                    "run 不活跃（state=" + run.state() + "），取消后不得取得新发送资格");
        }
        // ③ generation 栅栏
        if (run.generation() != action.expectedGeneration()) {
            throw new RcaActionGuardReject(REJ_GENERATION_FENCE,
                    "generation 漂移: 期望 " + action.expectedGeneration()
                            + " 实际 " + run.generation());
        }
        // ④ leaseEpoch 栅栏（任务行现值 == 持有者所见）
        RcaTask task = tasks.findById(action.taskId()).orElseThrow(() ->
                new RcaActionGuardReject(REJ_LEASE_FENCE, "任务不存在: " + action.taskId()));
        if (task.leaseEpoch() != action.expectedLeaseEpoch()) {
            throw new RcaActionGuardReject(REJ_LEASE_FENCE,
                    "租约 epoch 漂移: 期望 " + action.expectedLeaseEpoch()
                            + " 实际 " + task.leaseEpoch());
        }
        // ⑤ deadline（task 行 deadlineAt=Instant.MAX 视为无限；再与动作 deadline 取 min）
        Instant now = clock.instant();
        Instant deadline = action.deadline();
        if (!now.isBefore(task.deadlineAt()) || !now.isBefore(deadline)) {
            // 过线即无发送资格：不落模型调用记录，直接拒绝
            throw new RcaActionGuardReject(REJ_DEADLINE,
                    "deadline 已过: now=" + now + " task=" + task.deadlineAt()
                            + " action=" + deadline);
        }
        // ⑥ 角色版本获准（绑定冻结三元组对注册表精确解析，digest 漂移=拒绝）
        AgentProfile profile;
        try {
            profile = agents.requireExact(action.roleId(), action.roleVersion(),
                    action.roleDigest());
        } catch (IllegalArgumentException e) {
            throw new RcaActionGuardReject(REJ_ROLE_NOT_ADMITTED, e.getMessage());
        }
        // ⑦⑧ 原子预留预算 + 写调用记录取得发送资格 + ⑨执行 + ⑩有界读取 + ⑪结算
        ReservationKey key = new ReservationKey(action.runId(), action.taskId(),
                action.attemptId(), action.actionSeq(), BudgetKind.TOKEN);
        RcaModelCallContext ctx = new RcaModelCallContext(action.runId(), action.taskId(),
                action.attemptId(), action.actionSeq(), action.roundId(), profile.name(),
                profile.version(), profile.digest(), task.leaseEpoch(), action.configEpoch(),
                action.releaseDigest(), action.inputSnapshotDigest(), null, deadline,
                deadline, action.leaseHeartbeat());
        return budgetGate.call(
                Map.of(BudgetKind.TOKEN, tokenEstimate),
                key,
                () -> modelGateway.call(ctx, prompt, maxTokens),
                outcome -> Map.of(BudgetKind.TOKEN,
                        outcome.usageMissing()
                                ? RunBudgetGate.Usage.unmatched()
                                : RunBudgetGate.Usage.of(outcome.totalTokens())),
                e -> e instanceof RcaModelCallException rce && rce.zeroNetwork());
    }

    /** 模型动作身份（调用方由持久任务/绑定/租约面装配，禁 ThreadLocal） */
    public record ModelAction(UUID runId, UUID taskId, UUID attemptId, long actionSeq,
            int roundId, String roleId, String roleVersion, String roleDigest,
            long expectedGeneration, long expectedLeaseEpoch, Instant deadline,
            Long configEpoch, String releaseDigest, String inputSnapshotDigest,
            java.util.function.BooleanSupplier leaseHeartbeat) {
    }

    /** 守卫拒绝（§六前置失败；不携带供应商内容，可安全入日志） */
    public static final class RcaActionGuardReject extends RuntimeException {
        private final String code;

        public RcaActionGuardReject(String code, String message) {
            super(message);
            this.code = Objects.requireNonNull(code, "code");
        }

        public String code() {
            return code;
        }
    }
}
