package com.objwww.pr.control.alert.application;

import com.objwww.pr.control.alert.application.agent.AgentRegistry;
import com.objwww.pr.control.alert.domain.agent.AgentProfile;
import com.objwww.pr.control.alert.domain.budget.BudgetKind;
import com.objwww.pr.control.alert.domain.dag.DependencyType;
import com.objwww.pr.control.alert.domain.dag.TaskEdge;
import com.objwww.pr.control.alert.domain.model.RcaRun;
import com.objwww.pr.control.alert.domain.model.RcaRunState;
import com.objwww.pr.control.alert.domain.model.RcaTask;
import com.objwww.pr.control.alert.domain.model.RcaTaskState;
import com.objwww.pr.control.alert.domain.model.RunTrigger;
import com.objwww.pr.control.alert.domain.repository.TaskEdgeRepository;
import com.objwww.pr.control.alert.support.AlertInMemoryStores;
import com.objwww.pr.shared.Digest;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionOperations;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * DeterministicSupervisor 单测（AM4 M4-26，TDD 先行）：
 * 相同提案相同任务图（验收①）、启动/推进/REPORTING 收敛的确定性、
 * 崩溃后重驱动恢复（验收②）、不活跃 run 推进栅栏（INV-AM4-4）。
 */
class DeterministicSupervisorTest {

    private static final Instant NOW = Instant.parse("2026-09-05T08:00:00Z");

    private final AlertInMemoryStores stores = new AlertInMemoryStores();
    private final EdgeStore edges = new EdgeStore();
    private final AgentRegistry agents = new AgentRegistry(List.of(
            profile("metrics"), profile("logs"), profile("reduce")));

    private final AlertClock clock = () -> NOW;

    private DeterministicSupervisor supervisor() {
        return new DeterministicSupervisor(
                new PlanCompiler(agents, stores.tasks, edges, inPlaceTx()),
                new DagExecutionService(edges, stores.tasks),
                stores.runs, stores.tasks, inPlaceTx(), clock);
    }

    // ------------------------------------------------------------------ 验收① 相同提案相同任务图

    @Test
    void sameProposalProducesIsomorphicGraph() {
        UUID run1 = castRun();
        UUID run2 = castRun();

        DeterministicSupervisor.StartResult r1 = supervisor().startRun(run1, proposal(), knownArtifacts());
        DeterministicSupervisor.StartResult r2 = supervisor().startRun(run2, proposal(), knownArtifacts());

        assertThat(r1.outcome()).isEqualTo(DeterministicSupervisor.StartOutcome.STARTED);
        assertThat(r2.outcome()).isEqualTo(DeterministicSupervisor.StartOutcome.STARTED);
        assertThat(r1.proposalDigest()).isEqualTo(r2.proposalDigest());
        assertThat(keySet(run1)).isEqualTo(keySet(run2));
        assertThat(edgeKeyPairs(run1)).isEqualTo(edgeKeyPairs(run2));
    }

    // ------------------------------------------------------------------ 启动与幂等

    @Test
    void startPromotesRootsOnlyAndRunStaysQueued() {
        UUID runId = castRun();

        supervisor().startRun(runId, proposal(), knownArtifacts());

        assertThat(taskState(runId, "investigate-a")).isEqualTo(RcaTaskState.READY);
        assertThat(taskState(runId, "investigate-b")).isEqualTo(RcaTaskState.READY);
        assertThat(taskState(runId, "reduce")).isEqualTo(RcaTaskState.BLOCKED);
        assertThat(stores.runs.findById(runId).orElseThrow().state()).isEqualTo(RcaRunState.QUEUED);
    }

    @Test
    void doubleStartIsIdempotent() {
        UUID runId = castRun();
        DeterministicSupervisor supervisor = supervisor();
        supervisor.startRun(runId, proposal(), knownArtifacts());
        int tasksBefore = stores.tasks.findByRunId(runId).size();

        DeterministicSupervisor.StartResult again =
                supervisor.startRun(runId, proposal(), knownArtifacts());

        assertThat(again.outcome()).isEqualTo(DeterministicSupervisor.StartOutcome.ALREADY_STARTED);
        assertThat(stores.tasks.findByRunId(runId)).hasSize(tasksBefore);
        assertThat(edges.rows).hasSize(2);
    }

    @Test
    void invalidProposalFailsRunClosedWithZeroWrites() {
        UUID runId = castRun();
        List<Map<String, Object>> tasks = List.of(
                Map.of("key", "a", "type", "metrics@1", "inputs", List.of()),
                Map.of("key", "b", "type", "logs@1", "inputs", List.of()));
        List<Map<String, Object>> cycleEdges = List.of(
                Map.of("from", "a", "to", "b", "dependency", "REQUIRED"),
                Map.of("from", "b", "to", "a", "dependency", "REQUIRED"));
        Map<String, Object> cyclic = Map.of("schema_version", "am4-plan.v1",
                "tasks", tasks, "edges", cycleEdges);

        DeterministicSupervisor.StartResult result =
                supervisor().startRun(runId, cyclic, knownArtifacts());

        assertThat(result.outcome()).isEqualTo(DeterministicSupervisor.StartOutcome.PROPOSAL_REJECTED);
        assertThat(result.rejectReason()).contains("环");
        RcaRun run = stores.runs.findById(runId).orElseThrow();
        assertThat(run.state()).isEqualTo(RcaRunState.FAILED);
        assertThat(run.lastError()).isEqualTo("PLAN_REJECTED");
        assertThat(stores.tasks.findByRunId(runId)).isEmpty();
        assertThat(edges.rows).isEmpty();
    }

    @Test
    void startRunOnTerminalRunIsRejected() {
        UUID runId = castRun();
        failRun(runId);

        assertThatThrownBy(() -> supervisor().startRun(runId, proposal(), knownArtifacts()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("不活跃");
    }

    // ------------------------------------------------------------------ 推进与 REPORTING 收敛

    @Test
    void chainAdvancesTaskByTask() {
        UUID runId = castRun();
        DeterministicSupervisor supervisor = supervisor();
        supervisor.startRun(runId, proposal(), knownArtifacts());

        setTask(runId, "investigate-a", RcaTaskState.DONE);
        DeterministicSupervisor.Advancement step = supervisor.advance(runId);
        assertThat(step.reportingEntered()).isFalse();
        assertThat(taskState(runId, "reduce")).isEqualTo(RcaTaskState.BLOCKED);

        setTask(runId, "investigate-b", RcaTaskState.DONE);
        DeterministicSupervisor.Advancement join = supervisor.advance(runId);
        assertThat(join.reportingEntered()).isFalse();
        assertThat(taskState(runId, "reduce")).isEqualTo(RcaTaskState.READY);
        assertThat(stores.runs.findById(runId).orElseThrow().state()).isEqualTo(RcaRunState.QUEUED);
    }

    @Test
    void deadRequiredSkipsDescendantsAndEntersReporting() {
        UUID runId = castRun();
        DeterministicSupervisor supervisor = supervisor();
        supervisor.startRun(runId, proposal(), knownArtifacts());

        setTask(runId, "investigate-a", RcaTaskState.DONE);
        setTask(runId, "investigate-b", RcaTaskState.DEAD);
        DeterministicSupervisor.Advancement result = supervisor.advance(runId);

        assertThat(taskState(runId, "reduce")).isEqualTo(RcaTaskState.SKIPPED);
        assertThat(result.reportingEntered()).isTrue();
        assertThat(stores.runs.findById(runId).orElseThrow().state())
                .isEqualTo(RcaRunState.REPORTING);
    }

    @Test
    void reportingTransitionIsIdempotent() {
        UUID runId = castRun();
        DeterministicSupervisor supervisor = supervisor();
        supervisor.startRun(runId, proposal(), knownArtifacts());
        setTask(runId, "investigate-a", RcaTaskState.DONE);
        setTask(runId, "investigate-b", RcaTaskState.DONE);
        setTask(runId, "reduce", RcaTaskState.DONE);
        supervisor.advance(runId);

        DeterministicSupervisor.Advancement again = supervisor.advance(runId);

        assertThat(again.reportingEntered()).isFalse();
        assertThat(stores.runs.findById(runId).orElseThrow().state())
                .isEqualTo(RcaRunState.REPORTING);
    }

    // ------------------------------------------------------------------ 验收② 恢复（崩溃重驱动）

    @Test
    void freshSupervisorRedrivesAfterCrash() {
        UUID runId = castRun();
        supervisor().startRun(runId, proposal(), knownArtifacts());

        // 进程崩溃重启：全新 Supervisor 实例、同一 DB（stores）——恢复入口只有 advance
        DeterministicSupervisor restarted = supervisor();
        restarted.advance(runId);
        assertThat(taskState(runId, "investigate-a")).isEqualTo(RcaTaskState.READY);
        assertThat(taskState(runId, "investigate-b")).isEqualTo(RcaTaskState.READY);
        assertThat(stores.tasks.findByRunId(runId)).hasSize(3);
        assertThat(edges.rows).hasSize(2);

        setTask(runId, "investigate-a", RcaTaskState.DONE);
        setTask(runId, "investigate-b", RcaTaskState.DONE);
        setTask(runId, "reduce", RcaTaskState.DONE);
        restarted.advance(runId);
        assertThat(stores.runs.findById(runId).orElseThrow().state())
                .isEqualTo(RcaRunState.REPORTING);
    }

    @Test
    void advanceIsFencedWhenRunNotActive() {
        UUID runId = castRun();
        supervisor().startRun(runId, proposal(), knownArtifacts());
        setTask(runId, "investigate-a", RcaTaskState.DONE);
        setTask(runId, "investigate-b", RcaTaskState.DONE);
        failRun(runId);

        DeterministicSupervisor.Advancement result = supervisor().advance(runId);

        // generation fence（INV-AM4-4）：死 run 的图零推进、零迁移（BLOCKED 不放行、不进 REPORTING）
        assertThat(taskState(runId, "reduce")).isEqualTo(RcaTaskState.BLOCKED);
        assertThat(result.reportingEntered()).isFalse();
        assertThat(stores.runs.findById(runId).orElseThrow().state()).isEqualTo(RcaRunState.FAILED);
    }

    @Test
    void advanceOnUnknownRunIsRejected() {
        assertThatThrownBy(() -> supervisor().advance(UUID.randomUUID()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("run 不存在");
    }

    // ------------------------------------------------------------------ 夹具

    private UUID castRun() {
        UUID runId = UUID.randomUUID();
        stores.runs.insert(new RcaRun(runId, UUID.randomUUID(), 0, RunTrigger.RERUN,
                RcaRunState.QUEUED, Digest.sha256Of("material"), NOW, NOW, null, null, null));
        return runId;
    }

    private void failRun(UUID runId) {
        RcaRun run = stores.runs.findById(runId).orElseThrow();
        stores.runs.update(new RcaRun(run.id(), run.incidentId(), run.generation(), run.trigger(),
                RcaRunState.FAILED, run.investigationHash(), run.createdAt(), NOW,
                run.startedAt(), NOW, "TEST_FAILURE"));
    }

    private void setTask(UUID runId, String key, RcaTaskState state) {
        RcaTask task = stores.tasks.findByRunId(runId).stream()
                .filter(t -> t.taskKey().equals(key)).findFirst().orElseThrow();
        boolean updated = stores.tasks.update(new RcaTask(task.id(), task.runId(), task.taskKey(),
                state, task.priority(), task.availableAt(), task.readySince(), task.deadlineAt(),
                null, null, task.leaseEpoch(), task.attemptCount(), task.maxAttempts(),
                task.createdAt(), NOW));
        assertThat(updated).as("task %s 状态直置 %s", key, state).isTrue();
    }

    private RcaTaskState taskState(UUID runId, String key) {
        return stores.tasks.findByRunId(runId).stream()
                .filter(t -> t.taskKey().equals(key)).findFirst().orElseThrow().state();
    }

    private TreeSet<String> keySet(UUID runId) {
        return stores.tasks.findByRunId(runId).stream().map(RcaTask::taskKey)
                .collect(Collectors.toCollection(TreeSet::new));
    }

    private TreeSet<String> edgeKeyPairs(UUID runId) {
        Map<String, String> keyById = new HashMap<>();
        stores.tasks.findByRunId(runId)
                .forEach(t -> keyById.put(t.id().toString(), t.taskKey()));
        return edges.rows.stream()
                .filter(row -> row.runId().equals(runId))
                .map(row -> keyById.get(row.edge().fromTaskId()) + "->"
                        + keyById.get(row.edge().toTaskId()) + ":" + row.edge().dependencyType())
                .collect(Collectors.toCollection(TreeSet::new));
    }

    /** 固定提案：并行双调查 + REQUIRED 归约（输入引用已声明 artifact） */
    private static Map<String, Object> proposal() {
        List<Map<String, Object>> tasks = List.of(
                Map.of("key", "investigate-a", "type", "metrics@1", "inputs", List.of()),
                Map.of("key", "investigate-b", "type", "logs@1", "inputs", List.of()),
                Map.of("key", "reduce", "type", "reduce@1", "inputs", List.of("snapshot:r0")));
        List<Map<String, Object>> edges = List.of(
                Map.of("from", "investigate-a", "to", "reduce", "dependency", "REQUIRED"),
                Map.of("from", "investigate-b", "to", "reduce", "dependency", "REQUIRED"));
        return Map.of("schema_version", "am4-plan.v1", "tasks", tasks, "edges", edges);
    }

    private static Set<String> knownArtifacts() {
        return Set.of("snapshot:r0");
    }

    private static AgentProfile profile(String name) {
        return new AgentProfile(name, "1", "prompt-" + name, "pv",
                Set.of(), Map.of(BudgetKind.STEP, 8L), Map.of("type", "object"));
    }

    private static TransactionOperations inPlaceTx() {
        return new TransactionOperations() {
            @Override
            public <T> T execute(TransactionCallback<T> action) {
                return action.doInTransaction(null);
            }
        };
    }

    /** 最小边仓储内存件（记录 run 归属供断言过滤） */
    private static final class EdgeStore implements TaskEdgeRepository {
        record EdgeRow(UUID runId, TaskEdge edge) {
        }

        final List<EdgeRow> rows = new ArrayList<>();

        @Override
        public void insert(UUID runId, UUID fromTaskId, UUID toTaskId,
                DependencyType dependencyType) {
            rows.add(new EdgeRow(runId,
                    new TaskEdge(fromTaskId.toString(), toTaskId.toString(), dependencyType)));
        }

        @Override
        public List<TaskEdge> findByRunId(UUID runId) {
            return rows.stream().filter(row -> row.runId().equals(runId))
                    .map(EdgeRow::edge).toList();
        }
    }
}
