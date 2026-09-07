package com.objwww.pr.control.it;

import com.objwww.pr.control.alert.domain.dag.DependencyType;
import com.objwww.pr.control.alert.domain.repository.TaskEdgeRepository;
import com.objwww.pr.control.infrastructure.persistence.PostgresTaskEdgeRepository;
import com.objwww.pr.shared.Digest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * M4-04 真 PG 迁移契约：V18 边同 run 归属——组合外键强制 edge.run_id == from/to
 * 任务各自所属 run（跨 run 连边 DB 层不可能）；自环/重复边（V8 原有）复锚；
 * 仓储 insert/findByRunId 往返。
 */
class AlertV18TaskEdgeRunScopeIT extends PostgresITBase {

    private TaskEdgeRepository edges;

    @Override
    @BeforeEach
    void truncateAll() {
        adminJdbc.sql("""
                TRUNCATE pr_subject, pr_revision, review_run, run_step, work_item, step_attempt,
                    execution_event, outbox_command, outbox_dependency, publication_resource,
                    review_finding, artifact, webhook_inbox, step_checkpoint, repair_request,
                    model_call_ledger, tool_call, sandbox_job, artifact_grant,
                    alert_inbox, alert_event, incident, rca_run, rca_task, rca_attempt,
                    rca_report, external_invocation_ledger, rca_task_edge
                RESTART IDENTITY CASCADE
                """).update();
        adminJdbc.sql("TRUNCATE scheduler_slot").update();
        adminJdbc.sql("INSERT INTO scheduler_slot(scope, slot_no) VALUES ('rca', 1), ('rca', 2)")
                .update();
        edges = new PostgresTaskEdgeRepository(JdbcClient.create(controlDataSource()));
    }

    @Test
    void v18ConstraintDefinitionsExist() {
        List<String> names = adminJdbc.sql("""
                SELECT conname FROM pg_constraint WHERE conname IN (
                    'uq_rca_task_id_run','fk_rca_task_edge_from_in_run','fk_rca_task_edge_to_in_run')
                """).query(String.class).list();
        assertThat(names).hasSize(3);
    }

    @Test
    void sameRunEdgeRoundtripsThroughRepository() {
        Seed seed = seedTwoRuns();
        edges.insert(seed.runA(), seed.taskA1(), seed.taskA2(), DependencyType.REQUIRED);

        assertThat(edges.findByRunId(seed.runA()))
                .containsExactly(new com.objwww.pr.control.alert.domain.dag.TaskEdge(
                        seed.taskA1().toString(), seed.taskA2().toString(),
                        DependencyType.REQUIRED));
        assertThat(edges.findByRunId(seed.runB())).isEmpty();
    }

    @Test
    void crossRunAndUnknownTaskEdgesRejectedByCompositeFk() {
        Seed seed = seedTwoRuns();
        // from 属 runB、边报 runA → from 组合 FK 拒
        assertThat(chainContains(() -> edges.insert(seed.runA(), seed.taskB1(),
                seed.taskA2(), DependencyType.REQUIRED),
                "fk_rca_task_edge_from_in_run")).isTrue();
        // to 属 runB、边报 runA → to 组合 FK 拒
        assertThat(chainContains(() -> edges.insert(seed.runA(), seed.taskA1(),
                seed.taskB2(), DependencyType.REQUIRED),
                "fk_rca_task_edge_to_in_run")).isTrue();
        // to 任务不存在 → FK 拒（无 (id, run_id) 配对）
        assertThat(chainContains(() -> edges.insert(seed.runA(), seed.taskA1(),
                UUID.randomUUID(), DependencyType.OPTIONAL),
                "fk_rca_task_edge")).isTrue();
        assertThat(count("rca_task_edge")).isZero();
    }

    @Test
    void v8ConstraintsStillHold() {
        Seed seed = seedTwoRuns();
        // 自环（V8 ck_rca_task_edge_no_self）
        assertThat(chainContains(() -> edges.insert(seed.runA(), seed.taskA1(),
                seed.taskA1(), DependencyType.REQUIRED),
                "ck_rca_task_edge_no_self")).isTrue();
        // 重复边（V8 uq_rca_task_edge）
        edges.insert(seed.runA(), seed.taskA1(), seed.taskA2(), DependencyType.REQUIRED);
        assertThat(chainContains(() -> edges.insert(seed.runA(), seed.taskA1(),
                seed.taskA2(), DependencyType.OPTIONAL),
                "uq_rca_task_edge")).isTrue();
        assertThat(count("rca_task_edge")).isEqualTo(1);
    }

    // ------------------------------------------------------------------ 骨架

    private boolean chainContains(Runnable action, String fragment) {
        try {
            action.run();
        } catch (RuntimeException e) {
            StringBuilder chain = new StringBuilder();
            for (Throwable c = e; c != null; c = c.getCause()) {
                chain.append(c.getMessage()).append('\n');
            }
            return chain.toString().contains(fragment);
        }
        return false;
    }

    private record Seed(UUID incidentId, UUID runA, UUID runB,
                        UUID taskA1, UUID taskA2, UUID taskB1, UUID taskB2) {
    }

    /** incident + 两个 run（各 2 个 READY 任务）——最小边测试夹具 */
    private Seed seedTwoRuns() {
        UUID incident = UUID.randomUUID();
        controlJdbc.sql("""
                INSERT INTO incident(id, incident_key, status, generation, episode_started_at,
                    first_seen_at, last_event_at, created_at, updated_at)
                VALUES (:id, :key, 'FIRING', 0, now(), now(), now(), now(), now())
                """).param("id", incident)
                .param("key", "alertname=HighErrorRate|service=edge-" + incident).update();
        UUID runA = insertRun(incident);
        UUID runB = insertRun(incident);
        // V12 uq_rca_run_active_incident：同 incident 只容一条活跃 run——本夹具只测边域
        // （roundtrip/组合外键/自环/重复边，与 run 状态无关），runB 置终态让位谓词
        controlJdbc.sql("""
                UPDATE rca_run SET state = 'SUPERSEDED', finished_at = now() WHERE id = :id
                """).param("id", runB).update();
        Seed seed = new Seed(incident, runA, runB,
                insertTask(runA), insertTask(runA), insertTask(runB), insertTask(runB));
        return seed;
    }

    private UUID insertRun(UUID incidentId) {
        UUID runId = UUID.randomUUID();
        controlJdbc.sql("""
                INSERT INTO rca_run(id, incident_id, generation, trigger_kind, state,
                    investigation_hash, created_at, updated_at)
                VALUES (:id, :inc, 0, 'INITIAL', 'QUEUED', :hash, now(), now())
                """).param("id", runId).param("inc", incidentId)
                .param("hash", Digest.sha256Of("it-" + runId).value()).update();
        return runId;
    }

    private UUID insertTask(UUID runId) {
        UUID taskId = UUID.randomUUID();
        controlJdbc.sql("""
                INSERT INTO rca_task(id, run_id, task_key, state, priority,
                    available_at, ready_since, deadline_at, created_at, updated_at)
                VALUES (:id, :run, :key, 'READY', 0, now(), now(), now(), now(), now())
                """).param("id", taskId).param("run", runId)
                .param("key", "T_" + taskId).update();
        return taskId;
    }
}
