package com.objwww.pr.control.it;

import com.objwww.pr.control.alert.domain.statemachine.RcaStateContract;
import com.objwww.pr.shared.Digest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * M4-02 真 PG 迁移契约：V12 状态全集扩容——rca_task 6→11 态、rca_run 6→9 态的 CHECK 扩容、
 * ck_rca_run_finish 活跃集 +REPORTING、uq_rca_run_active_incident 谓词 +REPORTING
 * （INV-AM1-2 延续：组装期同 incident 不得开新 run）。
 *
 * <p>与 M4-01 Java 侧 RcaStateContractTest 互补：那里锁解析契约（fail-closed），
 * 这里在真 PG 上证明新旧状态同时可写入（additive only）且经契约读回恒等；
 * AM1 旧值零回收（评审 v1.1 修正④：不改名不迁移数据）。命名 *IT 由 failsafe 在
 * verify 阶段执行（本机无 docker 自动跳过；195 真跑）。
 */
class AlertV12StateExpansionIT extends PostgresITBase {

    /** 本类覆盖清场:TRUNCATE 主列表后复位 scheduler_slot 迁移预置行 */
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
    }

    @Test
    void v12ConstraintDefinitionsCarryExpandedSets() {
        // CHECK 三件套：状态集扩容 + 收尾不变式活跃集含 REPORTING
        List<String> defs = adminJdbc.sql("""
                SELECT conname || '|' || pg_get_constraintdef(oid)
                FROM pg_constraint
                WHERE conname IN ('ck_rca_task_state','ck_rca_run_state','ck_rca_run_finish')
                """).query(String.class).list();
        assertThat(defs).hasSize(3);
        assertThat(defs).anySatisfy(d -> {
            assertThat(d).startsWith("ck_rca_task_state|");
            assertThat(d).contains("'BLOCKED'", "'RUNNING'", "'SKIPPED'",
                    "'FAILED_TERMINAL'", "'STALE'");
        });
        assertThat(defs).anySatisfy(d -> {
            assertThat(d).startsWith("ck_rca_run_state|");
            assertThat(d).contains("'REPORTING'", "'PARTIAL'", "'EXPIRED'");
        });
        assertThat(defs).anySatisfy(d -> {
            assertThat(d).startsWith("ck_rca_run_finish|");
            assertThat(d).contains("'REPORTING'");
        });

        // 部分唯一索引谓词 +REPORTING（V7 重建）
        String indexDef = adminJdbc.sql("""
                SELECT indexdef FROM pg_indexes WHERE indexname = 'uq_rca_run_active_incident'
                """).query(String.class).single();
        assertThat(indexDef).contains("'REPORTING'");
    }

    @Test
    void legacyTaskAndRunValuesRemainWritable() {
        Seed seed = seedAlertChain("legacy");
        assertThat(count("rca_run")).isEqualTo(1);
        assertThat(count("rca_task")).isEqualTo(1);
        assertThat(seed.runState()).isEqualTo("QUEUED");
        assertThat(seed.taskState()).isEqualTo("READY");
        // 经 Java 契约读回恒等（M4-01 双读契约的真 PG 回放）
        assertThat(RcaStateContract.parseRunState(seed.runState()))
                .isEqualTo(com.objwww.pr.control.alert.domain.model.RcaRunState.QUEUED);
        assertThat(RcaStateContract.parseTaskState(seed.taskState()))
                .isEqualTo(com.objwww.pr.control.alert.domain.model.RcaTaskState.READY);
    }

    @Test
    void taskNewStatesPassExpandedCheckAndRoundtripThroughContract() {
        Seed seed = seedAlertChain("taskam4");
        List<String> newStates = List.of("BLOCKED", "RUNNING", "SKIPPED", "FAILED_TERMINAL", "STALE");
        for (String state : newStates) {
            UUID taskId = UUID.randomUUID();
            controlJdbc.sql("""
                    INSERT INTO rca_task(id, run_id, task_key, state, priority,
                        available_at, ready_since, deadline_at, created_at, updated_at)
                    VALUES (:id, :run, :key, :state, 0, now(), now(), now(), now(), now())
                    """).param("id", taskId).param("run", seed.runId())
                    .param("key", "AM4_" + state).param("state", state).update();

            String raw = controlJdbc.sql("SELECT state FROM rca_task WHERE id = :id")
                    .param("id", taskId).query(String.class).single();
            assertThat(RcaStateContract.parseTaskState(raw).name())
                    .as("AM4 新态 %s 写入后经契约读回", state).isEqualTo(state);
        }
        assertThat(count("rca_task")).isEqualTo(1 + newStates.size());

        // DB 侧 fail-closed 对应 Java 契约：AM5 值被 CHECK 拒绝
        assertThat(chainContains(() -> controlJdbc.sql("""
                        INSERT INTO rca_task(id, run_id, task_key, state, priority,
                            available_at, ready_since, deadline_at, created_at, updated_at)
                        VALUES (:id, :run, 'WAITING_APPROVAL_PROBE', 'WAITING_APPROVAL', 0,
                            now(), now(), now(), now(), now())
                        """).param("id", UUID.randomUUID()).param("run", seed.runId()).update(),
                "ck_rca_task_state")).isTrue();
    }

    @Test
    void runReportingCountsAsActiveAndPartiaExpiresFinish() {
        // REPORTING 出生（活跃、finished_at 必空）
        Seed seed = seedAlertChain("runam4");
        UUID reportingRun = insertRun(seed.incidentId(), "REPORTING", null);
        assertThat(controlJdbc.sql("SELECT state FROM rca_run WHERE id = :id")
                .param("id", reportingRun).query(String.class).single()).isEqualTo("REPORTING");

        // uq 谓词 +REPORTING：组装期同 incident 不得开新 run
        assertThat(chainContains(() -> insertRun(seed.incidentId(), "QUEUED", null),
                "uq_rca_run_active_incident")).isTrue();

        // REPORTING→PARTIAL 收尾（终态必填 finished_at）
        controlJdbc.sql("""
                UPDATE rca_run SET state = 'PARTIAL', finished_at = now() WHERE id = :id
                """).param("id", reportingRun).update();
        assertThat(controlJdbc.sql("SELECT state FROM rca_run WHERE id = :id")
                .param("id", reportingRun).query(String.class).single()).isEqualTo("PARTIAL");

        // PARTIAL 已不活跃：可再开新 run（uq 谓词释放）
        assertThat(insertRun(seed.incidentId(), "QUEUED", null)).isNotNull();

        // EXPIRED 无 finished_at 被 ck_rca_run_finish 拒绝；带 finished_at 可写
        assertThat(chainContains(() -> insertRun(seed.incidentId(), "EXPIRED", null),
                "ck_rca_run_finish")).isTrue();
        assertThat(insertRun(seed.incidentId(), "EXPIRED",
                java.time.Instant.now())).isNotNull();
    }

    /** 断言辅助:执行应抛异常,且整条 cause 链文本包含预期片段（CHECK/唯一索引翻译层坑同 V7 IT） */
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

    // ------------------------------------------------------------------ 种子

    private record Seed(UUID incidentId, UUID runId, UUID taskId, String runState,
                        String taskState) {
    }

    /** incident → QUEUED run → READY task（control 角色最小列集，V7 同构） */
    private Seed seedAlertChain(String tag) {
        UUID incidentId = UUID.randomUUID();
        UUID runId = UUID.randomUUID();
        UUID taskId = UUID.randomUUID();
        String hash = Digest.sha256Of("it-" + tag + "-" + incidentId).value();
        controlJdbc.sql("""
                INSERT INTO incident(id, incident_key, status, generation, episode_started_at,
                    first_seen_at, last_event_at, created_at, updated_at)
                VALUES (:id, :key, 'FIRING', 0, now(), now(), now(), now(), now())
                """).param("id", incidentId)
                .param("key", "alertname=HighErrorRate|service=" + tag + "-" + incidentId).update();
        controlJdbc.sql("""
                INSERT INTO rca_run(id, incident_id, generation, trigger_kind, state,
                    investigation_hash, created_at, updated_at)
                VALUES (:id, :inc, 0, 'INITIAL', 'QUEUED', :hash, now(), now())
                """).param("id", runId).param("inc", incidentId).param("hash", hash).update();
        controlJdbc.sql("""
                INSERT INTO rca_task(id, run_id, task_key, state, priority,
                    available_at, ready_since, deadline_at, created_at, updated_at)
                VALUES (:id, :run, 'HOLMES_INVESTIGATE', 'READY', 100,
                    now(), now(), now(), now(), now())
                """).param("id", taskId).param("run", runId).update();
        return new Seed(incidentId, runId, taskId, "QUEUED", "READY");
    }

    /** 追加 run：state 直传（含 AM4 新值）；finishedAt 为 null 时按活跃出生 */
    private UUID insertRun(UUID incidentId, String state, java.time.Instant finishedAt) {
        UUID runId = UUID.randomUUID();
        String hash = Digest.sha256Of("it-run-" + runId).value();
        controlJdbc.sql("""
                INSERT INTO rca_run(id, incident_id, generation, trigger_kind, state,
                    investigation_hash, created_at, updated_at, finished_at)
                VALUES (:id, :inc, 0, 'RERUN', :state, :hash, now(), now(), :finished)
                """).param("id", runId).param("inc", incidentId).param("state", state)
                .param("hash", hash).param("finished", finishedAt == null ? null
                        : java.sql.Timestamp.from(finishedAt)).update();
        return runId;
    }
}
