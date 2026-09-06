package com.objwww.pr.control.it;

import com.objwww.pr.control.alert.application.DagExecutionService;
import com.objwww.pr.control.alert.domain.dag.DependencyType;
import com.objwww.pr.control.alert.domain.repository.TaskEdgeRepository;
import com.objwww.pr.control.infrastructure.persistence.PostgresTaskEdgeRepository;
import com.objwww.pr.shared.Digest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

/**
 * M4-05 环检测接线真 PG 回归：DagExecutionService.addEdge 挡在 rca_task_edge 前——
 * 链式边可建、成环边拒绝且库中不留半条边（真库计数断言）。
 * 纯函数侧穷举见 DagCycleDetectorTest；接线行为此处以真库为准。
 */
class AlertDagCycleGuardIT extends PostgresITBase {

    private DagExecutionService service;
    private Seed seed;

    @Override
    @BeforeEach
    void truncateAll() {
        super.truncateAll();
        TaskEdgeRepository edges = new PostgresTaskEdgeRepository(controlJdbc);
        var tasks = new com.objwww.pr.control.infrastructure.persistence.PostgresRcaTaskRepository(controlJdbc);
        service = new DagExecutionService(edges, tasks);
        seed = seedRunWithTasks(3);
    }

    @Test
    void chainEdgesLandAndClosingEdgeIsRejected() {
        UUID a = seed.taskIds().get(0);
        UUID b = seed.taskIds().get(1);
        UUID c = seed.taskIds().get(2);

        service.addEdge(seed.runId(), a, b, DependencyType.REQUIRED);
        service.addEdge(seed.runId(), b, c, DependencyType.REQUIRED);

        assertThatIllegalArgumentException().isThrownBy(
                () -> service.addEdge(seed.runId(), c, a, DependencyType.REQUIRED));

        assertThat(count("rca_task_edge")).as("成环边拒绝后库中恰两条链边").isEqualTo(2);
    }

    // ------------------------------------------------------------------ 种子

    private record Seed(UUID incidentId, UUID runId, java.util.List<UUID> taskIds) {
    }

    private Seed seedRunWithTasks(int taskCount) {
        UUID incidentId = UUID.randomUUID();
        UUID runId = UUID.randomUUID();
        String hash = Digest.sha256Of("it-" + incidentId).value();
        controlJdbc.sql("""
                INSERT INTO incident(id, incident_key, status, generation, episode_started_at,
                    first_seen_at, last_event_at, created_at, updated_at)
                VALUES (:id, :key, 'FIRING', 0, now(), now(), now(), now(), now())
                """).param("id", incidentId)
                .param("key", "alertname=HighErrorRate|service=it-" + incidentId).update();
        controlJdbc.sql("""
                INSERT INTO rca_run(id, incident_id, generation, trigger_kind, state,
                    investigation_hash, created_at, updated_at)
                VALUES (:id, :inc, 0, 'INITIAL', 'QUEUED', :hash, now(), now())
                """).param("id", runId).param("inc", incidentId).param("hash", hash).update();
        java.util.List<UUID> taskIds = new java.util.ArrayList<>();
        for (int i = 0; i < taskCount; i++) {
            UUID taskId = UUID.randomUUID();
            controlJdbc.sql("""
                    INSERT INTO rca_task(id, run_id, task_key, state, priority,
                        available_at, ready_since, deadline_at, created_at, updated_at)
                    VALUES (:id, :run, :key, 'READY', 100,
                        now(), now(), now(), now(), now())
                    """).param("id", taskId).param("run", runId)
                    .param("key", "NODE_" + i).update();
            taskIds.add(taskId);
        }
        return new Seed(incidentId, runId, taskIds);
    }
}
