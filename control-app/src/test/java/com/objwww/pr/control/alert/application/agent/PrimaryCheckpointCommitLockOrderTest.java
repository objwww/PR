package com.objwww.pr.control.alert.application.agent;

import com.objwww.pr.control.alert.domain.agent.PrimaryCheckpoint;
import com.objwww.pr.control.alert.domain.model.RcaRun;
import com.objwww.pr.control.alert.domain.model.RcaRunState;
import com.objwww.pr.control.alert.domain.model.RcaTask;
import com.objwww.pr.control.alert.domain.model.RcaTaskState;
import com.objwww.pr.control.alert.domain.model.RunTrigger;
import com.objwww.pr.control.alert.domain.repository.RcaRunRepository;
import com.objwww.pr.control.alert.domain.repository.RcaTaskRepository;
import com.objwww.pr.control.alert.domain.repository.RunConfigEpochRepository;
import com.objwww.pr.control.alert.support.AlertInMemoryStores;
import com.objwww.pr.shared.Digest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionOperations;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * WC-1（方案 v2 §4.1）：提交围栏锁获取序 = <b>task → run → checkpoint</b>——与
 * finishTask（LeaseFence task→run）/ expireRun（lockNonTerminalByRunIdForUpdate
 * task→run）全链一致。锁获取序换向不改变校验优先级（run 活跃优先于 task 身份，
 * 拒绝原因语义零漂移）。
 *
 * <p>本类是锁序契约的 UT 面（锁序记录器断言获取次序）；真 PG 行级 AB-BA 竞态由
 * PostgresCheckpointLockOrderIT（WC-T07/T08）承载。
 */
class PrimaryCheckpointCommitLockOrderTest {

    private static final Instant NOW = Instant.parse("2026-09-13T03:00:00Z");

    // --------------------------------------------------------------- 记录器假件

    /** 只实现提交围栏路径所需方法；锁读调用序记录进 order */
    static final class RecordingRuns implements RcaRunRepository {
        RcaRun row;
        final List<String> order;

        RecordingRuns(RcaRun row, List<String> order) {
            this.row = row;
            this.order = order;
        }

        @Override
        public Optional<RcaRun> findByIdForUpdate(UUID id) {
            order.add("RUN");
            return Optional.ofNullable(row).filter(r -> r.id().equals(id));
        }

        @Override
        public void insert(RcaRun run) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Optional<RcaRun> findById(UUID id) {
            return Optional.ofNullable(row).filter(r -> r.id().equals(id));
        }

        @Override
        public boolean update(RcaRun run) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Optional<RcaRun> findActiveByIncidentId(UUID incidentId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<RcaRun> findAll() {
            throw new UnsupportedOperationException();
        }

        @Override
        public Optional<RoutingView> findRoutingById(UUID id) {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean existsNativeRunByIncidentId(UUID incidentId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public OptionalLong currentRevision(UUID id) {
            return OptionalLong.of(0);
        }
    }

    static final class RecordingTasks implements RcaTaskRepository {
        RcaTask row;
        final List<String> order;

        RecordingTasks(RcaTask row, List<String> order) {
            this.row = row;
            this.order = order;
        }

        @Override
        public Optional<RcaTask> findByIdForUpdate(UUID id) {
            order.add("TASK");
            return Optional.ofNullable(row).filter(t -> t.id().equals(id));
        }

        @Override
        public void insert(RcaTask task) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Optional<RcaTask> claimNext(String owner, Instant now, Duration lease) {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean requireCurrentLease(UUID id, String owner, long leaseEpoch) {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean update(RcaTask task) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void heartbeat(UUID id, String owner, long leaseEpoch, Instant now,
                Duration extend) {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<RcaTask> findExpiredLeased(Instant now) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Optional<RcaTask> findById(UUID id) {
            return Optional.ofNullable(row).filter(t -> t.id().equals(id));
        }

        @Override
        public List<RcaTask> findByRunId(UUID runId) {
            return row != null && row.runId().equals(runId) ? List.of(row) : List.of();
        }

        @Override
        public boolean transitionState(UUID id, RcaTaskState from, RcaTaskState to) {
            throw new UnsupportedOperationException();
        }

        @Override
        public int countQueued() {
            return 0;
        }
    }

    /** 提交围栏时钟桩（ponytail：AlertClock 接口按需内联） */
    private static final com.objwww.pr.control.alert.application.AlertClock CLOCK =
            new com.objwww.pr.control.alert.application.AlertClock() {
                @Override
                public Instant now() {
                    return NOW;
                }
            };

    // --------------------------------------------------------------- 用例

    @Test
    @DisplayName("WC-T07(UT面)：checkAndApply 锁获取序 = task → run（与 finishTask/expireRun 同序）")
    void lockAcquisitionOrderIsTaskThenRun() {
        Fixture fx = new Fixture(RcaRunState.RUNNING, RcaTaskState.LEASED);
        PrimaryCheckpointCommitService.CheckpointCommitResult result = fx.service.commit(fx.fence(),
                "primary:" + fx.taskId + ":step:0",
                PrimaryCheckpointCommitService.CommitMutation.STEP_COMPLETED,
                cp -> cp.withStepAdvanced("digest", null, null, null, NOW));
        assertThat(result.status()).isEqualTo(PrimaryCheckpointCommitService.CommitStatus.APPLIED);
        assertThat(fx.order).as("锁获取序：task 先于 run").containsExactly("TASK", "RUN");
    }

    @Test
    @DisplayName("锁序换向不改校验优先级：终态 run + 失效 owner → 仍 RUN_TERMINAL（非 STALE_OWNER）")
    void rejectionPrecedenceUnchangedByLockOrder() {
        Fixture fx = new Fixture(RcaRunState.CANCELLED, RcaTaskState.LEASED);
        // owner 故意错配：run 终态优先命中（次序与旧实现一致）
        assertThatThrownBy(() -> fx.service.commit(fx.fence(),
                "primary:" + fx.taskId + ":step:0",
                PrimaryCheckpointCommitService.CommitMutation.STEP_COMPLETED,
                cp -> cp))
                .isInstanceOf(PrimaryCheckpointCommitService.CommitRejectedException.class)
                .hasMessageContaining("RUN_TERMINAL");
        assertThat(fx.order).containsExactly("TASK", "RUN");
    }

    @Test
    @DisplayName("task 不属于该 run → STALE_OWNER（身份闸保持）")
    void foreignTaskRejected() {
        Fixture fx = new Fixture(RcaRunState.RUNNING, RcaTaskState.LEASED, UUID.randomUUID());
        assertThatThrownBy(() -> fx.service.commit(fx.fence(),
                "primary:" + fx.taskId + ":step:0",
                PrimaryCheckpointCommitService.CommitMutation.STEP_COMPLETED,
                cp -> cp))
                .isInstanceOf(PrimaryCheckpointCommitService.CommitRejectedException.class)
                .hasMessageContaining("STALE_OWNER");
    }

    // --------------------------------------------------------------- 装配

    private static final class Fixture {
        final UUID runId = UUID.randomUUID();
        final UUID taskId = UUID.randomUUID();
        final List<String> order = new ArrayList<>();
        final PrimaryCheckpointCommitService service;

        Fixture(RcaRunState runState, RcaTaskState taskState) {
            this(runState, taskState, null);
        }

        /** taskRunOverride 非空 = task 行挂到外 run（身份闸用例） */
        Fixture(RcaRunState runState, RcaTaskState taskState, UUID taskRunOverride) {
            RcaRun run = new RcaRun(runId, UUID.randomUUID(), 0, RunTrigger.INITIAL,
                    runState, Digest.sha256Of("wc1"), NOW, NOW, NOW, null, null);
            RcaTask task = new RcaTask(taskId, taskRunOverride == null ? runId : taskRunOverride,
                    RcaTask.NATIVE_INVESTIGATE,
                    taskState, 5, NOW, NOW, NOW.plusSeconds(3600), "w1",
                    NOW.plusSeconds(600), 1, 0, 3, NOW, NOW);
            AlertInMemoryStores.Checkpoints checkpoints = new AlertInMemoryStores.Checkpoints();
            checkpoints.upsert(PrimaryCheckpoint.initial(taskId, runId, 0, NOW));
            service = new PrimaryCheckpointCommitService(
                    new RecordingRuns(run, order), new RecordingTasks(task, order),
                    checkpoints, RunConfigEpochRepository.NO_OP, CLOCK,
                    TransactionOperations.withoutTransaction());
        }

        PrimaryCheckpointCommitService.CommitFence fence() {
            return new PrimaryCheckpointCommitService.CommitFence(
                    runId, taskId, "w1", 1, null, 0);
        }
    }
}
