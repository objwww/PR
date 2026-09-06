package com.objwww.pr.control.it;

import com.objwww.pr.control.alert.application.DagExecutionService;
import com.objwww.pr.control.alert.domain.dag.DependencyType;
import com.objwww.pr.control.alert.domain.model.RcaTaskState;
import com.objwww.pr.control.alert.domain.repository.RcaTaskRepository;
import com.objwww.pr.control.alert.domain.repository.TaskEdgeRepository;
import com.objwww.pr.control.infrastructure.persistence.PostgresRcaTaskRepository;
import com.objwww.pr.control.infrastructure.persistence.PostgresTaskEdgeRepository;
import com.objwww.pr.shared.Digest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * M4-06 推进器真 PG 集成：DagPromoter 备料纯函数经 DagExecutionService 落真库——
 * REQUIRED 前驱失败 → GX-5 终局收敛 SKIPPED（含级联链单调用收敛）；OPTIONAL 前驱失败不阻塞；
 * 两个前驱并发回执 → BLOCKED→READY 恰一次（CAS 栅栏），终态唯一。
 */
class AlertDagPromotionIT extends PostgresITBase {

    private DagExecutionService service;
    private Seed seed;

    @Override
    @BeforeEach
    void truncateAll() {
        super.truncateAll();
        TaskEdgeRepository edges = new PostgresTaskEdgeRepository(controlJdbc);
        RcaTaskRepository tasks = new PostgresRcaTaskRepository(controlJdbc);
        service = new DagExecutionService(edges, tasks);
        seed = seedRun();
    }

    @Test
    void requiredFailureConvergesSkippedCascadeInSingleCall() {
        UUID a = task("A", RcaTaskState.FAILED_TERMINAL);
        UUID b = task("B", RcaTaskState.BLOCKED);
        UUID c = task("C", RcaTaskState.BLOCKED);
        edge(a, b);
        edge(b, c);

        service.promoteOnTerminal(seed.runId());

        assertThat(stateOf(b)).as("REQUIRED 前驱终态未成功 → SKIPPED").isEqualTo(RcaTaskState.SKIPPED);
        assertThat(stateOf(c)).as("级联链同一调用内收敛（不动点迭代），不永久 BLOCKED")
                .isEqualTo(RcaTaskState.SKIPPED);
    }

    @Test
    void optionalPredecessorFailureDoesNotBlockReady() {
        UUID a = task("A", RcaTaskState.DONE);
        UUID b = task("B", RcaTaskState.FAILED_TERMINAL);
        UUID c = task("C", RcaTaskState.BLOCKED);
        edge(a, c);
        controlJdbc.sql("""
                INSERT INTO rca_task_edge(id, run_id, from_task_id, to_task_id, dependency_type)
                VALUES (:id, :run, :from, :to, 'OPTIONAL')
                """).param("id", UUID.randomUUID()).param("run", seed.runId())
                .param("from", b).param("to", c).update();

        service.promoteOnTerminal(seed.runId());

        assertThat(stateOf(c)).as("OPTIONAL 前驱失败已终态 + REQUIRED 全成 → READY")
                .isEqualTo(RcaTaskState.READY);
    }

    @Test
    void concurrentPromotionsConvergeSingleShot() throws Exception {
        UUID a = task("A", RcaTaskState.DONE);
        UUID b = task("B", RcaTaskState.DONE);
        UUID c = task("C", RcaTaskState.BLOCKED);
        edge(a, c);
        edge(b, c);

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            Future<?> first = pool.submit(() -> promoteAfterLatch(start));
            Future<?> second = pool.submit(() -> promoteAfterLatch(start));
            start.countDown();
            first.get(10, TimeUnit.SECONDS);
            second.get(10, TimeUnit.SECONDS);

            assertThat(stateOf(c)).as("并发回执收敛后 C 恰为 READY（终态唯一）")
                    .isEqualTo(RcaTaskState.READY);
        } finally {
            pool.shutdownNow();
        }
    }

    private void promoteAfterLatch(CountDownLatch start) {
        try {
            start.await(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
        service.promoteOnTerminal(seed.runId());
    }

    // ------------------------------------------------------------------ 种子

    private record Seed(UUID incidentId, UUID runId) {
    }

    private Seed seedRun() {
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
        return new Seed(incidentId, runId);
    }

    private UUID task(String key, RcaTaskState state) {
        UUID taskId = UUID.randomUUID();
        controlJdbc.sql("""
                INSERT INTO rca_task(id, run_id, task_key, state, priority,
                    available_at, ready_since, deadline_at, created_at, updated_at)
                VALUES (:id, :run, :key, :state, 100, now(), now(), now(), now(), now())
                """).param("id", taskId).param("run", seed.runId())
                .param("key", key).param("state", state.name()).update();
        return taskId;
    }

    private void edge(UUID from, UUID to) {
        controlJdbc.sql("""
                INSERT INTO rca_task_edge(id, run_id, from_task_id, to_task_id, dependency_type)
                VALUES (:id, :run, :from, :to, 'REQUIRED')
                """).param("id", UUID.randomUUID()).param("run", seed.runId())
                .param("from", from).param("to", to).update();
    }

    private RcaTaskState stateOf(UUID taskId) {
        return RcaTaskState.valueOf(controlJdbc.sql("SELECT state FROM rca_task WHERE id = :id")
                .param("id", taskId).query(String.class).single());
    }
}
