package com.objwww.pr.control.alert.application;

import com.objwww.pr.control.alert.domain.dag.DependencyType;
import com.objwww.pr.control.alert.domain.dag.DagTaskState;
import com.objwww.pr.control.alert.domain.dag.TaskEdge;
import com.objwww.pr.control.alert.domain.model.RcaTask;
import com.objwww.pr.control.alert.domain.model.RcaTaskState;
import com.objwww.pr.control.alert.domain.repository.TaskEdgeRepository;
import com.objwww.pr.control.alert.support.AlertInMemoryStores;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

/**
 * M4-05 环检测接线：addEdge 在落库前跑 DagCycleDetector，成环边拒绝且零写入。
 * M4-06 推进器接线：promoteOnTerminal 消费备料 DagPromoter（GX-5 终局收敛），
 * BLOCKED → READY/SKIPPED 经状态机 + 仓储 CAS 落库。
 * 备料纯函数已穷举单测——这里锁的是接线行为：服务真的挡在仓储前、拒绝时不留半条边、
 * 推进真的落库且并发收敛（并发语义由 195 真 PG IT 复核）。
 */
class DagExecutionServiceTest {

    private static final UUID RUN = UUID.randomUUID();
    private static final Instant NOW = Instant.parse("2026-09-06T10:00:00Z");

    private AlertInMemoryStores stores;
    private InMemoryEdges edges;
    private DagExecutionService service;

    @BeforeEach
    void setUp() {
        stores = new AlertInMemoryStores();
        edges = new InMemoryEdges();
        service = new DagExecutionService(edges, stores.tasks);
    }

    private UUID task(String key, RcaTaskState state) {
        UUID id = UUID.randomUUID();
        stores.tasks.insert(new RcaTask(id, RUN, key, state, 100,
                NOW.minus(Duration.ofMinutes(1)), NOW.minus(Duration.ofMinutes(1)),
                NOW.plus(Duration.ofMinutes(10)), null, null, 0, 0, 3, NOW, NOW));
        return id;
    }

    private void edge(UUID from, UUID to, DependencyType type) {
        edges.insert(RUN, from, to, type);
    }

    private RcaTaskState stateOf(UUID id) {
        return stores.tasks.findById(id).orElseThrow().state();
    }

    @Test
    void addEdgeAcceptsAcyclicChain() {
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        UUID c = UUID.randomUUID();

        service.addEdge(RUN, a, b, DependencyType.REQUIRED);
        service.addEdge(RUN, b, c, DependencyType.REQUIRED);

        assertThat(edges.rows).hasSize(2);
    }

    @Test
    void addEdgeRejectsClosingEdgeAndWritesNothing() {
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        UUID c = UUID.randomUUID();
        service.addEdge(RUN, a, b, DependencyType.REQUIRED);
        service.addEdge(RUN, b, c, DependencyType.REQUIRED);

        assertThatIllegalArgumentException().isThrownBy(
                () -> service.addEdge(RUN, c, a, DependencyType.REQUIRED));

        assertThat(edges.rows).as("成环边被拒，库中不留半条边").hasSize(2);
    }

    @Test
    void addEdgeRejectsSelfLoopBeforeDb() {
        UUID a = UUID.randomUUID();

        assertThatIllegalArgumentException().isThrownBy(
                () -> service.addEdge(RUN, a, a, DependencyType.REQUIRED));

        assertThat(edges.rows).isEmpty();
    }

    // ------------------------------------------------------------------ M4-06 推进器接线

    @Test
    void promoteReadyWhenAllRequiredSucceeded() {
        UUID a = task("A", RcaTaskState.DONE);
        UUID b = task("B", RcaTaskState.BLOCKED);
        edge(a, b, DependencyType.REQUIRED);

        service.promoteOnTerminal(RUN);

        assertThat(stateOf(b)).as("REQUIRED 前驱全部 DONE → BLOCKED 放行 READY").isEqualTo(RcaTaskState.READY);
        assertThat(stateOf(a)).isEqualTo(RcaTaskState.DONE);
    }

    @Test
    void promoteSkippedWhenRequiredFailedTerminal_gx5() {
        UUID a = task("A", RcaTaskState.FAILED_TERMINAL);
        UUID b = task("B", RcaTaskState.BLOCKED);
        edge(a, b, DependencyType.REQUIRED);

        service.promoteOnTerminal(RUN);

        assertThat(stateOf(b)).as("GX-5 终局收敛：REQUIRED 前驱终态未成功 → SKIPPED 不永久 BLOCKED")
                .isEqualTo(RcaTaskState.SKIPPED);
    }

    @Test
    void promoteWaitsWhileRequiredRunning() {
        UUID a = task("A", RcaTaskState.RUNNING);
        UUID b = task("B", RcaTaskState.BLOCKED);
        edge(a, b, DependencyType.REQUIRED);

        service.promoteOnTerminal(RUN);

        assertThat(stateOf(b)).as("REQUIRED 在途 → 保持 BLOCKED 不动").isEqualTo(RcaTaskState.BLOCKED);
    }

    @Test
    void promoteReadyWhenOnlyOptionalFailed() {
        UUID a = task("A", RcaTaskState.DONE);
        UUID b = task("B", RcaTaskState.FAILED_TERMINAL);
        UUID c = task("C", RcaTaskState.BLOCKED);
        edge(a, c, DependencyType.REQUIRED);
        edge(b, c, DependencyType.OPTIONAL);

        service.promoteOnTerminal(RUN);

        assertThat(stateOf(c)).as("OPTIONAL 前驱失败已终态不阻塞，REQUIRED 全成 → READY")
                .isEqualTo(RcaTaskState.READY);
    }

    @Test
    void promoteSkippedCascadeThroughSkippedPredecessor() {
        UUID a = task("A", RcaTaskState.FAILED_TERMINAL);
        UUID b = task("B", RcaTaskState.BLOCKED);
        UUID c = task("C", RcaTaskState.BLOCKED);
        edge(a, b, DependencyType.REQUIRED);
        edge(b, c, DependencyType.REQUIRED);

        service.promoteOnTerminal(RUN);

        assertThat(stateOf(b)).isEqualTo(RcaTaskState.SKIPPED);
        assertThat(stateOf(c)).as("SKIPPED 前驱级联：被剪枝链也收敛 SKIPPED").isEqualTo(RcaTaskState.SKIPPED);
    }

    @Test
    void promoteIsIdempotentOnRerun() {
        UUID a = task("A", RcaTaskState.DONE);
        UUID b = task("B", RcaTaskState.BLOCKED);
        edge(a, b, DependencyType.REQUIRED);

        service.promoteOnTerminal(RUN);
        service.promoteOnTerminal(RUN);

        assertThat(stateOf(b)).as("重跑推进幂等（READY 非 BLOCKED 不再入集）").isEqualTo(RcaTaskState.READY);
    }

    /** 投影映射锁：RcaTaskState → DagTaskState 穷举经 fromPersistent（防扩值漏更，M4-01 同款） */
    @Test
    void persistentStatesProjectExhaustively() {
        for (RcaTaskState state : RcaTaskState.values()) {
            assertThat(DagTaskState.fromPersistent(state))
                    .as("RcaTaskState.%s 必须有 DagTaskState 投影", state)
                    .isNotNull();
        }
    }

    /** 只保序存储的最小 fake（键集/排序契约由 Postgres 实现的 IT 与 M4-04 覆盖） */
    private static final class InMemoryEdges implements TaskEdgeRepository {
        private final List<TaskEdge> rows = new ArrayList<>();

        @Override
        public void insert(UUID runId, UUID fromTaskId, UUID toTaskId, DependencyType dependencyType) {
            rows.add(new TaskEdge(fromTaskId.toString(), toTaskId.toString(), dependencyType));
        }

        @Override
        public List<TaskEdge> findByRunId(UUID runId) {
            return List.copyOf(rows);
        }
    }
}
