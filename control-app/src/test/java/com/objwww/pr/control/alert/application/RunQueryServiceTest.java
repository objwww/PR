package com.objwww.pr.control.alert.application;

import com.objwww.pr.control.alert.domain.dag.DependencyType;
import com.objwww.pr.control.alert.domain.dag.TaskEdge;
import com.objwww.pr.control.alert.domain.model.RcaRun;
import com.objwww.pr.control.alert.domain.model.RcaRunState;
import com.objwww.pr.control.alert.domain.model.RcaTask;
import com.objwww.pr.control.alert.domain.model.RcaTaskState;
import com.objwww.pr.control.alert.domain.model.RunTrigger;
import com.objwww.pr.control.alert.domain.model.TaskExecutionBinding;
import com.objwww.pr.control.alert.domain.repository.RcaModelCallUsageReader;
import com.objwww.pr.control.alert.domain.repository.RcaRunRepository;
import com.objwww.pr.control.alert.domain.repository.RcaTaskRepository;
import com.objwww.pr.control.alert.domain.repository.TaskEdgeRepository;
import com.objwww.pr.control.alert.domain.repository.TaskExecutionBindingRepository;
import com.objwww.pr.shared.Digest;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * RunQueryService 单测（M5-13）：bucket 由 run+task 状态推导（C-18④）、SLA 面、
 * detail 的路由/进度/DAG 投影（C-18②③：engine/config 读 V25 列，budget 如实 null）、
 * §三.5 多 Agent 透出（role/round 读 V46 绑定、usage 读 V48 账本；旧 run 降级 null）。
 */
class RunQueryServiceTest {

    private static final Instant NOW = Instant.parse("2026-09-08T10:00:00Z");
    private static final Supplier<Instant> CLOCK = () -> NOW;

    private final FakeRuns runs = new FakeRuns();
    private final FakeTasks tasks = new FakeTasks();
    private final FakeEdges edges = new FakeEdges();
    private final FakeBindings bindings = new FakeBindings();
    private final FakeUsage usage = new FakeUsage();
    private final RunQueryService service =
            new RunQueryService(runs, tasks, edges, bindings, usage, CLOCK);

    @Test
    void bucketsDeriveFromRunAndTaskStates() {
        UUID stuckRun = run(RcaRunState.RUNNING);
        task(stuckRun, "METRICS", RcaTaskState.DONE);
        task(stuckRun, "ROOT_CAUSE", RcaTaskState.BLOCKED);
        UUID runningRun = run(RcaRunState.RUNNING);
        task(runningRun, "METRICS", RcaTaskState.RUNNING);
        UUID failedRun = run(RcaRunState.FAILED);
        UUID reviewRun = run(RcaRunState.SUCCEEDED);

        Map<String, Object> out = service.list();

        @SuppressWarnings("unchecked")
        Map<String, Object> buckets =
                (Map<String, Object>) ((Map<String, Object>) out.get("summary")).get("buckets");
        assertThat(buckets).containsEntry("mine", 0)
                .containsEntry("running", 1).containsEntry("stuck", 1)
                .containsEntry("failed", 1).containsEntry("review", 1);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> rows = (List<Map<String, Object>>) out.get("rows");
        assertThat(rows).hasSize(4);
        Map<String, Object> stuck = byBucket(rows, "stuck");
        assertThat(stuck.get("stage")).isEqualTo("BLOCKED");
        assertThat(stuck.get("stageZh")).isEqualTo("卡住");
        assertThat(stuck.get("progress")).isEqualTo("1/2");
        assertThat(stuck.get("blocker")).isEqualTo("ROOT_CAUSE:BLOCKED");
        assertThat(stuck.get("severity")).isNull();
        assertThat(stuck.get("owner")).isNull();
        assertThat(stuck.get("action")).isEqualTo("view");
        assertThat(byBucket(rows, "running").get("stage")).isEqualTo("RUNNING");
        assertThat(out.get("nextCursor")).isNull();
    }

    @Test
    void slaFacesCountOverDeadlineAndOldestReadyWait() {
        UUID overRun = run(RcaRunState.RUNNING);
        task(overRun, "ROOT_CAUSE", RcaTaskState.LEASED, NOW.minus(Duration.ofMinutes(6)));
        UUID readyRun = run(RcaRunState.QUEUED);
        task(readyRun, "METRICS", RcaTaskState.READY, null)
                .readySince(NOW.minus(Duration.ofMinutes(18)));

        Map<String, Object> out = service.list();

        @SuppressWarnings("unchecked")
        Map<String, Object> sla =
                (Map<String, Object>) ((Map<String, Object>) out.get("summary")).get("sla");
        assertThat(sla.get("overSla")).isEqualTo(1);
        assertThat(sla.get("oldestReadyWait")).isEqualTo("18m");
        assertThat(sla.get("projectionLag")).as("无观测面 → 如实 null（C-18③）").isNull();
    }

    @Test
    void detailProjectsRoutingProgressTasksAndEdges() {
        UUID runId = run(RcaRunState.RUNNING);
        UUID metricsId = task(runId, "METRICS", RcaTaskState.DONE).taskId();
        task(runId, "ROOT_CAUSE", RcaTaskState.RUNNING, null)
                .lease("worker-03", 7).attempts(2);
        edges.insert(runId, metricsId, UUID.randomUUID(), DependencyType.REQUIRED);
        UUID edgeTo = UUID.randomUUID();
        edges.insert(runId, edgeTo, UUID.randomUUID(), DependencyType.OPTIONAL);
        runs.routing.put(runId, new RcaRunRepository.RoutingView(
                com.objwww.pr.control.alert.domain.model.RcaEngine.NATIVE,
                "9c1e".repeat(16), "grp:1", 42, null, null, null));

        Map<String, Object> detail = service.detail(runId).orElseThrow();

        @SuppressWarnings("unchecked")
        Map<String, Object> head = (Map<String, Object>) detail.get("run");
        assertThat(head.get("status")).isEqualTo("RUNNING");
        assertThat(head.get("severity")).as("无列 → 如实 null（C-18①）").isNull();
        assertThat(head.get("engine")).isEqualTo("NATIVE");
        assertThat(head.get("config")).isEqualTo("9c1e".repeat(16));
        assertThat(head.get("budget")).as("无读面 → 如实 null（C-18③）").isNull();
        assertThat(head.get("progress")).isEqualTo(Map.of("done", 1, "running", 1, "blocked", 0, "total", 2));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> taskRows = (List<Map<String, Object>>) detail.get("tasks");
        Map<String, Object> rootCause = taskRows.stream()
                .filter(t -> "ROOT_CAUSE".equals(t.get("id"))).findFirst().orElseThrow();
        assertThat(rootCause.get("lease")).isEqualTo(Map.of("worker", "worker-03", "epoch", 7L));
        assertThat(rootCause.get("attempts")).isEqualTo(2);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> edgeRows = (List<Map<String, Object>>) detail.get("edges");
        assertThat(edgeRows).hasSize(2);
        assertThat(edgeRows.get(0).get("source")).isEqualTo("METRICS");
        assertThat(edgeRows.get(1).get("source")).isEqualTo(edgeTo.toString());
    }

    @Test
    void detailIsEmptyForUnknownRun() {
        assertThat(service.detail(UUID.randomUUID())).isEmpty();
    }

    // ------------------------------------------------ §三.5 多 Agent 透出（R7-X1/V46 + R7a-1/V48）

    @Test
    void detailProjectsRoleRoundAndUsageFromBindingAndLedger() {
        UUID runId = run(RcaRunState.RUNNING);
        UUID primaryId = task(runId, "PRIMARY_INVESTIGATE", RcaTaskState.RUNNING).taskId();
        UUID delegateId = UUID.randomUUID();
        tasks.rows.put(delegateId, new RcaTask(delegateId, runId, "LOG_ANALYZE",
                RcaTaskState.READY, 10, NOW.minus(Duration.ofMinutes(5)),
                NOW.minus(Duration.ofMinutes(4)), Instant.MAX, null, null, 0, 0, 3,
                NOW.minus(Duration.ofMinutes(5)), NOW, 1));
        UUID parentRequest = UUID.randomUUID();
        bindings.insert(binding(primaryId, runId, 0, "PRIMARY_INVESTIGATE",
                "primary-investigator", "1.2.0", null));
        bindings.insert(binding(delegateId, runId, 1, "LOG_ANALYZE",
                "log-analyst", "0.9.3", parentRequest));
        usage.byRun.put(runId, new RcaModelCallUsageReader.RunUsage(
                3, 1200, 340, 1523000L, 1, "CNY", "pv-2026-09"));

        Map<String, Object> detail = service.detail(runId).orElseThrow();

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> taskRows = (List<Map<String, Object>>) detail.get("tasks");
        Map<String, Object> primary = taskRows.stream()
                .filter(t -> "PRIMARY_INVESTIGATE".equals(t.get("id"))).findFirst().orElseThrow();
        assertThat(primary.get("roundId")).isEqualTo(0);
        assertThat(primary.get("roleId")).isEqualTo("primary-investigator");
        assertThat(primary.get("roleVersion")).isEqualTo("1.2.0");
        assertThat(primary.get("parentRequestId")).as("round0 主任务恒 null（V46）").isNull();
        Map<String, Object> delegate = taskRows.stream()
                .filter(t -> "LOG_ANALYZE".equals(t.get("id"))).findFirst().orElseThrow();
        assertThat(delegate.get("roundId")).isEqualTo(1);
        assertThat(delegate.get("roleId")).isEqualTo("log-analyst");
        assertThat(delegate.get("parentRequestId")).isEqualTo(parentRequest.toString());

        @SuppressWarnings("unchecked")
        Map<String, Object> usageBlock = (Map<String, Object>) detail.get("usage");
        assertThat(usageBlock)
                .containsEntry("callCount", 3L).containsEntry("tokensIn", 1200L)
                .containsEntry("tokensOut", 340L).containsEntry("costMicros", 1523000L)
                .containsEntry("usageMissing", 1L).containsEntry("currency", "CNY")
                .containsEntry("pricingVersion", "pv-2026-09");
    }

    @Test
    void detailWithoutBindingsOrModelCallsDegradesToNulls() {
        // 旧版单角色 run（R7 前铸造）：无绑定行、无模型调用账本 → 如实 null，不伪造
        UUID runId = run(RcaRunState.SUCCEEDED);
        task(runId, "HOLMES_INVESTIGATE", RcaTaskState.DONE);

        Map<String, Object> detail = service.detail(runId).orElseThrow();

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> taskRows = (List<Map<String, Object>>) detail.get("tasks");
        Map<String, Object> row = taskRows.get(0);
        assertThat(row.get("roleId")).isNull();
        assertThat(row.get("roleVersion")).isNull();
        assertThat(row.get("parentRequestId")).isNull();
        assertThat(row.get("roundId")).as("存量行 default 0 = round 0（V46）").isEqualTo(0);
        assertThat(detail.get("usage")).as("无行 → null，前端显「无模型调用」而非全 0").isNull();
    }

    private static TaskExecutionBinding binding(UUID taskId, UUID runId, int roundId,
            String taskKey, String roleId, String roleVersion, UUID parentRequestId) {
        return new TaskExecutionBinding(taskId, runId, roundId, taskKey, roleId, roleVersion,
                "a".repeat(64), null, null, List.of(), Map.of(), parentRequestId, true,
                TaskExecutionBinding.FailurePolicy.DEAD_ON_FAILURE, NOW);
    }

    // ------------------------------------------------------------------ fixtures

    private UUID run(RcaRunState state) {
        UUID id = UUID.randomUUID();
        boolean terminal = !state.isActive();
        runs.rows.put(id, new RcaRun(id, UUID.randomUUID(), 0, RunTrigger.INITIAL, state,
                Digest.sha256Of("inv-" + id),
                NOW.minus(Duration.ofMinutes(30)), NOW,
                NOW.minus(Duration.ofMinutes(24)),
                terminal ? NOW.minus(Duration.ofMinutes(5)) : null,
                null));
        return id;
    }

    private TaskBuilder task(UUID runId, String key, RcaTaskState state) {
        return task(runId, key, state, null);
    }

    /** deadlineAt 为 Instant.MAX（永不到期，与真实铸造一致）除非显式给 overDeadline */
    private TaskBuilder task(UUID runId, String key, RcaTaskState state, Instant overDeadline) {
        UUID id = UUID.randomUUID();
        RcaTask t = new RcaTask(id, runId, key, state, 10,
                NOW.minus(Duration.ofMinutes(20)), NOW.minus(Duration.ofMinutes(18)),
                overDeadline != null ? overDeadline : Instant.MAX,
                null, null, 0, 1, 3,
                NOW.minus(Duration.ofMinutes(20)), NOW);
        tasks.rows.put(id, t);
        return new TaskBuilder(id);
    }

    /** 少量字段的二级定制（lease/attempts/readySince），避免主构造重载爆炸 */
    private final class TaskBuilder {
        private final UUID id;

        TaskBuilder(UUID id) {
            this.id = id;
        }

        UUID taskId() {
            return id;
        }

        TaskBuilder lease(String worker, long epoch) {
            RcaTask t = tasks.rows.get(id);
            tasks.rows.put(id, new RcaTask(t.id(), t.runId(), t.taskKey(), t.state(),
                    t.priority(), t.availableAt(), t.readySince(), t.deadlineAt(),
                    worker, NOW.plus(Duration.ofSeconds(30)), epoch,
                    t.attemptCount(), t.maxAttempts(), t.createdAt(), t.updatedAt()));
            return this;
        }

        TaskBuilder attempts(int n) {
            RcaTask t = tasks.rows.get(id);
            tasks.rows.put(id, new RcaTask(t.id(), t.runId(), t.taskKey(), t.state(),
                    t.priority(), t.availableAt(), t.readySince(), t.deadlineAt(),
                    t.leaseOwner(), t.leaseUntil(), t.leaseEpoch(),
                    n, t.maxAttempts(), t.createdAt(), t.updatedAt()));
            return this;
        }

        TaskBuilder readySince(Instant readySince) {
            RcaTask t = tasks.rows.get(id);
            tasks.rows.put(id, new RcaTask(t.id(), t.runId(), t.taskKey(), t.state(),
                    t.priority(), t.availableAt(), readySince, t.deadlineAt(),
                    t.leaseOwner(), t.leaseUntil(), t.leaseEpoch(),
                    t.attemptCount(), t.maxAttempts(), t.createdAt(), t.updatedAt()));
            return this;
        }
    }

    private static Map<String, Object> byBucket(List<Map<String, Object>> rows, String bucket) {
        return rows.stream().filter(r -> bucket.equals(r.get("bucket"))).findFirst().orElseThrow();
    }

    // ------------------------------------------------------------------ 内存假

    static final class FakeRuns implements RcaRunRepository {
        final Map<UUID, RcaRun> rows = new LinkedHashMap<>();
        final Map<UUID, RoutingView> routing = new LinkedHashMap<>();

        @Override
        public void insert(RcaRun run) {
            rows.put(run.id(), run);
        }

        // C-61 fake 镜像：无路由记录 = 普通 insert 存量行 = 默认 HOLMES
        @Override
        public boolean existsNativeRunByIncidentId(UUID incidentId) {
            return routing.values().stream().anyMatch(v -> v.engine()
                    == com.objwww.pr.control.alert.domain.model.RcaEngine.NATIVE);
        }

        @Override
        public Optional<RcaRun> findByIdForUpdate(UUID id) {
            return Optional.ofNullable(rows.get(id));
        }

        @Override
        public Optional<RcaRun> findById(UUID id) {
            return Optional.ofNullable(rows.get(id));
        }

        @Override
        public boolean update(RcaRun run) {
            return rows.containsKey(run.id());
        }

        @Override
        public Optional<RcaRun> findActiveByIncidentId(UUID incidentId) {
            return Optional.empty();
        }

        @Override
        public List<RcaRun> findAll() {
            return rows.values().stream()
                    .sorted(Comparator.comparing(RcaRun::createdAt).reversed()
                            .thenComparing(RcaRun::id))
                    .toList();
        }

        @Override
        public Optional<RoutingView> findRoutingById(UUID id) {
            return Optional.ofNullable(routing.get(id));
        }

        @Override
        public java.util.OptionalLong currentRevision(UUID id) {
            return rows.containsKey(id) ? java.util.OptionalLong.of(0) : java.util.OptionalLong.empty();
        }
    }

    static final class FakeTasks implements RcaTaskRepository {
        final Map<UUID, RcaTask> rows = new LinkedHashMap<>();

        @Override
        public void insert(RcaTask task) {
            rows.put(task.id(), task);
        }

        @Override
        public Optional<RcaTask> claimNext(String owner, Instant now, Duration lease) {
            return Optional.empty();
        }

        @Override
        public boolean requireCurrentLease(UUID id, String owner, long leaseEpoch) {
            return false;
        }

        @Override
        public boolean update(RcaTask task) {
            return rows.containsKey(task.id());
        }

        @Override
        public void heartbeat(UUID id, String owner, long leaseEpoch, Instant now, Duration extend) {
        }

        @Override
        public List<RcaTask> findExpiredLeased(Instant now) {
            return List.of();
        }

        @Override
        public Optional<RcaTask> findById(UUID id) {
            return Optional.ofNullable(rows.get(id));
        }

        @Override
        public List<RcaTask> findByRunId(UUID runId) {
            return rows.values().stream()
                    .filter(t -> t.runId().equals(runId))
                    .sorted(Comparator.comparing(RcaTask::id))
                    .toList();
        }

        @Override
        public boolean transitionState(UUID id, RcaTaskState from, RcaTaskState to) {
            return false;
        }

        @Override
        public int countQueued() {
            return 0;
        }
    }

    static final class FakeEdges implements TaskEdgeRepository {
        private final Map<UUID, List<TaskEdge>> byRun = new LinkedHashMap<>();

        @Override
        public void insert(UUID runId, UUID fromTaskId, UUID toTaskId, DependencyType dependencyType) {
            byRun.computeIfAbsent(runId, k -> new ArrayList<>())
                    .add(new TaskEdge(fromTaskId.toString(), toTaskId.toString(), dependencyType));
        }

        @Override
        public List<TaskEdge> findByRunId(UUID runId) {
            return byRun.getOrDefault(runId, List.of());
        }
    }

    static final class FakeBindings implements TaskExecutionBindingRepository {
        final Map<UUID, TaskExecutionBinding> byTask = new LinkedHashMap<>();

        @Override
        public void insert(TaskExecutionBinding binding) {
            byTask.put(binding.taskId(), binding);
        }

        @Override
        public Optional<TaskExecutionBinding> findByTask(UUID taskId) {
            return Optional.ofNullable(byTask.get(taskId));
        }

        @Override
        public List<TaskExecutionBinding> findByRun(UUID runId) {
            return byTask.values().stream()
                    .filter(b -> b.runId().equals(runId))
                    .sorted(Comparator.comparingInt(TaskExecutionBinding::roundId)
                            .thenComparing(TaskExecutionBinding::taskKey))
                    .toList();
        }
    }

    static final class FakeUsage implements RcaModelCallUsageReader {
        final Map<UUID, RunUsage> byRun = new LinkedHashMap<>();

        @Override
        public Optional<RunUsage> summarizeByRun(UUID runId) {
            return Optional.ofNullable(byRun.get(runId));
        }
    }
}
