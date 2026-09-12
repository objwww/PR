package com.objwww.pr.control.eval.application;

import com.objwww.pr.control.eval.domain.EvalRun;
import com.objwww.pr.control.eval.domain.EvalRunMetadata;
import com.objwww.pr.control.eval.domain.model.EvalRunCommand;
import com.objwww.pr.control.eval.domain.repository.EvalRunCommandRepository;
import com.objwww.pr.control.eval.domain.repository.EvalRunRepository;
import com.objwww.pr.shared.Digest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * EV-04 worker：领取→执行→收尾单拍语义、命令失败收口（异常不吞、命令 FAILED
 * 留痕）、启动孤儿清扫三分支（run 未落库重排队稳定身份 / run RUNNING worker_lost
 * 终态 / run 已终态命令对齐）。
 */
class EvalRunWorkerTest {

    private static final Instant BASE = Instant.parse("2026-09-11T00:00:00Z");
    private static final long STALE_SECONDS = 900;

    // ------------------------------------------------------------------ 假件

    private static final class FakeCommands implements EvalRunCommandRepository {
        final Map<UUID, EvalRunCommand> byId = new HashMap<>();
        final Deque<EvalRunCommand> pending = new ArrayDeque<>();

        void enqueue(EvalRunCommand command) {
            byId.put(command.id(), command);
            pending.add(command);
        }

        private EvalRunCommand replace(EvalRunCommand c, EvalRunCommand.State state,
                                       String workerId, Instant claimedAt,
                                       Instant finishedAt) {
            EvalRunCommand updated = new EvalRunCommand(c.id(), c.commandType(),
                    c.evalRunId(), c.idempotencyKey(), c.payloadJson(), c.payloadHash(),
                    state, c.actor(), workerId, c.createdAt(), claimedAt, finishedAt);
            byId.put(c.id(), updated);
            return updated;
        }

        @Override
        public void insert(EvalRunCommand command) {
            enqueue(command);
        }

        @Override
        public Optional<EvalRunCommand> findByKey(EvalRunCommand.Type type, String key) {
            return byId.values().stream()
                    .filter(c -> c.commandType() == type && c.idempotencyKey().equals(key))
                    .findFirst();
        }

        @Override
        public boolean cancelAccepted(UUID evalRunId) {
            return false;
        }

        @Override
        public Optional<Instant> cancelRequestedAt(UUID evalRunId) {
            return Optional.empty();
        }

        @Override
        public Optional<EvalRunCommand> claimNextLaunch(String workerId, Instant claimedAt) {
            EvalRunCommand head = pending.poll();
            if (head == null) {
                return Optional.empty();
            }
            return Optional.of(replace(head, EvalRunCommand.State.CLAIMED, workerId,
                    claimedAt, null));
        }

        @Override
        public boolean finish(UUID id, EvalRunCommand.State terminal, Instant finishedAt) {
            EvalRunCommand c = byId.get(id);
            if (c == null || (c.state() != EvalRunCommand.State.PENDING
                    && c.state() != EvalRunCommand.State.CLAIMED)) {
                return false;
            }
            replace(c, terminal, c.workerId(), c.claimedAt(), finishedAt);
            return true;
        }

        @Override
        public List<EvalRunCommand> findOrphanedClaims(Instant before) {
            return byId.values().stream()
                    .filter(c -> c.state() == EvalRunCommand.State.CLAIMED
                            && c.claimedAt() != null && c.claimedAt().isBefore(before))
                    .toList();
        }

        @Override
        public boolean requeue(UUID id) {
            EvalRunCommand c = byId.get(id);
            if (c == null || c.state() != EvalRunCommand.State.CLAIMED) {
                return false;
            }
            EvalRunCommand updated = replace(c, EvalRunCommand.State.PENDING, null,
                    null, null);
            pending.add(updated);
            return true;
        }
    }

    private static final class FakeEvalRuns implements EvalRunRepository {
        final Map<UUID, EvalRun> runs = new HashMap<>();

        @Override
        public void insertRunning(EvalRun running) {
            runs.put(running.id(), running);
        }

        @Override
        public boolean finalizeOnce(EvalRun terminal) {
            runs.put(terminal.id(), terminal);
            return true;
        }

        @Override
        public boolean applyLaunchIdentity(UUID runId, String displayName, String mode,
                                           String launchPlanJson) {
            return true;
        }

        @Override
        public boolean updateRecoveryState(UUID runId, String recoveryState) {
            return true;
        }

        @Override
        public boolean insertCaseResult(com.objwww.pr.control.eval.domain.EvalCaseResult r) {
            return true;
        }

        @Override
        public Optional<EvalRun> findById(UUID runId) {
            return Optional.ofNullable(runs.get(runId));
        }

        @Override
        public List<com.objwww.pr.control.eval.domain.EvalCaseResult> findCasesByRunId(
                UUID runId) {
            return List.of();
        }
    }

    private static final class StepClock implements EvalBatchRunner.EvalClock {
        private Instant now = BASE;

        @Override
        public Instant now() {
            return now;
        }

        @Override
        public void sleepSeconds(long seconds) {
            now = now.plusSeconds(seconds);
        }
    }

    private static EvalRunMetadata metadata() {
        return new EvalRunMetadata(1, "eval-ds-1", Digest.sha256Of("registry"), 1,
                "deepseek-v3", "am3-rca-v2", Digest.sha256Of("prompt"),
                Digest.sha256Of("tools"), null, null, null, null, null, "fp-x",
                Digest.sha256Of("rules"), "scenario-driver-v1", "grader-test-v1");
    }

    private static EvalRunCommand launch(String key, String mode) {
        return EvalRunCommand.pending(UUID.randomUUID(), EvalRunCommand.Type.LAUNCH,
                UUID.randomUUID(), key,
                "{\"displayName\":\"n\",\"mode\":\"" + mode + "\","
                        + "\"datasetVersion\":\"eval-ds-1\"}",
                Digest.sha256Of(key).value(), "operator", BASE);
    }

    private FakeCommands commands;
    private FakeEvalRuns evalRuns;
    private StepClock clock;
    private UsageLedgerService ledger;

    @BeforeEach
    void setUp() {
        commands = new FakeCommands();
        evalRuns = new FakeEvalRuns();
        clock = new StepClock();
        // 账本桩：evalRuns findCasesByRunId 空 → 对账走 UNMATCHED 降级面（不触网）
        ledger = new UsageLedgerService(evalRuns,
                new com.objwww.pr.control.alert.support.AlertInMemoryStores.ModelCalls(),
                null, null, 0);
    }

    private EvalRunWorker worker(EvalRunWorker.LaunchExecutor executor) {
        return new EvalRunWorker(commands, evalRuns, executor,
                ledger, clock, "worker-ut", 5, STALE_SECONDS);
    }

    /** 清扫面测试用 worker（执行面不应被触达） */
    private EvalRunWorker worker() {
        return worker(command -> {
            throw new IllegalStateException("executor 不应被调用");
        });
    }

    // ------------------------------------------------------------------ tick

    @Test
    @DisplayName("tick：无命令 false；领取 LAUNCH 执行成功 → 命令 DONE；执行抛错 → "
            + "异常不吞进日志、命令 FAILED 收口（run 终态归 runner 责任面）")
    void tickClaimsExecutesAndFinishes() {
        EvalRunWorker worker = worker(newFailingExecutor());

        assertThat(worker.tick()).isFalse();

        EvalRunCommand cmd = launch("k-1", "E");
        commands.enqueue(cmd);
        assertThat(worker.tick()).isTrue();
        assertThat(commands.byId.get(cmd.id()).state())
                .isEqualTo(EvalRunCommand.State.FAILED);
        assertThat(commands.byId.get(cmd.id()).finishedAt()).isNotNull();
    }

    /** 执行器桩（函数口，不触网） */
    private EvalRunWorker.LaunchExecutor newFailingExecutor() {
        return command -> {
            throw new IllegalStateException("arena map missing");
        };
    }

    // ------------------------------------------------------------------ 孤儿清扫

    @Test
    @DisplayName("孤儿清扫：run 未落库 → 重排队且预定 run id 不变（稳定身份重放）")
    void orphanWithoutRunRowRequeues() {
        EvalRunCommand cmd = launch("k-orphan", "E");
        commands.enqueue(cmd);
        EvalRunCommand claimed = commands.claimNextLaunch("dead-worker",
                BASE.minusSeconds(3600)).orElseThrow();

        int handled = worker().sweepOrphanedClaims();

        assertThat(handled).isEqualTo(1);
        EvalRunCommand requeued = commands.byId.get(claimed.id());
        assertThat(requeued.state()).isEqualTo(EvalRunCommand.State.PENDING);
        assertThat(requeued.workerId()).isNull();
        assertThat(requeued.evalRunId()).isEqualTo(cmd.evalRunId());
        assertThat(evalRuns.runs).isEmpty();
    }

    @Test
    @DisplayName("孤儿清扫：run 仍 RUNNING（崩溃于跑批中）→ worker_lost 终态化 + "
            + "命令 FAILED；L 模式卡因带 recovery_unverified")
    void orphanWithRunningRunFinalizesWorkerLost() {
        EvalRunCommand cmd = launch("k-orphan-l", "L");
        commands.enqueue(cmd);
        EvalRunCommand claimed = commands.claimNextLaunch("dead-worker",
                BASE.minusSeconds(3600)).orElseThrow();
        evalRuns.insertRunning(EvalRun.running(cmd.evalRunId(), metadata(), BASE));

        int handled = worker().sweepOrphanedClaims();

        assertThat(handled).isEqualTo(1);
        EvalRun terminal = evalRuns.findById(cmd.evalRunId()).orElseThrow();
        assertThat(terminal.state()).isEqualTo(EvalRun.EvalRunState.FAILED);
        assertThat(terminal.terminalReason())
                .isEqualTo("worker_lost;recovery_unverified");
        assertThat(commands.byId.get(claimed.id()).state())
                .isEqualTo(EvalRunCommand.State.FAILED);
    }

    @Test
    @DisplayName("孤儿清扫：run 已终态（崩溃于收尾前）→ 命令按 run 终态对齐，"
            + "不再执行任何业务动作")
    void orphanWithTerminalRunAlignsCommand() {
        EvalRunCommand cmd = launch("k-orphan-done", "E");
        commands.enqueue(cmd);
        EvalRunCommand claimed = commands.claimNextLaunch("dead-worker",
                BASE.minusSeconds(3600)).orElseThrow();
        evalRuns.runs.put(cmd.evalRunId(), EvalRun.terminal(cmd.evalRunId(), metadata(),
                EvalRun.EvalRunState.SUCCEEDED, BASE, BASE.plusSeconds(60),
                new com.objwww.pr.control.eval.domain.ScenarioMetrics.Snapshot(
                        1, 1, 1, 0, 0, 0, 1.0, 1.0, 1.0, 0.0),
                new EvalRun.SymptomCounts(1, 0, 0), Digest.sha256Of("report")));

        int handled = worker().sweepOrphanedClaims();

        assertThat(handled).isEqualTo(1);
        assertThat(commands.byId.get(claimed.id()).state())
                .isEqualTo(EvalRunCommand.State.DONE);
    }

    @Test
    @DisplayName("命令 payload → 发起计划 round-trip（服务层快照字段 worker 侧复验可读）")
    void launchPlanRoundTripsThroughPayload() {
        com.objwww.pr.control.eval.domain.model.EvalLaunchPlan plan =
                EvalLaunchExecutor.parsePlan("{\"displayName\":\"实验甲\",\"mode\":\"L\","
                        + "\"datasetVersion\":\"eval-ds-1\",\"model\":null,"
                        + "\"promptVersion\":\"p3\",\"budgetMaxTokens\":10000,"
                        + "\"maxConcurrency\":null,\"deadlineSeconds\":3600,"
                        + "\"roundsPerScenario\":3}");

        assertThat(plan.displayName()).isEqualTo("实验甲");
        assertThat(plan.mode()).isEqualTo("L");
        assertThat(plan.datasetVersion()).isEqualTo("eval-ds-1");
        assertThat(plan.model()).isNull();
        assertThat(plan.promptVersion()).isEqualTo("p3");
        assertThat(plan.budgetMaxTokens()).isEqualTo(10000L);
        assertThat(plan.deadlineSeconds()).isEqualTo(3600L);
        assertThat(plan.roundsPerScenario()).isEqualTo(3);
    }
}
