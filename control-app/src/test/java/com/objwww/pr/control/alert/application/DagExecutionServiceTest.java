package com.objwww.pr.control.alert.application;

import com.objwww.pr.control.alert.domain.dag.DependencyType;
import com.objwww.pr.control.alert.domain.dag.TaskEdge;
import com.objwww.pr.control.alert.domain.repository.TaskEdgeRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

/**
 * M4-05 环检测接线：addEdge 在落库前跑 DagCycleDetector，成环边拒绝且零写入。
 * 备料纯函数已穷举单测（DagCycleDetectorTest）——这里锁的是接线行为：
 * 服务真的挡在仓储前、拒绝时不留半条边。
 */
class DagExecutionServiceTest {

    private static final UUID RUN = UUID.randomUUID();

    private InMemoryEdges edges;
    private DagExecutionService service;

    @BeforeEach
    void setUp() {
        edges = new InMemoryEdges();
        service = new DagExecutionService(edges);
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
