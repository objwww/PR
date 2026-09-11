package com.objwww.pr.control.alert.application;

import com.objwww.pr.control.alert.domain.agent.AgentProfile;
import com.objwww.pr.control.alert.application.agent.AgentRegistry;
import com.objwww.pr.control.alert.domain.agent.RcaModelCallFenceException;
import com.objwww.pr.control.alert.domain.agent.RcaModelCallLedger;
import com.objwww.pr.control.alert.domain.budget.BudgetKind;
import com.objwww.pr.control.alert.domain.model.ConfigSwitchRequest;
import com.objwww.pr.control.alert.domain.model.OperatorCommand;
import com.objwww.pr.control.alert.domain.model.RcaEngine;
import com.objwww.pr.control.alert.domain.model.RcaRun;
import com.objwww.pr.control.alert.domain.model.RcaRunRouting;
import com.objwww.pr.control.alert.domain.model.RcaRunState;
import com.objwww.pr.control.alert.domain.model.RcaTask;
import com.objwww.pr.control.alert.domain.model.RcaTaskState;
import com.objwww.pr.control.alert.domain.model.RunTrigger;
import com.objwww.pr.control.alert.domain.repository.RunConfigEpochRepository;
import com.objwww.pr.control.alert.support.AlertInMemoryStores;
import com.objwww.pr.control.release.domain.model.ConfigBundle;
import com.objwww.pr.control.release.domain.model.ReleaseQualification;
import com.objwww.pr.control.release.domain.repository.ConfigBundleRepository;
import com.objwww.pr.control.release.domain.repository.ReleaseQualificationRepository;
import com.objwww.pr.shared.Digest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionOperations;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * EN-04 运行中热更新（H 矩阵 E 面）：epoch 历史（UNIQUE(run_id, config_epoch)，§207）、
 * 切换命令状态机 PERSISTED→WAITING_SAFE_POINT→APPLIED / REJECTED_* / CANCELLED / EXPIRED
 * （§227 命令字段入 payload）、§225 首期安全点（完整 round：非 driver 任务全终态 +
 * 账本无 PENDING/UNKNOWN + driver 持租）、§229 应用事务锁序（run 行锁先行 → 校验 →
 * 资格复验 → 兼容 → 追加历史+CONFIG_SWITCH_APPLIED 事件 → 命令推进）、H04 调度闸
 * （切换生效后旧 epoch 动作过账本 epoch 栅栏，零触网，无计数检查空窗）。
 *
 * <p>诚实边界：真 PG IT（历史唯一键/账本栅栏 SQL/事务原子性）、全链轮换（H01 下一轮
 * 真驱 v2）、真进程重启（H09）、真事务崩溃窗（H10 前后杀）——E2E 窗标 NOT_RUN。
 */
class RunConfigSwitchServiceTest {

    private static final Instant NOW = Instant.parse("2026-09-11T08:00:00Z");

    private final AlertInMemoryStores stores = new AlertInMemoryStores();
    private final FakeEpochs epochs = new FakeEpochs();
    private final FakeBundles bundles = new FakeBundles();
    private final FakeQualifications qualifications = new FakeQualifications();

    private UUID runId;
    private UUID driverTaskId;
    private String v1;
    private String v2;

    @BeforeEach
    void setUp() {
        runId = UUID.randomUUID();
        v1 = bundles.put(proposal("metrics@1", "logs@1", "change@1"));
        v2 = bundles.put(proposal("metrics@2", "logs@1", "change@1"));
        qualifications.grant(v2);

        // run 铸造已入 ROUTING（configDigest=v1）；准入播种面（insertRouted 同事务
        // 播种 epoch 0）由 Postgres IT 钉——本单测直走播种等价物 epochs.append
        stores.runs.insertRouted(runningRun(runId),
                new RcaRunRouting(RcaEngine.NATIVE, new Digest(v1), null, null,
                        "EN-04-TEST"));
        epochs.append(runId, 0, v1, null, "ADMISSION", null);

        driverTaskId = UUID.randomUUID();
        stores.tasks.insert(task(driverTaskId, runId, RcaTaskState.RUNNING, 0,
                "worker-a", NOW.plusSeconds(600), 0));
    }

    // ------------------------------------------------------------------ 用例

    @Test
    @DisplayName("H01/E：v1 完成一轮提交兼容 v2——driver 安全点应用，epoch+1 追加历史")
    void h01CompatibleSwitchAppliedAtSafePoint() {
        RunConfigSwitchService service = service();

        RunConfigSwitchService.Result submitted = submit(service, "op-1");
        assertThat(submitted.state()).isEqualTo(OperatorCommand.State.WAITING_SAFE_POINT);

        RunConfigSwitchService.Result applied =
                service.applyAtSafePoint(runId, "op-1", driverTaskId, 0);
        assertThat(applied.state()).isEqualTo(OperatorCommand.State.APPLIED);
        assertThat(epochs.history(runId)).extracting(
                        RunConfigEpochRepository.EpochRow::configEpoch,
                        RunConfigEpochRepository.EpochRow::releaseDigest)
                .containsExactly(org.assertj.core.groups.Tuple.tuple(0L, v1),
                        org.assertj.core.groups.Tuple.tuple(1L, v2));
        assertThat(stores.rcaEvents.all())
                .anySatisfy(e -> {
                    assertThat(e.eventType()).isEqualTo("CONFIG_SWITCH_APPLIED");
                    assertThat(e.payloadJson()).contains("\"target_epoch\":1").contains(v2);
                });
    }

    @Test
    @DisplayName("H02/E：模型请求阻塞时提交切换——命令 WAITING，在飞行身份面不被改写")
    void h02InFlightModelRequestKeepsCommandWaiting() {
        RcaModelCallLedger.OpenRow pending = pendingCall(0L);
        stores.modelCalls.open(pending);

        RunConfigSwitchService service = service();
        assertThat(submit(service, "op-2").state())
                .isEqualTo(OperatorCommand.State.WAITING_SAFE_POINT);

        // 已发送请求不可换 Prompt（§231）：账本行原样（旧 epoch/旧 digest/PENDING）
        var row = stores.modelCalls.all().get(0);
        assertThat(row.state()).isEqualTo("PENDING");
        assertThat(row.open().configEpoch()).isEqualTo(0L);
        assertThat(row.open().releaseDigest()).isEqualTo(v1);

        // 在飞结算 → driver 安全点重试（同幂等键续走）→ APPLIED
        stores.modelCalls.succeed(pending.id(), usage());
        assertThat(service.applyAtSafePoint(runId, "op-2", driverTaskId, 0).state())
                .isEqualTo(OperatorCommand.State.APPLIED);
    }

    @Test
    @DisplayName("H03/E：两在飞模型动作只结算一方不切换，全部结算才应用")
    void h03AllInFlightMustSettle() {
        RcaModelCallLedger.OpenRow first = pendingCall(0L);
        RcaModelCallLedger.OpenRow second = pendingCall(1L);
        stores.modelCalls.open(first);
        stores.modelCalls.open(second);

        RunConfigSwitchService service = service();
        assertThat(submit(service, "op-3").state())
                .isEqualTo(OperatorCommand.State.WAITING_SAFE_POINT);

        stores.modelCalls.succeed(first.id(), usage());
        assertThat(service.applyAtSafePoint(runId, "op-3", driverTaskId, 0).state())
                .isEqualTo(OperatorCommand.State.WAITING_SAFE_POINT);

        stores.modelCalls.succeed(second.id(), usage());
        assertThat(service.applyAtSafePoint(runId, "op-3", driverTaskId, 0).state())
                .isEqualTo(OperatorCommand.State.APPLIED);
    }

    @Test
    @DisplayName("H03b/E：兄弟任务仍 RUNNING（在飞工具/drive）——未达全 Run 安全点不切换")
    void h03bRunningSiblingTaskBlocksSafePoint() {
        UUID sibling = UUID.randomUUID();
        stores.tasks.insert(task(sibling, runId, RcaTaskState.RUNNING, 1,
                "worker-a", NOW.plusSeconds(600), 0));

        RunConfigSwitchService service = service();
        assertThat(submit(service, "op-3b").state())
                .isEqualTo(OperatorCommand.State.WAITING_SAFE_POINT);

        stores.tasks.update(terminal(sibling, 1, RcaTaskState.DONE));
        assertThat(service.applyAtSafePoint(runId, "op-3b", driverTaskId, 0).state())
                .isEqualTo(OperatorCommand.State.APPLIED);
    }

    @Test
    @DisplayName("H04/E：切换生效后旧 epoch 新动作过账本闸 = EPOCH_FENCE（零触网），无计数检查空窗")
    void h04OldEpochActionFencedAfterSwitch() {
        RunConfigSwitchService service = service();
        assertThat(submit(service, "op-4").state())
                .isEqualTo(OperatorCommand.State.WAITING_SAFE_POINT);
        assertThat(service.applyAtSafePoint(runId, "op-4", driverTaskId, 0).state())
                .isEqualTo(OperatorCommand.State.APPLIED);

        // 切换事务提交后的世界：epoch 1 已在史。旧 epoch 新动作领取发送资格 = 闸拒绝
        stores.modelCalls.currentEpochView.put(runId, 1L);
        assertThatThrownBy(() -> stores.modelCalls.open(oldEpochCall(9L)))
                .isInstanceOf(RcaModelCallFenceException.class)
                .hasMessageContaining("EPOCH_FENCE");
        // 新 epoch 动作照常入账（新调用绑新 epoch）
        stores.modelCalls.open(new RcaModelCallLedger.OpenRow(UUID.randomUUID(), runId,
                driverTaskId, UUID.randomUUID(), 10L, 1, 0, "metrics", "2", "md", "pd",
                null, null, 1L, v2, 0));
        assertThat(stores.modelCalls.all()).hasSize(1);
    }

    @Test
    @DisplayName("H05/E：两切换命令争同 epoch——仅一个 APPLIED，败者 REJECTED_STALE 不跳两级")
    void h05RacingSwitchesOnlyOneApplied() {
        RunConfigSwitchService service = service();
        assertThat(submit(service, "op-5a").state())
                .isEqualTo(OperatorCommand.State.WAITING_SAFE_POINT);
        assertThat(submit(service, "op-5b").state())
                .isEqualTo(OperatorCommand.State.WAITING_SAFE_POINT);

        assertThat(service.applyAtSafePoint(runId, "op-5a", driverTaskId, 0).state())
                .isEqualTo(OperatorCommand.State.APPLIED);
        RunConfigSwitchService.Result loser =
                service.applyAtSafePoint(runId, "op-5b", driverTaskId, 0);
        assertThat(loser.state()).isEqualTo(OperatorCommand.State.REJECTED_STALE);
        assertThat(epochs.history(runId)).hasSize(2);
    }

    @Test
    @DisplayName("H06/E：同幂等键重复提交/超时重试——相同命令结果，无重复切换事件")
    void h06SameKeyReplayReturnsOriginal() {
        RunConfigSwitchService service = service();
        RunConfigSwitchService.Result first = submit(service, "op-6");
        RunConfigSwitchService.Result replay = submit(service, "op-6");

        assertThat(replay.commandId()).isEqualTo(first.commandId());
        assertThat(replay.state()).isEqualTo(first.state());
        assertThat(replay.replayed()).isTrue();

        assertThat(service.applyAtSafePoint(runId, "op-6", driverTaskId, 0).state())
                .isEqualTo(OperatorCommand.State.APPLIED);
        assertThat(service.applyAtSafePoint(runId, "op-6", driverTaskId, 0).replayed())
                .isTrue();
        assertThat(epochs.history(runId)).hasSize(2);
        long switchEvents = stores.rcaEvents.all().stream()
                .filter(e -> e.eventType().equals("CONFIG_SWITCH_APPLIED")).count();
        assertThat(switchEvents).isEqualTo(1);
    }

    @Test
    @DisplayName("H07/E：Cancel 先落（Run 终态）——切换 REJECTED_FORBIDDEN，零历史追加")
    void h07CancelWinsSwitchRejected() {
        stores.runs.update(new RcaRun(runId, stores.runs.findById(runId).orElseThrow()
                .incidentId(), 3, RunTrigger.INITIAL, RcaRunState.CANCELLED,
                Digest.sha256Of("materials"), NOW.minusSeconds(60), NOW,
                NOW.minusSeconds(30), NOW, null));

        RunConfigSwitchService service = service();
        RunConfigSwitchService.Result result = submit(service, "op-7");
        assertThat(result.state()).isEqualTo(OperatorCommand.State.REJECTED_FORBIDDEN);
        assertThat(epochs.history(runId)).hasSize(1);
    }

    @Test
    @DisplayName("H08/E：等待期间 driver 失租——旧 driver 不能应用，新 driver 按持久命令恢复")
    void h08LostLeaseCannotApply() {
        RcaModelCallLedger.OpenRow pending = pendingCall(0L);
        stores.modelCalls.open(pending);
        RunConfigSwitchService service = service();
        // 命令期限长于租约生命周期（本例只考失租，不考 deadline 过期——那是 H14）
        service.submit(runId, "op-8",
                new ConfigSwitchRequest(0, 0, v2, "切换 op-8", NOW.plusSeconds(72_000)),
                "op");

        // 旧 driver 在租约过期后（时钟前移 700s > leaseUntil=NOW+600s）仍来应用：
        // 保持 WAITING（不应用、不追加历史）——H08 失租者不能应用
        stores.modelCalls.succeed(pending.id(), usage());
        Instant later = NOW.plusSeconds(3_600);
        assertThat(clock(NOW.plusSeconds(700))
                .applyAtSafePoint(runId, "op-8", driverTaskId, 0).state())
                .isEqualTo(OperatorCommand.State.WAITING_SAFE_POINT);
        assertThat(epochs.history(runId)).hasSize(1);

        // 新 driver 重领（leaseEpoch+1、新租约）→ 按持久命令续走 APPLIED
        stores.tasks.update(task(driverTaskId, runId, RcaTaskState.RUNNING, 0,
                "worker-b", later.plusSeconds(600), 1));
        assertThat(clock(NOW.plusSeconds(700))
                .applyAtSafePoint(runId, "op-8", driverTaskId, 1).state())
                .isEqualTo(OperatorCommand.State.APPLIED);
    }

    @Test
    @DisplayName("H09/E：REQUESTED 落库后 worker 读取前'重启'——持久行续走 apply，命令不丢")
    void h09PersistedCommandSurvivesRestart() {
        // phase1 只落库（submit 后、生效前进程终止的窗口等价物）
        stores.commands.insert(new OperatorCommand(UUID.randomUUID(), runId,
                OperatorCommand.Type.CONFIG_SWITCH, "op-9", 0,
                request("op-9", v2).toPayload(), OperatorCommand.State.PERSISTED,
                "op", NOW, null));

        RunConfigSwitchService restarted = service();
        assertThat(restarted.applyAtSafePoint(runId, "op-9", driverTaskId, 0).state())
                .isEqualTo(OperatorCommand.State.APPLIED);
        assertThat(epochs.history(runId)).hasSize(2);
    }

    @Test
    @DisplayName("H10/E：epoch 追加撞唯一键（并发胜者已在）——本次应用零事件零历史")
    void h10EpochAppendConflictRollsBack() {
        epochs.append(runId, 1, v2, UUID.randomUUID(), "race-winner", null);
        stores.commands.insert(new OperatorCommand(UUID.randomUUID(), runId,
                OperatorCommand.Type.CONFIG_SWITCH, "op-10", 0,
                request("op-10", v2).toPayload(), OperatorCommand.State.PERSISTED,
                "op", NOW, null));

        RunConfigSwitchService.Result result =
                service().applyAtSafePoint(runId, "op-10", driverTaskId, 0);
        assertThat(result.state()).isEqualTo(OperatorCommand.State.REJECTED_STALE);
        assertThat(epochs.history(runId)).hasSize(2);
        assertThat(stores.rcaEvents.all())
                .noneSatisfy(e -> assertThat(e.eventType()).isEqualTo("CONFIG_SWITCH_APPLIED"));
    }

    @Test
    @DisplayName("H11/E：旧请求迟到（UNKNOWN 回执）——保留旧 epoch 审计，安全点以对账读为准")
    void h11LateOldResponseKeepsOriginalIdentity() {
        RcaModelCallLedger.OpenRow pending = pendingCall(0L);
        stores.modelCalls.open(pending);
        RunConfigSwitchService service = service();
        assertThat(submit(service, "op-11").state())
                .isEqualTo(OperatorCommand.State.WAITING_SAFE_POINT);

        stores.modelCalls.markUnknown(pending.id());
        var row = stores.modelCalls.all().get(0);
        assertThat(row.state()).isEqualTo("UNKNOWN");
        assertThat(row.open().configEpoch()).isEqualTo(0L);
        assertThat(row.open().releaseDigest()).isEqualTo(v1);

        // UNKNOWN = 是否已执行不确定（未结算族）→ 保守阻塞安全点，恢复对账不盲重发
        assertThat(service.applyAtSafePoint(runId, "op-11", driverTaskId, 0).state())
                .isEqualTo(OperatorCommand.State.WAITING_SAFE_POINT);
    }

    @Test
    @DisplayName("H12/E：切换携带预算/期限扩展键——拒绝超范围变更；应用不延 Run 侧期限")
    void h12BudgetOrDeadlineExtensionRejected() {
        Map<String, Object> budget = request("op-12", v2).toPayload();
        budget.put("extra_budget_micros", 1_000_000L);
        assertThatThrownBy(() -> ConfigSwitchRequest.fromPayload(budget))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("超范围");
        Map<String, Object> deadline = request("op-12", v2).toPayload();
        deadline.put("extend_run_deadline", "PT1H");
        assertThatThrownBy(() -> ConfigSwitchRequest.fromPayload(deadline))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("超范围");

        // 合法切换应用后：driver 任务期限不动（切换不借道抬高/延长约束，§231）
        RunConfigSwitchService service = service();
        submit(service, "op-12-ok");
        assertThat(service.applyAtSafePoint(runId, "op-12-ok", driverTaskId, 0).state())
                .isEqualTo(OperatorCommand.State.APPLIED);
        assertThat(stores.tasks.findById(driverTaskId).orElseThrow().deadlineAt())
                .isEqualTo(Instant.MAX);
    }

    @Test
    @DisplayName("H13/E：目标改 DAG / 扩工具权限——切换拒绝并提示关联新调查，不静默转换")
    void h13IncompatibleTargetRejected() {
        RunConfigSwitchService service = service();

        // ① 改 DAG：目标节点集漂移（三任务 → 单任务）
        String v2dag = bundles.put(proposalSingle("investigate-metrics", "metrics@2"));
        qualifications.grant(v2dag);
        RunConfigSwitchService.Result dagChange = submit(service, "op-13a", v2dag);
        assertThat(dagChange.state()).isEqualTo(OperatorCommand.State.REJECTED_FORBIDDEN);
        assertThat(dagChange.reason()).contains("关联新调查");
        assertThat(epochs.history(runId)).hasSize(1);

        // ② 扩工具权限：同 DAG，metrics@3 权限集超出在跑 metrics@1
        String v3grow = bundles.put(proposal("metrics@3", "logs@1", "change@1"));
        qualifications.grant(v3grow);
        RunConfigSwitchService.Result toolGrowth = submit(service, "op-13b", v3grow);
        assertThat(toolGrowth.state()).isEqualTo(OperatorCommand.State.REJECTED_FORBIDDEN);
        assertThat(toolGrowth.reason()).contains("关联新调查");
        assertThat(epochs.history(runId)).hasSize(1);
    }

    @Test
    @DisplayName("H14/E：长期无安全点直到 deadline——EXPIRED 可查询原因；WAITING 行巡回过期")
    void h14DeadlinePassesCommandExpires() {
        // deadline 已过直接判 EXPIRED（结果面携带原因可查询）
        stores.commands.insert(new OperatorCommand(UUID.randomUUID(), runId,
                OperatorCommand.Type.CONFIG_SWITCH, "op-14", 0,
                request("op-14", v2, NOW.minusSeconds(1)).toPayload(),
                OperatorCommand.State.PERSISTED, "op", NOW, null));
        RunConfigSwitchService service = service();
        RunConfigSwitchService.Result expired =
                service.applyAtSafePoint(runId, "op-14", driverTaskId, 0);
        assertThat(expired.state()).isEqualTo(OperatorCommand.State.EXPIRED);
        assertThat(expired.reason()).contains("deadline");
        assertThat(epochs.history(runId)).hasSize(1);

        // 巡回面：WAITING 行过 deadline 批量翻 EXPIRED（调度接线在 195 窗如实标注）
        RcaModelCallLedger.OpenRow pending = pendingCall(0L);
        stores.modelCalls.open(pending);
        submit(service, "op-14-waiting");
        RunConfigSwitchService later = clock(NOW.plusSeconds(3_600));
        assertThat(later.expireOverdue())
                .extracting(OperatorCommand::idempotencyKey)
                .containsExactly("op-14-waiting");
        assertThat(later.expireOverdue()).isEmpty();
    }

    @Test
    @DisplayName("H15/E：WAITING 命令取消 / 目标版本资格紧急撤销——待命令不再应用")
    void h15CancelledCommandAndRevokedTargetStayDown() {
        RcaModelCallLedger.OpenRow pending = pendingCall(0L);
        stores.modelCalls.open(pending);
        RunConfigSwitchService service = service();
        assertThat(submit(service, "op-15").state())
                .isEqualTo(OperatorCommand.State.WAITING_SAFE_POINT);

        RunConfigSwitchService.Result cancelled =
                service.cancelWaiting(runId, "op-15", "op", "目标版本紧急回退");
        assertThat(cancelled.state()).isEqualTo(OperatorCommand.State.CANCELLED);
        stores.modelCalls.succeed(pending.id(), usage());
        RunConfigSwitchService.Result afterCancel =
                service.applyAtSafePoint(runId, "op-15", driverTaskId, 0);
        assertThat(afterCancel.state()).isEqualTo(OperatorCommand.State.CANCELLED);
        assertThat(afterCancel.replayed()).isTrue();
        assertThat(epochs.history(runId)).hasSize(1);

        // 目标被撤销后的新命令：资格复验拒绝（撤销不能被"冻结旧版本"掩盖，§235）
        qualifications.revoke(v2);
        assertThat(submit(service, "op-15b").state())
                .isEqualTo(OperatorCommand.State.REJECTED_FORBIDDEN);
    }

    @Test
    @DisplayName("H16/E：混合版本 Run——MIXED_CONFIG 可判定，epoch 历史可列（报告面消费）")
    void h16MixedConfigMarking() {
        RunConfigSwitchService service = service();
        assertThat(service.mixedConfig(runId)).isFalse();
        submit(service, "op-16");
        assertThat(service.applyAtSafePoint(runId, "op-16", driverTaskId, 0).state())
                .isEqualTo(OperatorCommand.State.APPLIED);
        assertThat(service.mixedConfig(runId)).isTrue();
        assertThat(service.history(runId)).hasSize(2);
    }

    @Test
    @DisplayName("命令形状：digest 非法/deadline 缺席拒绝；payload 双向映射稳定")
    void requestShapeGuards() {
        assertThatThrownBy(() -> new ConfigSwitchRequest(0, 0, "not-a-digest", "r",
                NOW.plusSeconds(1)))
                .isInstanceOf(IllegalArgumentException.class);
        ConfigSwitchRequest request =
                new ConfigSwitchRequest(3, 0, v2, "reason", NOW.plusSeconds(600));
        assertThat(ConfigSwitchRequest.fromPayload(request.toPayload())).isEqualTo(request);
        assertThatThrownBy(() -> new ConfigSwitchRequest(0, 0, v2, "r", null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ------------------------------------------------------------------ 造具

    private RunConfigSwitchService service() {
        return clock(NOW);
    }

    private RunConfigSwitchService clock(Instant at) {
        return new RunConfigSwitchService(stores.commands, stores.runs, stores.tasks,
                epochs, bundles, qualifications, registry(), stores.modelCalls,
                stores.rcaEvents, withoutTransaction(), () -> at);
    }

    private RunConfigSwitchService.Result submit(RunConfigSwitchService service, String key) {
        return service.submit(runId, key, request(key, v2), "op");
    }

    private RunConfigSwitchService.Result submit(RunConfigSwitchService service,
            String key, String digest) {
        return service.submit(runId, key, request(key, digest), "op");
    }

    private static ConfigSwitchRequest request(String key, String digest) {
        return request(key, digest, NOW.plusSeconds(600));
    }

    private static ConfigSwitchRequest request(String key, String digest, Instant deadline) {
        return new ConfigSwitchRequest(0, 0, digest, "切换 " + key, deadline);
    }

    private static AgentRegistry registry() {
        // metrics@2 = metrics@1 的兼容 v2（同权限/同 schema，仅 prompt/版本演进）；
        // metrics@3 权限集超出 = H13 扩权拒绝面
        return new AgentRegistry(List.of(
                profile("metrics", "1", Set.of("metrics_query")),
                profile("metrics", "2", Set.of("metrics_query")),
                profile("metrics", "3", Set.of("metrics_query", "profiles_query")),
                profile("logs", "1", Set.of("logs_query")),
                profile("change", "1", Set.of("change_query"))));
    }

    private static AgentProfile profile(String name, String version, Set<String> tools) {
        return new AgentProfile(name, version, "prompt-" + name, "pv-" + version, tools,
                Map.of(BudgetKind.STEP, 8L), Map.of("type", "object"));
    }

    private static Map<String, Object> proposal(String... types) {
        String[] keys = {"investigate-metrics", "investigate-logs", "investigate-change"};
        List<Map<String, Object>> tasks = new ArrayList<>();
        for (int i = 0; i < types.length; i++) {
            tasks.add(Map.of("key", keys[i], "type", types[i], "inputs", List.of()));
        }
        return Map.of("schema_version",
                com.objwww.pr.control.alert.domain.dag.PlanProposal.SCHEMA_VERSION,
                "tasks", tasks, "edges", List.of());
    }

    private static Map<String, Object> proposalSingle(String key, String type) {
        return Map.of("schema_version",
                com.objwww.pr.control.alert.domain.dag.PlanProposal.SCHEMA_VERSION,
                "tasks", List.of(Map.of("key", key, "type", type, "inputs", List.of())),
                "edges", List.of());
    }

    private static RcaRun runningRun(UUID id) {
        return new RcaRun(id, UUID.randomUUID(), 3, RunTrigger.INITIAL,
                RcaRunState.RUNNING, Digest.sha256Of("materials"),
                NOW.minusSeconds(60), NOW, NOW.minusSeconds(30), null, null);
    }

    private static RcaTask task(UUID id, UUID runId, RcaTaskState state, int round,
                                String owner, Instant leaseUntil, long leaseEpoch) {
        return new RcaTask(id, runId, RcaTask.NATIVE_INVESTIGATE, state, 5, NOW, NOW,
                Instant.MAX, owner, leaseUntil, leaseEpoch, 0, 2, NOW, NOW, round);
    }

    private RcaTask terminal(UUID id, int round, RcaTaskState state) {
        return task(id, runId, state, round, null, null, 0);
    }

    private RcaModelCallLedger.OpenRow pendingCall(long actionSeq) {
        return new RcaModelCallLedger.OpenRow(UUID.randomUUID(), runId, driverTaskId,
                UUID.randomUUID(), actionSeq, 1, 0, "metrics", "1", "md", "pd",
                null, null, 0L, v1, 0);
    }

    private RcaModelCallLedger.OpenRow oldEpochCall(long actionSeq) {
        return new RcaModelCallLedger.OpenRow(UUID.randomUUID(), runId, driverTaskId,
                UUID.randomUUID(), actionSeq, 1, 0, "metrics", "1", "md", "pd",
                null, null, 0L, v1, 0);
    }

    private static RcaModelCallLedger.UsageOutcome usage() {
        return new RcaModelCallLedger.UsageOutcome(10, 20, 30, false, 1L, "pv", "CNY",
                null, "route", "m", 5L, null);
    }

    private static TransactionOperations withoutTransaction() {
        return new TransactionOperations() {
            @Override
            @SuppressWarnings("unchecked")
            public <T> T execute(TransactionCallback<T> action) {
                return action.doInTransaction(null);
            }
        };
    }

    /** epoch 历史假件：pk(run_id, config_epoch) putIfAbsent 同构（H05/H10 唯一键守卫） */
    static final class FakeEpochs implements RunConfigEpochRepository {
        private final Map<UUID, ConcurrentSkipListMap<Long, EpochRow>> rows =
                new ConcurrentHashMap<>();

        @Override
        public boolean append(UUID runId, long configEpoch, String releaseDigest,
                UUID sourceCommandId, String appliedBy, String reason) {
            EpochRow row = new EpochRow(runId, configEpoch, releaseDigest,
                    sourceCommandId, appliedBy, reason, NOW);
            var perRun = rows.computeIfAbsent(runId, k -> new ConcurrentSkipListMap<>());
            return perRun.putIfAbsent(configEpoch, row) == null;
        }

        @Override
        public Optional<EpochRow> findCurrent(UUID runId) {
            var perRun = rows.get(runId);
            return perRun == null || perRun.isEmpty()
                    ? Optional.empty() : Optional.of(perRun.lastEntry().getValue());
        }

        @Override
        public List<EpochRow> history(UUID runId) {
            var perRun = rows.get(runId);
            return perRun == null ? List.of() : List.copyOf(perRun.values());
        }
    }

    /** bundle 假件：content 形状同生产（native.proposal 段），digest 由 canonical 派生 */
    static final class FakeBundles implements ConfigBundleRepository {
        private final Map<String, ConfigBundle> rows = new LinkedHashMap<>();
        private long revision = 1;

        String put(Map<String, Object> proposal) {
            Map<String, Object> content = new LinkedHashMap<>();
            content.put("policy_version", "policy-en04");
            content.put("native", Map.of("proposal", proposal));
            ConfigBundle bundle = ConfigBundle.of(content, "pub", NOW);
            rows.put(bundle.bundleDigest().hex(), bundle);
            return bundle.bundleDigest().hex();
        }

        @Override
        public long nextRevision() {
            return ++revision;
        }

        @Override
        public boolean insert(ConfigBundle bundle) {
            return rows.putIfAbsent(bundle.bundleDigest().hex(), bundle) == null;
        }

        @Override
        public Optional<ConfigBundle> findByDigest(Digest digest) {
            return Optional.ofNullable(rows.get(digest.hex()));
        }

        @Override
        public Optional<Digest> activeDigest() {
            return Optional.empty();
        }

        @Override
        public Optional<ActivePointer> findActivePointer() {
            return Optional.empty();
        }

        @Override
        public boolean activateQualified(Digest toDigest, long expectedActiveRevision,
                String by, Instant at) {
            return false;
        }
    }

    /** 资格假件：candidate→未撤销 PASS 证明（撤销三件套语义与生产一致） */
    static final class FakeQualifications implements ReleaseQualificationRepository {
        private final Map<String, ReleaseQualification> rows = new LinkedHashMap<>();

        void grant(String digest) {
            rows.put(digest, new ReleaseQualification(UUID.randomUUID(),
                    new Digest(digest), null, Digest.sha256Of("dataset").hex(),
                    "runner-1", "grader-1", ReleaseQualification.VERDICT_PASS,
                    ReleaseQualification.USAGE_MATCHED, "it", "op", NOW,
                    null, null, null));
        }

        @Override
        public boolean insert(ReleaseQualification qualification) {
            return rows.putIfAbsent(qualification.candidateDigest().hex(),
                    qualification) == null;
        }

        @Override
        public Optional<ReleaseQualification> findUnrevokedFor(Digest candidate) {
            return Optional.ofNullable(rows.get(candidate.hex()))
                    .filter(q -> q.revokedAt() == null);
        }

        @Override
        public boolean revoke(UUID id, String by, String reason, Instant at) {
            for (Map.Entry<String, ReleaseQualification> e : rows.entrySet()) {
                if (e.getValue().id().equals(id) && e.getValue().revokedAt() == null) {
                    rows.put(e.getKey(), e.getValue().revoked(by, reason, at));
                    return true;
                }
            }
            return false;
        }

        void revoke(String digest) {
            revoke(rows.get(digest).id(), "op", "紧急撤销", NOW);
        }
    }
}
