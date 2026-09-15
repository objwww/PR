package com.objwww.pr.control.alert;

import com.objwww.pr.control.alert.application.RunBudgetGate;
import com.objwww.pr.control.alert.application.tool.ToolGateway;
import com.objwww.pr.control.alert.application.tool.ToolRegistry;
import com.objwww.pr.control.alert.domain.budget.BudgetExhaustedException;
import com.objwww.pr.control.alert.domain.budget.BudgetKind;
import com.objwww.pr.control.alert.domain.budget.ReservationKey;
import com.objwww.pr.control.alert.domain.model.RcaRun;
import com.objwww.pr.control.alert.domain.model.RcaRunState;
import com.objwww.pr.control.alert.domain.model.RcaTask;
import com.objwww.pr.control.alert.domain.model.RcaTaskState;
import com.objwww.pr.control.alert.domain.model.RunPurpose;
import com.objwww.pr.control.alert.domain.model.RunTrigger;
import com.objwww.pr.control.alert.domain.statemachine.RcaTaskStateMachine;
import com.objwww.pr.control.alert.domain.tool.ToolDefinition;
import com.objwww.pr.control.alert.domain.tool.ToolControlPlaneException;
import com.objwww.pr.control.alert.domain.tool.ToolControlReason;
import com.objwww.pr.control.alert.domain.tool.ToolPolicy;
import com.objwww.pr.control.alert.domain.tool.ToolRisk;
import com.objwww.pr.control.alert.infrastructure.InMemoryRunBudgetLedger;
import com.objwww.pr.control.alert.support.AlertInMemoryStores;
import com.objwww.pr.shared.Digest;
import com.objwww.pr.shared.IllegalTransitionException;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * E0 回归不变量 v1（设计基线《方案Bv2严格执行-全量纳入设计-v2.md》§6 A 组）：
 * 把"一次性证明"变成 CI 持续证明——每条不变量在本套件内以最薄的真实执法原语
 * 直接断言，深度场景由各专属测试族承接（映射见各条 javadoc）。
 *
 * <pre>
 * A1  旧 worker 永远不能 commit          → 本类 a1 / ExA2LeaseCancelFenceIT
 * A2  stale generation 不能 commit       → R7ActionGuardAdmissionTest（generation 漂移拒绝）
 * A3  cancel-vs-finish 只有一个赢家      → 本类 a3（终态无出边）/ ExA2 取消竞态 IT
 * A4  同 incident 不能两个 active run    → 本类 a4 + V12 uq_rca_run_active_incident / AlertGenerationFenceIT
 * A5  重复 alert 不重复铸 run            → ExA4bIncidentProjectorTest（dedup 三计数语义）
 * A6  UNKNOWN 不被自动猜 success         → RcaWorkerTest（三账本悬挂 sweep）
 * A7  零预算绝不触网                     → 本类 a7 / RunBudgetGateTest / ExA1BudgetAdmissionIT
 * A8  未授权工具绝不进入 executor        → 本类 a8 / ToolGatewayTest
 * A9  dead task 最终 terminal/lost       → RunReconcilerTest（清理通道+回收）
 * A10 R2/R3 永远零副作用（Phase D 前有效）→ 本类 a10 / ToolGatewayTest
 * A11 cancel-requested ≠ cancelled      → 本类 a11（租约活不可回收重排）
 * A12 状态更新与事件同事务               → 投影/收尾路径 join-tx 结构约束（ExA4b/PostgresCheckpointLockOrderIT）
 * </pre>
 */
class RegressionInvariantsV1Test {

    private static final Instant NOW = Instant.parse("2026-09-15T10:00:00Z");
    private static final Clock FIXED = Clock.fixed(NOW, ZoneOffset.UTC);

    private final AlertInMemoryStores stores = new AlertInMemoryStores();

    // ---------------------------------------------------------------- A1

    @Test
    void a1_oldWorkerCannotCommitAfterLeaseMoved() {
        UUID taskId = insertLeasedTask(UUID.randomUUID(), "w-new", NOW.plusSeconds(300), 4);
        // 旧 worker（epoch 3 / 别名）的提交权栅栏 = 0 行 → 零落档
        assertThat(stores.tasks.requireCurrentLease(taskId, "w-old", 3)).isFalse();
        assertThat(stores.tasks.requireCurrentLease(taskId, "w-new", 3)).isFalse();
        assertThat(stores.tasks.requireCurrentLease(taskId, "w-new", 4)).isTrue();
    }

    // ---------------------------------------------------------------- A3

    @Test
    void a3_terminalTaskHasNoExitEdgesSingleWinner() {
        // 取消/完成任一先落地后，另一路径不得再改写——终态零出边（状态机为唯一迁移权威）
        assertThatThrownBy(() -> RcaTaskStateMachine.requireTransition(
                RcaTaskState.CANCELLED, RcaTaskState.DONE))
                .isInstanceOf(IllegalTransitionException.class);
        assertThatThrownBy(() -> RcaTaskStateMachine.requireTransition(
                RcaTaskState.DONE, RcaTaskState.RETRY_WAIT))
                .isInstanceOf(IllegalTransitionException.class);
        assertThatThrownBy(() -> RcaTaskStateMachine.requireTransition(
                RcaTaskState.STALE, RcaTaskState.CANCELLED))
                .isInstanceOf(IllegalTransitionException.class);
    }

    // ---------------------------------------------------------------- A4

    @Test
    void a4_activeRunPresentBlocksSecondCastForSameIncident() {
        UUID incidentId = UUID.randomUUID();
        stores.runs.insert(new RcaRun(UUID.randomUUID(), incidentId, 3, RunTrigger.INITIAL,
                RcaRunState.RUNNING, Digest.sha256Of("m"), NOW, NOW, NOW, null, null,
                RunPurpose.PRODUCTION, "ut", null));
        // 投影侧铸造闸（castRunIfFree 的唯一判据）+ DB 侧 uq_rca_run_active_incident
        // 部分唯一索引双保险——活跃 run 在场 = 不得再铸
        assertThat(stores.runs.findActiveByIncidentId(incidentId)).isPresent();
    }

    // ---------------------------------------------------------------- A7

    @Test
    void a7_zeroBudgetNeverTouchesRemote() {
        InMemoryRunBudgetLedger ledger = new InMemoryRunBudgetLedger();
        RunBudgetGate gate = new RunBudgetGate(ledger);
        UUID runId = UUID.randomUUID();
        gate.openRun(runId, Map.of(BudgetKind.TOKEN, 0L));
        AtomicInteger remote = new AtomicInteger();
        assertThatThrownBy(() -> gate.call(Map.of(BudgetKind.TOKEN, 1L),
                new ReservationKey(runId, UUID.randomUUID(), UUID.randomUUID(), 0,
                        BudgetKind.TOKEN),
                () -> {
                    remote.incrementAndGet();
                    return "reached-remote";
                }, o -> Map.of(BudgetKind.TOKEN, RunBudgetGate.Usage.of(1L)), e -> true))
                .isInstanceOf(BudgetExhaustedException.class);
        assertThat(remote.get()).as("零预算零触网").isZero();
        assertThat(ledger.entryCount()).as("零预算零预留").isZero();
    }

    // ---------------------------------------------------------------- A8

    @Test
    void a8_unauthorizedToolNeverReachesExecutor() {
        AtomicInteger remote = new AtomicInteger();
        ToolRegistry.Registration registration = new ToolRegistry.Registration(
                definition("allowed.tool", ToolRisk.R0), execution -> {
                    remote.incrementAndGet();
                    return new byte[1];
                });
        ToolGateway gateway = new ToolGateway(new ToolRegistry(List.of(registration)),
                new ToolPolicy(Set.of("allowed.tool")), Executors.newSingleThreadExecutor(),
                FIXED, null);

        // 未注册工具：UNKNOWN_TOOL，executor 零进入
        assertThatThrownBy(() -> gateway.invoke(invocation("unknown.tool", Map.of())))
                .isInstanceOf(ToolControlPlaneException.class)
                .hasFieldOrPropertyWithValue("reason", ToolControlReason.UNKNOWN_TOOL);
        // 策略白名单外：POLICY_DENIED（双闸之二），executor 零进入
        ToolGateway strict = new ToolGateway(new ToolRegistry(List.of(registration)),
                new ToolPolicy(Set.of("other.tool")), Executors.newSingleThreadExecutor(),
                FIXED, null);
        assertThatThrownBy(() -> strict.invoke(invocation("allowed.tool", Map.of())))
                .isInstanceOf(ToolControlPlaneException.class)
                .hasFieldOrPropertyWithValue("reason", ToolControlReason.POLICY_DENIED);
        assertThat(remote.get()).as("未授权零执行").isZero();
    }

    // ---------------------------------------------------------------- A10

    @Test
    void a10_riskR2NeverExecutesValidateOnly() throws Exception {
        AtomicInteger remote = new AtomicInteger();
        ToolGateway gateway = new ToolGateway(new ToolRegistry(List.of(
                new ToolRegistry.Registration(definition("write.tool", ToolRisk.R2),
                        execution -> {
                            remote.incrementAndGet();
                            return new byte[1];
                        }))),
                new ToolPolicy(Set.of("write.tool")), Executors.newSingleThreadExecutor(),
                FIXED, null);

        ToolGateway.ToolInvocationResult result =
                gateway.invoke(invocation("write.tool", Map.of("q", "x")));

        assertThat(result.kind()).isEqualTo(ToolGateway.ToolInvocationResult.Kind.VALIDATE_ONLY);
        assertThat(result.body()).isNull();
        assertThat(remote.get()).as("R2 零执行（Phase D 解锁前永久成立）").isZero();
    }

    // ---------------------------------------------------------------- A11

    @Test
    void a11_cancelRequestedTaskWithLiveLeaseIsNotReschedulable() {
        UUID taskId = insertLeasedTask(UUID.randomUUID(), "w1", NOW.plusSeconds(300), 4);
        // 租约活 = 执行身份仍在：回收/重排拒绝（cancel-requested ≠ cancelled）
        assertThat(stores.tasks.reclaimExpired(taskId, 4, NOW,
                RcaTaskState.RETRY_WAIT, NOW.plusSeconds(310))).isFalse();
        // 租约过期后才可回收（epoch 条件 + lease_until < now 同语句复核）
        assertThat(stores.tasks.reclaimExpired(taskId, 4, NOW.plusSeconds(600),
                RcaTaskState.RETRY_WAIT, NOW.plusSeconds(610))).isTrue();
    }

    // ---------------------------------------------------------------- 夹具

    private UUID insertLeasedTask(UUID runId, String owner, Instant leaseUntil, long epoch) {
        UUID taskId = UUID.randomUUID();
        stores.tasks.insert(new RcaTask(taskId, runId, RcaTask.NATIVE_INVESTIGATE,
                RcaTaskState.LEASED, 3, NOW.minusSeconds(60), NOW.minusSeconds(60),
                NOW.plusSeconds(3600), owner, leaseUntil, epoch, 1, 3,
                NOW.minusSeconds(60), NOW, 0));
        return taskId;
    }

    private static ToolDefinition definition(String name, ToolRisk risk) {
        return new ToolDefinition(name, "1.0.0",
                Map.of("type", "object",
                        "properties", Map.of("q", Map.of("type", "string"))),
                risk, 1_000, 16);
    }

    private ToolGateway.ToolInvocation invocation(String tool, Map<String, Object> args) {
        return new ToolGateway.ToolInvocation(UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), 1, tool, "1.0.0",
                "2026-09-15T00:00:00Z/2026-09-15T01:00:00Z", args, null);
    }
}
