package com.objwww.pr.control.it;

import com.objwww.pr.control.alert.application.AlertClock;
import com.objwww.pr.control.alert.application.agent.PrimaryCheckpointCommitService;
import com.objwww.pr.control.alert.domain.agent.PrimaryCheckpoint;
import com.objwww.pr.control.alert.domain.model.Incident;
import com.objwww.pr.control.alert.domain.model.IncidentStatus;
import com.objwww.pr.control.alert.domain.model.RcaRun;
import com.objwww.pr.control.alert.domain.model.RcaRunState;
import com.objwww.pr.control.alert.domain.model.RcaTask;
import com.objwww.pr.control.alert.domain.model.RcaTaskState;
import com.objwww.pr.control.alert.domain.model.RunTrigger;
import com.objwww.pr.control.alert.domain.repository.PrimaryCheckpointRepository;
import com.objwww.pr.control.alert.domain.repository.RcaRunRepository;
import com.objwww.pr.control.alert.domain.repository.RcaTaskRepository;
import com.objwww.pr.control.infrastructure.persistence.PostgresIncidentRepository;
import com.objwww.pr.control.infrastructure.persistence.PostgresPrimaryCheckpointRepository;
import com.objwww.pr.control.infrastructure.persistence.PostgresRcaRunRepository;
import com.objwww.pr.control.infrastructure.persistence.PostgresRcaTaskRepository;
import com.objwww.pr.control.infrastructure.persistence.PostgresRunConfigEpochRepository;
import com.objwww.pr.shared.Digest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * MC08 扩展（CL-01，真 PG 四写点屏障，告警-Agent闭环修复 v1 §2.5）：提交围栏
 * revision CAS / owner-epoch 栅栏 / 动作身份 REPLAYED / Run 终止与 configEpoch
 * 拒绝 / 真并发恰一胜者 / 不同 task 并发无死锁。
 *
 * <p>全部断言以行锁/条件写影响行数裁决，不以 sleep 推测时序。
 */
class PrimaryCheckpointCommitFenceIT extends PostgresITBase {

    private static final Instant NOW = Instant.parse("2026-09-13T02:00:00Z");

    private RcaTaskRepository tasks;
    private RcaRunRepository runs;
    private PrimaryCheckpointRepository checkpoints;
    private PostgresRunConfigEpochRepository epochs;
    private PrimaryCheckpointCommitService fence;

    @BeforeEach
    void setUp() {
        JdbcClient jdbc = JdbcClient.create(controlDataSource());
        tasks = new PostgresRcaTaskRepository(jdbc);
        runs = new PostgresRcaRunRepository(jdbc);
        checkpoints = new PostgresPrimaryCheckpointRepository(jdbc, objectMapper());
        epochs = new PostgresRunConfigEpochRepository(jdbc);
        fence = new PrimaryCheckpointCommitService(runs, tasks, checkpoints, epochs,
                () -> NOW, controlTx);
    }

    private static com.fasterxml.jackson.databind.ObjectMapper objectMapper() {
        return new com.fasterxml.jackson.databind.ObjectMapper();
    }

    private record Seed(UUID runId, UUID taskId) {}

    /** 活跃 run + 已领取主任务 + revision 0 检查点（leaseUntil 默认远期） */
    private Seed seed(String tag, String owner, long epoch, Instant leaseUntil) {
        UUID incidentId = UUID.randomUUID();
        Instant now = Instant.now();
        PostgresIncidentRepository incidents = new PostgresIncidentRepository(
                JdbcClient.create(controlDataSource()));
        incidents.insert(new Incident(incidentId,
                "alertname=HighErrorRate|service=" + tag, IncidentStatus.FIRING, 0,
                now.minus(Duration.ofMinutes(5)), now.minus(Duration.ofMinutes(5)),
                null, null, null, 0, 0, 0, null,
                now.minus(Duration.ofMinutes(5)), now.minus(Duration.ofMinutes(5)),
                now, now));
        RcaRun run = new RcaRun(UUID.randomUUID(), incidentId, 0, RunTrigger.INITIAL,
                RcaRunState.RUNNING, Digest.sha256Of("run-" + tag), now, now, now,
                null, null);
        runs.insert(run);
        RcaTask task = new RcaTask(UUID.randomUUID(), run.id(),
                RcaTask.PRIMARY_INVESTIGATE, RcaTaskState.RUNNING, 5, now, now,
                now.plusSeconds(3600), owner, leaseUntil, epoch, 1, 3, now, now);
        tasks.insert(task);
        checkpoints.insertIfAbsent(PrimaryCheckpoint.initial(task.id(), run.id(), 0, now));
        return new Seed(run.id(), task.id());
    }

    private PrimaryCheckpointCommitService.CommitFence fenceOf(Seed seed, String owner,
            long epoch, long expectedRevision) {
        return new PrimaryCheckpointCommitService.CommitFence(seed.runId(), seed.taskId(),
                owner, epoch, null, expectedRevision);
    }

    /** 通用 STEP_COMPLETED 推进 */
    private PrimaryCheckpointCommitService.CheckpointCommitResult step(
            PrimaryCheckpointCommitService.CommitFence f, String actionKey) {
        return fence.commit(f, actionKey,
                PrimaryCheckpointCommitService.CommitMutation.STEP_COMPLETED,
                cp -> cp.withStepAdvanced(null, null, null, null, NOW));
    }

    // ------------------------------------------------------------ 四写点 APPLIED

    @Test
    @DisplayName("四类 mutation 各自推进且 revision 单调（step/decision/final/lastError）")
    void fourWritePointsAppliedWithMonotonicRevision() {
        Seed seed = seed("cl01-four", "worker-a", 1, NOW.plusSeconds(600));
        long rev = 0;
        var r1 = step(fenceOf(seed, "worker-a", 1, rev++),
                "primary:" + seed.taskId() + ":step:0");
        assertThat(r1.status()).isEqualTo(PrimaryCheckpointCommitService.CommitStatus.APPLIED);
        assertThat(r1.checkpoint().revision()).isEqualTo(1);
        assertThat(r1.checkpoint().decisionSeq()).isEqualTo(1);
        assertThat(r1.checkpoint().stepsUsed()).isEqualTo(1);

        var r2 = fence.commit(fenceOf(seed, "worker-a", 1, rev++),
                "primary:" + seed.taskId() + ":decision:1",
                PrimaryCheckpointCommitService.CommitMutation.DECISION_ADVANCED,
                cp -> cp.withDecisionAdvanced("REJ", NOW));
        assertThat(r2.status()).isEqualTo(PrimaryCheckpointCommitService.CommitStatus.APPLIED);
        assertThat(r2.checkpoint().decisionSeq()).isEqualTo(2);
        assertThat(r2.checkpoint().lastError()).isEqualTo("REJ");

        var r3 = fence.commit(fenceOf(seed, "worker-a", 1, rev++),
                "primary:" + seed.taskId() + ":final:2",
                PrimaryCheckpointCommitService.CommitMutation.FINAL_PROPOSED,
                cp -> cp.withFinal(List.of(Map.of("claim_key", "c1")), List.of(), NOW));
        assertThat(r3.status()).isEqualTo(PrimaryCheckpointCommitService.CommitStatus.APPLIED);
        assertThat(r3.checkpoint().finalClaims()).hasSize(1);

        var r4 = fence.commit(fenceOf(seed, "worker-a", 1, rev++),
                "primary:" + seed.taskId() + ":err:2:sig",
                PrimaryCheckpointCommitService.CommitMutation.ERROR_RECORDED,
                cp -> cp.withLastError("sig", NOW));
        assertThat(r4.status()).isEqualTo(PrimaryCheckpointCommitService.CommitStatus.APPLIED);
        assertThat(r4.checkpoint().decisionSeq()).as("ERROR_RECORDED 零推进")
                .isEqualTo(2);

        assertThat(checkpoints.findByTask(seed.taskId()).orElseThrow().revision())
                .isEqualTo(4);
    }

    // ------------------------------------------------------------ REPLAYED

    @Test
    @DisplayName("同动作重复提交：相同结果 REPLAYED 且状态不变；不再叠加 revision")
    void sameActionReplayReturnsReplayedUnchanged() {
        Seed seed = seed("cl01-replay", "worker-a", 1, NOW.plusSeconds(600));
        var f = fenceOf(seed, "worker-a", 1, 0);
        String actionKey = "primary:" + seed.taskId() + ":step:0";
        var first = step(f, actionKey);
        assertThat(first.status()).isEqualTo(PrimaryCheckpointCommitService.CommitStatus.APPLIED);

        // 相同动作重复提交（预期相同结果）：REPLAYED，检查点逐列不变
        var replay = fence.commit(fenceOf(seed, "worker-a", 1, 1), actionKey,
                PrimaryCheckpointCommitService.CommitMutation.STEP_COMPLETED,
                cp -> cp.withStepAdvanced(null, null, null, null, NOW));
        assertThat(replay.status()).isEqualTo(PrimaryCheckpointCommitService.CommitStatus.REPLAYED);
        assertThat(replay.checkpoint().revision()).isEqualTo(1);
        assertThat(checkpoints.findByTask(seed.taskId()).orElseThrow().revision())
                .isEqualTo(1);
    }

    // ---------------------------------------------------- A 停滞 B 接管屏障

    @Test
    @DisplayName("A 过准入后停住→B 接管写新 revision→A 返回：A 拒绝 STALE_REVISION，B 状态不变")
    void staleRevisionAfterOwnerTakeover() {
        Seed seed = seed("cl01-takeover", "worker-a", 1, NOW.plusSeconds(600));
        // A 读得 revision=0（过准入）后停住
        long aExpected = checkpoints.findByTask(seed.taskId()).orElseThrow().revision();

        // B 接管：以同身份写新 revision（新协议 writer 推进）
        var b = step(fenceOf(seed, "worker-a", 1, aExpected),
                "primary:" + seed.taskId() + ":step:0");
        assertThat(b.status()).isEqualTo(PrimaryCheckpointCommitService.CommitStatus.APPLIED);

        // A 返回：按旧 revision 提交 → STALE_REVISION（条件写 0 行），B 的状态不变
        assertThatThrownBy(() -> step(fenceOf(seed, "worker-a", 1, aExpected),
                "primary:" + seed.taskId() + ":step:0"))
                .isInstanceOf(PrimaryCheckpointCommitService.CommitRejectedException.class)
                .satisfies(e -> assertThat(
                        ((PrimaryCheckpointCommitService.CommitRejectedException) e).status())
                        .isEqualTo(PrimaryCheckpointCommitService.CommitStatus.STALE_REVISION));
        assertThat(checkpoints.findByTask(seed.taskId()).orElseThrow().decisionSeq())
                .isEqualTo(1);
    }

    @Test
    @DisplayName("租约易主（owner/epoch 换代）后旧 owner 提交 → STALE_OWNER")
    void staleOwnerRejected() {
        Seed seed = seed("cl01-owner", "worker-a", 1, NOW.plusSeconds(600));
        // B 重领：epoch +1 易主（模拟 reclaim/claimNext 后的 task 行）
        tasks.update(new RcaTask(seed.taskId(), seed.runId(), RcaTask.PRIMARY_INVESTIGATE,
                RcaTaskState.RUNNING, 5, NOW, NOW, NOW.plusSeconds(600), "worker-b",
                NOW.plusSeconds(600), 2, 1, 3, NOW, NOW));
        assertThatThrownBy(() -> step(fenceOf(seed, "worker-a", 1, 0), "a-key"))
                .isInstanceOf(PrimaryCheckpointCommitService.CommitRejectedException.class)
                .satisfies(e -> assertThat(
                        ((PrimaryCheckpointCommitService.CommitRejectedException) e).status())
                        .isEqualTo(PrimaryCheckpointCommitService.CommitStatus.STALE_OWNER));
        // 新 owner 凭新 epoch 提交通过
        assertThat(step(fenceOf(seed, "worker-b", 2, 0), "b-key").status())
                .isEqualTo(PrimaryCheckpointCommitService.CommitStatus.APPLIED);
    }

    @Test
    @DisplayName("run 终止后提交 → RUN_TERMINAL；configEpoch 变更 → CONFIG_CHANGED")
    void runTerminalAndConfigChangedRejected() {
        Seed seed = seed("cl01-terminal", "worker-a", 1, NOW.plusSeconds(600));
        runs.update(new RcaRun(seed.runId(), runs.findById(seed.runId()).orElseThrow()
                .incidentId(), 0, RunTrigger.INITIAL, RcaRunState.CANCELLED,
                Digest.sha256Of("h"), NOW, NOW, NOW, NOW, null));
        assertThatThrownBy(() -> step(fenceOf(seed, "worker-a", 1, 0), "a-key"))
                .isInstanceOf(PrimaryCheckpointCommitService.CommitRejectedException.class)
                .satisfies(e -> assertThat(
                        ((PrimaryCheckpointCommitService.CommitRejectedException) e).status())
                        .isEqualTo(PrimaryCheckpointCommitService.CommitStatus.RUN_TERMINAL));

        // configEpoch 栅栏：围栏携带 epoch=1，代际史推进到 2 → CONFIG_CHANGED
        Seed seed2 = seed("cl01-epoch", "worker-a", 1, NOW.plusSeconds(600));
        epochs.append(seed2.runId(), 1, "e".repeat(64), UUID.randomUUID(),
                "it", "init");
        var ok = fence.commit(new PrimaryCheckpointCommitService.CommitFence(seed2.runId(),
                seed2.taskId(), "worker-a", 1, 1L, 0),
                PrimaryCheckpointCommitService.CommitMutation.STEP_COMPLETED.name(),
                PrimaryCheckpointCommitService.CommitMutation.STEP_COMPLETED,
                cp -> cp.withStepAdvanced(null, null, null, null, NOW));
        assertThat(ok.status()).isEqualTo(PrimaryCheckpointCommitService.CommitStatus.APPLIED);
        epochs.append(seed2.runId(), 2, "f".repeat(64), UUID.randomUUID(),
                "it", "hot-switch");
        assertThatThrownBy(() -> fence.commit(new PrimaryCheckpointCommitService.CommitFence(
                        seed2.runId(), seed2.taskId(), "worker-a", 1, 1L, 1),
                PrimaryCheckpointCommitService.CommitMutation.STEP_COMPLETED.name(),
                PrimaryCheckpointCommitService.CommitMutation.STEP_COMPLETED,
                cp -> cp.withStepAdvanced(null, null, null, null, NOW)))
                .isInstanceOf(PrimaryCheckpointCommitService.CommitRejectedException.class)
                .satisfies(e -> assertThat(
                        ((PrimaryCheckpointCommitService.CommitRejectedException) e).status())
                        .isEqualTo(PrimaryCheckpointCommitService.CommitStatus.CONFIG_CHANGED));
    }

    // ------------------------------------------------------------ 真并发屏障

    @Test
    @DisplayName("双 worker 同 revision 并发提交：行锁裁决恰一 APPLIED 一 STALE_REVISION")
    void concurrentCommitSingleWinner() throws Exception {
        Seed seed = seed("cl01-race", "worker-a", 1, NOW.plusSeconds(600));
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CyclicBarrier barrier = new CyclicBarrier(2);
        Callable<PrimaryCheckpointCommitService.CommitStatus> contender = () -> {
            barrier.await(5, TimeUnit.SECONDS);
            try {
                return step(fenceOf(seed, "worker-a", 1, 0),
                        "primary:" + seed.taskId() + ":step:0:"
                                + Thread.currentThread().getName()).status();
            } catch (PrimaryCheckpointCommitService.CommitRejectedException e) {
                return e.status();
            }
        };
        Future<PrimaryCheckpointCommitService.CommitStatus> f1 = pool.submit(contender);
        Future<PrimaryCheckpointCommitService.CommitStatus> f2 = pool.submit(contender);
        AtomicReference<Object> pair = new AtomicReference<>(List.of(f1.get(10, TimeUnit.SECONDS),
                f2.get(10, TimeUnit.SECONDS)));
        pool.shutdownNow();
        List<?> statuses = (List<?>) pair.get();
        long applied = statuses.stream()
                .filter(s -> s == PrimaryCheckpointCommitService.CommitStatus.APPLIED).count();
        long stale = statuses.stream()
                .filter(s -> s == PrimaryCheckpointCommitService.CommitStatus.STALE_REVISION
                        || s == PrimaryCheckpointCommitService.CommitStatus.REPLAYED).count();
        assertThat(applied).as("恰一胜者").isEqualTo(1);
        assertThat(stale).as("恰一败者（REPLAYED 仅当胜者动作身份相同，同为收敛语义）")
                .isEqualTo(1);
        assertThat(checkpoints.findByTask(seed.taskId()).orElseThrow().revision())
                .as("检查点恰推进一次").isEqualTo(1);
    }

    @Test
    @DisplayName("同 run 两个 task 并发提交（统一锁序 run→task→checkpoint）无死锁双胜")
    void concurrentDifferentTasksNoDeadlock() throws Exception {
        Seed a = seed("cl01-ta", "worker-a", 1, NOW.plusSeconds(600));
        Seed b = seed("cl01-tb", "worker-b", 1, NOW.plusSeconds(600));
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CyclicBarrier barrier = new CyclicBarrier(2);
        Callable<Boolean> driveA = () -> {
            barrier.await(5, TimeUnit.SECONDS);
            for (int i = 0; i < 20; i++) {
                step(fenceOf(a, "worker-a", 1, i), "key-a-" + i);
            }
            return true;
        };
        Callable<Boolean> driveB = () -> {
            barrier.await(5, TimeUnit.SECONDS);
            for (int i = 0; i < 20; i++) {
                step(fenceOf(b, "worker-b", 1, i), "key-b-" + i);
            }
            return true;
        };
        Future<Boolean> fa = pool.submit(driveA);
        Future<Boolean> fb = pool.submit(driveB);
        assertThat(fa.get(20, TimeUnit.SECONDS)).isTrue();
        assertThat(fb.get(20, TimeUnit.SECONDS)).isTrue();
        pool.shutdownNow();
        assertThat(checkpoints.findByTask(a.taskId()).orElseThrow().revision()).isEqualTo(20);
        assertThat(checkpoints.findByTask(b.taskId()).orElseThrow().revision()).isEqualTo(20);
    }
}
