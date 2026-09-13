package com.objwww.pr.control.it;

import com.objwww.pr.control.alert.application.CommandService;
import com.objwww.pr.control.alert.domain.event.RcaEventAppender;
import com.objwww.pr.control.alert.domain.model.Incident;
import com.objwww.pr.control.alert.domain.model.IncidentStatus;
import com.objwww.pr.control.alert.domain.model.OperatorCommand;
import com.objwww.pr.control.alert.domain.model.RcaRun;
import com.objwww.pr.control.alert.domain.model.RcaRunState;
import com.objwww.pr.control.alert.domain.model.RunTrigger;
import com.objwww.pr.control.alert.domain.repository.IncidentRepository;
import com.objwww.pr.control.alert.domain.repository.RcaRunRepository;
import com.objwww.pr.control.infrastructure.persistence.PostgresIncidentRepository;
import com.objwww.pr.control.infrastructure.persistence.PostgresOperatorCommandRepository;
import com.objwww.pr.control.infrastructure.persistence.PostgresRcaEventAppender;
import com.objwww.pr.control.infrastructure.persistence.PostgresRcaRunRepository;
import com.objwww.pr.shared.Digest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * WC-T09/T10/T11（方案 v2 §8，真 PG）：CANCEL 应用事务原子性——
 * <ul>
 *   <li>T09：apply 中途异常（CAS 之后事件之前）→ 整事务回滚（run 仍 RUNNING、
 *       命令仍 PERSISTED、零事件）；同键重放续走成功，事件恰一条；</li>
 *   <li>T10：同键重放与新键旧 revision 并发 → 原命令结果稳定、事件恰一条、
 *       新键合法冲突；</li>
 *   <li>T11：遗留半应用行——唯一 CANCEL 候选 = 身份可证明补标；多候选不冒认。</li>
 * </ul>
 */
class PostgresCommandAtomicityIT extends PostgresITBase {

    private JdbcClient jdbc;
    private RcaRunRepository runs;
    private IncidentRepository incidents;
    private PostgresOperatorCommandRepository commands;
    private RcaEventAppender events;

    @BeforeEach
    void setUp() {
        jdbc = JdbcClient.create(controlDataSource());
        runs = new PostgresRcaRunRepository(jdbc);
        incidents = new PostgresIncidentRepository(jdbc);
        commands = new PostgresOperatorCommandRepository(jdbc);
        events = new PostgresRcaEventAppender(jdbc, controlTx, requiresNewTx());
    }

    private CommandService service() {
        return new CommandService(commands, runs, events, controlTx, Instant::now);
    }

    // ------------------------------------------------ T09：中途崩溃整体回滚

    @Test
    @DisplayName("WC-T09 apply 中途异常：CAS 已跑但整体回滚（run 活跃/命令 PERSISTED/零事件），重放续走恰一事件")
    void midApplyCrashRollsBackWholeTransaction() {
        UUID runId = seedRunningRun("wc-t09");
        // CAS 之后、事件处引爆：joinTx append 抛异常 → 事务整体回滚
        RcaEventAppender exploding = new RcaEventAppender() {
            @Override
            public long append(UUID runIdArg, EventDraft draft) {
                throw new IllegalStateException("WC-T09 注入：apply 中途崩溃");
            }

            @Override
            public long appendIndependent(UUID runIdArg, EventDraft draft) {
                throw new IllegalStateException("WC-T09 注入：独立事件面同样引爆");
            }
        };
        CommandService crashing = new CommandService(commands, runs, exploding,
                controlTx, Instant::now);
        try {
            crashing.submit(runId, OperatorCommand.Type.CANCEL, "op-1", 0, Map.of(), "operator");
            throw new AssertionError("注入的崩溃未上抛");
        } catch (IllegalStateException expected) {
            assertThat(expected.getMessage()).contains("WC-T09");
        }
        // 整体回滚：run 仍活跃（CAS 被回滚）、命令仍 PERSISTED、零事件
        assertThat(runs.findById(runId).orElseThrow().state()).isEqualTo(RcaRunState.RUNNING);
        assertThat(commands.find(runId, OperatorCommand.Type.CANCEL, "op-1").orElseThrow()
                .state()).isEqualTo(OperatorCommand.State.PERSISTED);
        assertThat(count("rca_event")).isZero();

        // 同键重放（正常装配）续走 apply：APPLIED + 恰一事件 + run CANCELLED
        CommandService.Result replay = service().submit(runId,
                OperatorCommand.Type.CANCEL, "op-1", 0, Map.of(), "operator");
        assertThat(replay.state()).isEqualTo(OperatorCommand.State.APPLIED);
        assertThat(replay.replayed()).isTrue();
        assertThat(runs.findById(runId).orElseThrow().state()).isEqualTo(RcaRunState.CANCELLED);
        assertThat(count("rca_event")).isEqualTo(1);
    }

    // ------------------------------------------------ T10：并发同键重放 + 新键旧 revision

    @Test
    @DisplayName("WC-T10 栅栏并发：同键续走与新键旧 revision → 原命令稳定 APPLIED、事件恰一、新键 REJECTED_STALE")
    void concurrentReplayAndNewKeySettleConsistently() throws Exception {
        UUID runId = seedRunningRun("wc-t10");
        // 先落一张 PERSISTED 行（模拟 phase1 完成后线程死亡），两个并发线程同拍：
        // A 同键续走（settle→apply），B 新键旧 revision 直提交
        UUID persistedId = UUID.randomUUID();
        commands.insert(new OperatorCommand(persistedId, runId, OperatorCommand.Type.CANCEL,
                "op-1", 0, Map.of(), OperatorCommand.State.PERSISTED, "operator",
                Instant.now(), null));

        CyclicBarrier barrier = new CyclicBarrier(2);
        AtomicReference<Object> replayOutcome = new AtomicReference<>();
        AtomicReference<Throwable> replayFailure = new AtomicReference<>();
        AtomicReference<Object> freshOutcome = new AtomicReference<>();
        AtomicReference<Throwable> freshFailure = new AtomicReference<>();
        CommandService svc = service();
        Thread replier = Thread.ofPlatform().name("wc-replay").unstarted(() -> {
            try {
                barrier.await(10, TimeUnit.SECONDS);
                replayOutcome.set(svc.submit(runId, OperatorCommand.Type.CANCEL,
                        "op-1", 0, Map.of(), "operator"));
            } catch (Throwable e) {
                replayFailure.set(e);
            }
        });
        Thread fresher = Thread.ofPlatform().name("wc-fresh").unstarted(() -> {
            try {
                barrier.await(10, TimeUnit.SECONDS);
                freshOutcome.set(svc.submit(runId, OperatorCommand.Type.CANCEL,
                        "op-2", 0, Map.of(), "operator-2"));
            } catch (Throwable e) {
                freshFailure.set(e);
            }
        });
        replier.start();
        fresher.start();
        replier.join(30_000);
        fresher.join(30_000);
        assertThat(replayFailure.get()).as("同键线程零异常").isNull();
        assertThat(freshFailure.get()).as("新键线程零异常").isNull();

        assertThat(runs.findById(runId).orElseThrow().state()).isEqualTo(RcaRunState.CANCELLED);
        assertThat(count("rca_event")).as("RUN_CANCELLED 恰一事件").isEqualTo(1);
        CommandService.Result replay = (CommandService.Result) replayOutcome.get();
        CommandService.Result fresh = (CommandService.Result) freshOutcome.get();
        // 恰一胜者：CAS 先后决定（replay 续走或 fresh 新键任一先到先得，两交错都合法）
        long appliedCount = java.util.List.of(replay.state(), fresh.state()).stream()
                .filter(s -> s == OperatorCommand.State.APPLIED).count();
        assertThat(appliedCount).as("恰一 APPLIED").isEqualTo(1);
        if (replay.state() == OperatorCommand.State.APPLIED) {
            assertThat(replay.commandId()).isEqualTo(persistedId);
            assertThat(commands.find(runId, OperatorCommand.Type.CANCEL, "op-2").orElseThrow()
                    .state()).isEqualTo(OperatorCommand.State.REJECTED_STALE);
        } else {
            // fresh 先胜 CAS 的交错：PERSISTED 行未被 replay 领走，replay 败于修订漂移
            assertThat(fresh.state()).isEqualTo(OperatorCommand.State.APPLIED);
            assertThat(replay.state()).isEqualTo(OperatorCommand.State.REJECTED_STALE);
            assertThat(commands.find(runId, OperatorCommand.Type.CANCEL, "op-1").orElseThrow()
                    .state()).isEqualTo(OperatorCommand.State.REJECTED_STALE);
        }
        assertThat(count("rca_event")).isEqualTo(1);
    }

    // ------------------------------------------------ T11：遗留半应用行恢复面

    @Test
    @DisplayName("WC-T11 唯一候选=身份可证明补标 APPLIED；多候选不冒认（STALE 落账交对账）")
    void legacyHalfAppliedRecoveryByIdentity() {
        // (a) 唯一候选：run 已 CANCELLED（旧版崩溃窗），行 PERSISTED → 补标，零新事件
        UUID soleRun = seedRunningRun("wc-t11-sole");
        controlTx.executeWithoutResult(status -> {
            RcaRun run = runs.findById(soleRun).orElseThrow();
            runs.update(new RcaRun(run.id(), run.incidentId(), run.generation(), run.trigger(),
                    RcaRunState.CANCELLED, run.investigationHash(), run.createdAt(),
                    Instant.now(), run.startedAt(), Instant.now(), "legacy-crash",
                    run.purpose(), run.purposeSource(), run.completionKind()));
        });
        UUID soleId = UUID.randomUUID();
        commands.insert(new OperatorCommand(soleId, soleRun, OperatorCommand.Type.CANCEL,
                "op-1", 0, Map.of(), OperatorCommand.State.PERSISTED, "operator",
                Instant.now(), null));

        CommandService.Result recovered = service().submit(soleRun,
                OperatorCommand.Type.CANCEL, "op-1", 0, Map.of(), "operator");

        assertThat(recovered.state()).isEqualTo(OperatorCommand.State.APPLIED);
        assertThat(recovered.commandId()).isEqualTo(soleId);
        assertThat(count("rca_event")).as("恢复面零新事件").isZero();

        // (b) 多候选：同 run 再挂一张历史 CANCEL 行 → 身份不可证明 → 不冒认
        UUID multiRun = seedRunningRun("wc-t11-multi");
        controlTx.executeWithoutResult(status -> {
            RcaRun run = runs.findById(multiRun).orElseThrow();
            runs.update(new RcaRun(run.id(), run.incidentId(), run.generation(), run.trigger(),
                    RcaRunState.CANCELLED, run.investigationHash(), run.createdAt(),
                    Instant.now(), run.startedAt(), Instant.now(), "legacy-crash",
                    run.purpose(), run.purposeSource(), run.completionKind()));
        });
        commands.insert(new OperatorCommand(UUID.randomUUID(), multiRun,
                OperatorCommand.Type.CANCEL, "op-0", 0, Map.of(),
                OperatorCommand.State.REJECTED_STALE, "operator-2",
                Instant.now().minusSeconds(60), Instant.now().minusSeconds(59)));
        UUID multiId = UUID.randomUUID();
        commands.insert(new OperatorCommand(multiId, multiRun, OperatorCommand.Type.CANCEL,
                "op-1", 0, Map.of(), OperatorCommand.State.PERSISTED, "operator",
                Instant.now(), null));

        CommandService.Result ambiguous = service().submit(multiRun,
                OperatorCommand.Type.CANCEL, "op-1", 0, Map.of(), "operator");

        assertThat(ambiguous.state()).isNotEqualTo(OperatorCommand.State.APPLIED);
        assertThat(commands.find(multiRun, OperatorCommand.Type.CANCEL, "op-1").orElseThrow()
                .state()).isNotEqualTo(OperatorCommand.State.APPLIED);
    }

    // ------------------------------------------------ 种子

    /** RUNNING run（修订 0），无任务——命令面测试只关心 run 行 */
    private UUID seedRunningRun(String tag) {
        Instant now = Instant.now();
        UUID incidentId = UUID.randomUUID();
        incidents.insert(new Incident(incidentId, "alertname=HighErrorRate|service=" + tag,
                IncidentStatus.FIRING, 0, now.minus(Duration.ofMinutes(5)),
                now.minus(Duration.ofMinutes(5)), null, null, null, 0, 0, 0, null,
                now.minus(Duration.ofMinutes(5)), now.minus(Duration.ofMinutes(5)), now, now));
        UUID runId = UUID.randomUUID();
        runs.insert(new RcaRun(runId, incidentId, 0, RunTrigger.INITIAL, RcaRunState.RUNNING,
                Digest.sha256Of("wc-" + tag), now.minus(Duration.ofMinutes(4)), now, now,
                null, null));
        return runId;
    }

    private org.springframework.transaction.support.TransactionOperations requiresNewTx() {
        org.springframework.transaction.support.TransactionTemplate template =
                new org.springframework.transaction.support.TransactionTemplate(
                        new org.springframework.jdbc.datasource.DataSourceTransactionManager(
                                controlDataSource()));
        template.setPropagationBehavior(
                org.springframework.transaction.TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        return template;
    }
}
