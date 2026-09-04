package com.objwww.pr.control.it;

import com.objwww.pr.shared.Digest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * CT-A01 真 PG 落地（G0-08）：V7+V8 迁移契约——10 张告警域表、真实角色授权（INV-AM1-5）、
 * rca_report 状态链 CHECK 扩全链（AA-16）、rca_task 预留列默认值、rca_task_edge 约束。
 *
 * <p>与 M7MigrationContractTest（本地静态门）互补：静态门锁 SQL 文本形状，本类在真 PG
 * 上以 control_app / publisher_app 真实角色验证行为。命名 *IT 由 failsafe 在 verify 阶段执行
 * （本机无 docker 自动跳过；195 maven 容器真跑）。
 */
class AlertV7MigrationContractIT extends PostgresITBase {

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
        // scheduler_slot.task_id 外键指向 rca_task——上面 TRUNCATE 的 CASCADE 会连带清空它
        // (195 真跑实证:count 掉 0)。预置行按 V7 同款复位(插入文本由 M7MigrationContractTest
        // 静态门锁定,这里复位行为形状使 preset 断言与类执行顺序解耦)
        adminJdbc.sql("TRUNCATE scheduler_slot").update();
        adminJdbc.sql("INSERT INTO scheduler_slot(scope, slot_no) VALUES ('rca', 1), ('rca', 2)")
                .update();
    }

    @Test
    void tenAlertTablesExistWithSlotPreset() {
        List<String> tables = adminJdbc.sql("""
                SELECT tablename FROM pg_tables WHERE schemaname = 'public' AND tablename IN (
                    'alert_inbox','alert_event','incident','rca_run','rca_task','rca_attempt',
                    'rca_report','external_invocation_ledger','scheduler_slot','rca_task_edge')
                """).query(String.class).list();
        assertThat(tables).hasSize(10);

        // V7 固定槽位形状(scope=rca × 2;truncateAll 已按 V7 同款复位,与类执行顺序解耦)
        assertThat(count("scheduler_slot")).isEqualTo(2);
    }

    @Test
    void grantsFollowLeastPrivilegeOnRealRoles() {
        // control_app 只增不改面:alert_event / rca_report / rca_task_edge 拒 UPDATE
        // 注:PG 的 42xxx(含 42501 permission denied)被 Spring 译成 BadSqlGrammarException,
        // "permission denied" 只在根因链——断言走整链文本(195 真跑首发现的翻译层坑)
        assertThat(chainContains(() -> controlJdbc.sql(
                        "UPDATE alert_event SET fingerprint = fingerprint WHERE false").update(),
                "permission denied")).isTrue();
        assertThat(chainContains(() -> controlJdbc.sql(
                        "UPDATE rca_report SET model = model WHERE false").update(),
                "permission denied")).isTrue();
        assertThat(chainContains(() -> controlJdbc.sql(
                        "UPDATE rca_task_edge SET dependency_type = dependency_type WHERE false").update(),
                "permission denied")).isTrue();
        // 固定槽位表拒 INSERT(只允许租约翻转)
        assertThat(chainContains(() -> controlJdbc.sql(
                        "INSERT INTO scheduler_slot(scope, slot_no) VALUES ('rca', 99)").update(),
                "permission denied")).isTrue();
        // SELECT 面正常
        assertThat(count("alert_event")).isZero();
        // publisher_app 显式冻结:连 SELECT 都拒绝
        assertThat(chainContains(() -> publisherJdbc.sql(
                        "SELECT count(*) FROM incident").query(Long.class).single(),
                "permission denied")).isTrue();
    }

    @Test
    void reportValidationStatusAcceptsFullAa16Chain() {
        Seed seed = seedAlertChain();
        for (String status : List.of("DRAFT", "STRUCTURE_VALIDATED", "EVIDENCE_VALIDATED",
                "NEEDS_REVIEW", "PUBLISHED", "REJECTED", "SUPERSEDED",
                "REJECTED_MALFORMED", "REJECTED_OVERSIZE",
                "REJECTED_SCHEMA_VERSION", "REJECTED_SCHEMA_MISMATCH")) {
            insertReport(UUID.randomUUID(), seed, status);
        }
        assertThat(count("rca_report")).isEqualTo(11);

        // 链外值被 CHECK 拒绝
        assertThat(chainContains(() -> insertReport(UUID.randomUUID(), seed, "BOGUS_STATUS"),
                "ck_rca_report_status")).isTrue();
    }

    @Test
    void rcaTaskReservedColumnsCarryDefaults() {
        // V8 预留列全部在场（information_schema）
        List<String> columns = adminJdbc.sql("""
                SELECT column_name FROM information_schema.columns
                WHERE table_name = 'rca_task' AND column_name IN (
                    'task_type','agent_profile','observed_generation','input_digest',
                    'output_artifact_ref','optional','max_total_duration','schema_version')
                """).query(String.class).list();
        assertThat(columns).hasSize(8);

        Seed seed = seedAlertChain();
        UUID task = seed.taskId();
        // seed 即最小列集插入:预留列走默认(AM1 不写入)——直接读回断言
        var row = controlJdbc.sql("""
                SELECT schema_version, optional, task_type, observed_generation
                FROM rca_task WHERE id = :id
                """).param("id", task).query((rs, n) -> new Object[]{
                rs.getInt(1), rs.getBoolean(2), rs.getString(3), rs.getObject(4)}).single();
        assertThat(row[0]).isEqualTo(1);        // schema_version 默认 1（契约版本化落点）
        assertThat(row[1]).isEqualTo(false);    // optional 默认 false
        assertThat(row[2]).isNull();            // 预留列 AM1 零写入
        assertThat(row[3]).isNull();
    }

    @Test
    void rcaTaskEdgeEnforcesUniquenessAndDependencyTypes() {
        Seed seed = seedAlertChain();
        // uq_rca_task_key(run_id, task_key):同 run 第二个任务必须换 key(seed 已占 HOLMES_INVESTIGATE)
        UUID taskA = seed.taskId();
        UUID taskB = insertTask(seed, UUID.randomUUID(), "RESERVE_DAG_NODE");

        insertEdge(seed.runId(), taskA, taskB, "REQUIRED");

        // uq(from, to)：同边重复插被拒
        assertThat(chainContains(() -> insertEdge(seed.runId(), taskA, taskB, "OPTIONAL"),
                "uq_rca_task_edge")).isTrue();
        // 自环被拒
        assertThat(chainContains(() -> insertEdge(seed.runId(), taskA, taskA, "REQUIRED"),
                "ck_rca_task_edge_no_self")).isTrue();
        // 链外 dependency_type 被拒
        assertThat(chainContains(() -> insertEdge(seed.runId(), taskB, taskA, "BOGUS"),
                "ck_rca_task_edge_dep")).isTrue();
        assertThat(count("rca_task_edge")).isEqualTo(1);
    }

    /** 断言辅助:执行应抛异常,且整条 cause 链文本包含预期片段(Spring 会把 PG 42xxx 译成 BadSqlGrammar) */
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

    private record Seed(UUID incidentId, UUID runId, UUID taskId, UUID attemptId) {
    }

    /** incident → QUEUED run → READY task → STARTED attempt（control 角色最小列集） */
    private Seed seedAlertChain() {
        Seed seed = new Seed(UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), UUID.randomUUID());
        String hash = Digest.sha256Of("it-" + UUID.randomUUID()).value();
        controlJdbc.sql("""
                INSERT INTO incident(id, incident_key, status, generation, episode_started_at,
                    first_seen_at, last_event_at, created_at, updated_at)
                VALUES (:id, :key, 'FIRING', 0, now(), now(), now(), now(), now())
                """).param("id", seed.incidentId())
                .param("key", "alertname=HighErrorRate|service=it-" + seed.incidentId()).update();
        controlJdbc.sql("""
                INSERT INTO rca_run(id, incident_id, generation, trigger_kind, state,
                    investigation_hash, created_at, updated_at)
                VALUES (:id, :inc, 0, 'INITIAL', 'QUEUED', :hash, now(), now())
                """).param("id", seed.runId()).param("inc", seed.incidentId())
                .param("hash", hash).update();
        insertTask(seed, seed.taskId(), "HOLMES_INVESTIGATE");
        controlJdbc.sql("""
                INSERT INTO rca_attempt(id, task_id, attempt_no, lease_epoch, worker_id, status, started_at)
                VALUES (:id, :task, 1, 0, 'it-worker', 'STARTED', now())
                """).param("id", seed.attemptId()).param("task", seed.taskId()).update();
        return seed;
    }

    private UUID insertTask(Seed seed, UUID taskId, String taskKey) {
        controlJdbc.sql("""
                INSERT INTO rca_task(id, run_id, task_key, state, priority,
                    available_at, ready_since, deadline_at, created_at, updated_at)
                VALUES (:id, :run, :key, 'READY', 100,
                    now(), now(), now(), now(), now())
                """).param("id", taskId).param("run", seed.runId()).param("key", taskKey).update();
        return taskId;
    }

    private void insertEdge(UUID runId, UUID from, UUID to, String dependencyType) {
        controlJdbc.sql("""
                INSERT INTO rca_task_edge(id, run_id, from_task_id, to_task_id, dependency_type)
                VALUES (:id, :run, :from, :to, :dep)
                """).param("id", UUID.randomUUID()).param("run", runId)
                .param("from", from).param("to", to).param("dep", dependencyType).update();
    }

    private void insertReport(UUID id, Seed seed, String status) {
        controlJdbc.sql("""
                INSERT INTO rca_report(id, run_id, attempt_id, schema_version, validation_status,
                    package_json, raw_text, usage_missing, created_at)
                VALUES (:id, :run, :attempt, 1, :status,
                    CAST('{"schema_version":1}' AS jsonb), 'raw', true, now())
                """).param("id", id).param("run", seed.runId())
                .param("attempt", seed.attemptId()).param("status", status).update();
    }
}
