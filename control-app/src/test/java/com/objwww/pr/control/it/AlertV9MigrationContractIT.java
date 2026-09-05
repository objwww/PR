package com.objwww.pr.control.it;

import com.objwww.pr.shared.Digest;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * M3-03 验收：V9 迁移契约——四表约束/索引/FK/栅栏列/授权逐项（真 PG + 真实角色）。
 *
 * <p>栅栏列（FUT-50 直挂）在 information_schema 逐列断言；状态列分离的 CHECK
 * （execution/validation 两列独立 + STARTED 生命周期 + STRUCTURE_VALIDATED⇒package）
 * 以链外值/链内矛盾写入门禁；授权矩阵走 notify_app/eval_app 真实角色正反断言。
 * 晚到旧代写入被拒的行为面在 M3-07 仓储 IT（DB 只存列，CAS 在仓储）。
 */
class AlertV9MigrationContractIT extends PostgresITBase {

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
        controlJdbc.sql("""
                INSERT INTO rca_task(id, run_id, task_key, state, priority,
                    available_at, ready_since, deadline_at, created_at, updated_at)
                VALUES (:id, :run, 'HOLMES_INVESTIGATE', 'READY', 100,
                    now(), now(), now(), now(), now())
                """).param("id", seed.taskId()).param("run", seed.runId()).update();
        controlJdbc.sql("""
                INSERT INTO rca_attempt(id, task_id, attempt_no, lease_epoch, worker_id, status, started_at)
                VALUES (:id, :task, 1, 0, 'it-worker', 'STARTED', now())
                """).param("id", seed.attemptId()).param("task", seed.taskId()).update();
        return seed;
    }

    private void insertResultStart(UUID resultId, Seed seed) {
        controlJdbc.sql("""
                INSERT INTO rca_investigation_result(id, attempt_id, run_id, observed_generation,
                    schema_version, execution_status, validation_status, created_at)
                VALUES (:id, :attempt, :run, 0, 2, 'STARTED', 'NOT_VALIDATED', now())
                """).param("id", resultId).param("attempt", seed.attemptId())
                .param("run", seed.runId()).update();
    }

    private void insertResultTerminal(UUID resultId, Seed seed, String execution, String validation,
                                      boolean withPackage) {
        controlJdbc.sql("""
                INSERT INTO rca_investigation_result(id, attempt_id, run_id, observed_generation,
                    schema_version, execution_status, validation_status, package_json,
                    created_at, finished_at)
                VALUES (:id, :attempt, :run, 0, 2, :exec, :val,
                        CASE WHEN :pkg THEN CAST('{"schema_version":2}' AS jsonb) END,
                        now(), now())
                """).param("id", resultId).param("attempt", seed.attemptId())
                .param("run", seed.runId()).param("exec", execution).param("val", validation)
                .param("pkg", withPackage).update();
    }

    // ------------------------------------------------------------------ 契约面

    @Test
    void fourTablesExistWithFenceColumns() {
        List<String> tables = adminJdbc.sql("""
                SELECT tablename FROM pg_tables WHERE schemaname = 'public' AND tablename IN (
                    'rca_investigation_result','rca_tool_call','report_publication','notify_outbox')
                """).query(String.class).list();
        assertThat(tables).hasSize(4);

        // FUT-50 栅栏直挂列逐项在场（禁止多层 JOIN 推导的落点）
        List<String> irColumns = adminJdbc.sql("""
                SELECT column_name FROM information_schema.columns
                WHERE table_name = 'rca_investigation_result' AND column_name IN (
                    'observed_generation','payload_digest','execution_status','validation_status',
                    'raw_artifact_ref','raw_digest','usage_json')
                """).query(String.class).list();
        assertThat(irColumns).hasSize(7);

        List<String> tcColumns = adminJdbc.sql("""
                SELECT column_name FROM information_schema.columns
                WHERE table_name = 'rca_tool_call' AND column_name IN (
                    'run_id','observed_generation','schema_version','payload_digest','created_at')
                """).query(String.class).list();
        assertThat(tcColumns).hasSize(5);
    }

    @Test
    void attemptIdIsIdempotentAnchor() {
        Seed seed = seedAlertChain();
        insertResultStart(UUID.randomUUID(), seed);

        // 一 attempt 一记录：同 attempt 第二条被 uq 拒绝
        assertThat(chainContains(() -> insertResultStart(UUID.randomUUID(), seed),
                "uq_rca_investigation_result_attempt")).isTrue();
    }

    @Test
    void executionAndValidationStatusAreIndependentColumns() {
        Seed seed = seedAlertChain();

        // 执行失败 + 结构验证通过 可共存（不混装：HTTP 超时前的响应可另行落档的形状面）
        // 执行成功 + 结构拒绝 可共存
        insertResultTerminal(UUID.randomUUID(), seed, "SUCCEEDED", "REJECTED_MALFORMED", false);
        insertResultTerminal(UUID.randomUUID(), seed, "FAILED", "NOT_VALIDATED", false);
        assertThat(count("rca_investigation_result")).isEqualTo(2);

        // STRUCTURE_VALIDATED 必须有 package_json（INV-AM3-7 的 DB 面）
        assertThat(chainContains(() -> insertResultTerminal(UUID.randomUUID(), seed,
                        "SUCCEEDED", "STRUCTURE_VALIDATED", false),
                "ck_rca_ir_validated_has_package")).isTrue();
        // 执行/验证状态链外值被拒
        assertThat(chainContains(() -> insertResultTerminal(UUID.randomUUID(), seed,
                        "RUNNING", "NOT_VALIDATED", false),
                "ck_rca_ir_execution")).isTrue();
        assertThat(chainContains(() -> insertResultTerminal(UUID.randomUUID(), seed,
                        "SUCCEEDED", "PENDING", false),
                "ck_rca_ir_validation")).isTrue();
    }

    @Test
    void startedLifecycleCheckEnforcesTerminalTimestamp() {
        Seed seed = seedAlertChain();

        // STARTED 行不允许带 finished_at（ck_rca_ir_execution_lifecycle）
        assertThat(chainContains(() -> controlJdbc.sql("""
                        INSERT INTO rca_investigation_result(id, attempt_id, run_id, observed_generation,
                            schema_version, execution_status, validation_status, created_at, finished_at)
                        VALUES (:id, :attempt, :run, 0, 2, 'STARTED', 'NOT_VALIDATED', now(), now())
                        """).param("id", UUID.randomUUID()).param("attempt", seed.attemptId())
                .param("run", seed.runId()).update(),
                "ck_rca_ir_execution_lifecycle")).isTrue();
    }

    @Test
    void toolCallCompositeKeyAndStatusDomain() {
        Seed seed = seedAlertChain();
        UUID resultId = UUID.randomUUID();
        insertResultStart(resultId, seed);

        insertToolCall(resultId, seed, "tc-1", "SUCCESS");
        // 同 (result, tool_call_id) 重复插被 PK 拒
        assertThat(chainContains(() -> insertToolCall(resultId, seed, "tc-1", "SUCCESS"),
                "rca_tool_call_pkey")).isTrue();
        // 状态域外被拒
        assertThat(chainContains(() -> insertToolCall(resultId, seed, "tc-2", "OK"),
                "rca_tool_call_status")).isTrue();
        // FK：悬空 result 拒
        assertThat(chainContains(() -> insertToolCall(UUID.randomUUID(), seed, "tc-3", "SUCCESS"),
                "rca_tool_call_investigation_result_id_fkey")).isTrue();
        assertThat(count("rca_tool_call")).isEqualTo(1);
    }

    private void insertToolCall(UUID resultId, Seed seed, String toolCallId, String status) {
        controlJdbc.sql("""
                INSERT INTO rca_tool_call(investigation_result_id, tool_call_id, sequence_no,
                    tool_name, status, run_id, observed_generation, schema_version, created_at)
                VALUES (:rid, :tcid, 1, 'prometheus_query', :status, :run, 0, 2, now())
                """).param("rid", resultId).param("tcid", toolCallId).param("status", status)
                .param("run", seed.runId()).update();
    }

    @Test
    void outboxUniqueKeyAndLifecycleChecks() {
        Seed seed = seedAlertChain();
        UUID reportId = seedReport(seed);
        UUID publicationId = seedPublication(seed, reportId);

        insertOutbox(publicationId, reportId, "test", "am3-candidate-v1");
        // §6.5：唯一键 (report_id, channel, template_version)——不靠随机 UUID 防重
        assertThat(chainContains(() -> insertOutbox(publicationId, reportId, "test", "am3-candidate-v1"),
                "uq_notify_outbox_delivery")).isTrue();
        // 同报告不同渠道可并存（一对多）
        insertOutbox(publicationId, reportId, "wecom", "am3-candidate-v1");
        assertThat(count("notify_outbox")).isEqualTo(2);

        // SENT ⇔ sent_at（lifecycle CHECK）
        assertThat(chainContains(() -> adminJdbc.sql("""
                        INSERT INTO notify_outbox(id, publication_id, report_id, channel,
                            template_version, operation_id, payload_json, state,
                            created_at, updated_at)
                        VALUES (:id, :pub, :report, 'sms', 'v1', :op, '{}'::jsonb, 'SENT',
                            now(), now())
                        """).param("id", UUID.randomUUID()).param("pub", publicationId)
                .param("report", reportId).param("op", UUID.randomUUID()).update(),
                "ck_notify_outbox_lifecycle")).isTrue();
    }

    private void insertOutbox(UUID publicationId, UUID reportId, String channel, String template) {
        controlJdbc.sql("""
                INSERT INTO notify_outbox(id, publication_id, report_id, channel,
                    template_version, operation_id, payload_json, state, created_at, updated_at)
                VALUES (:id, :pub, :report, :channel, :template, :op, '{}'::jsonb,
                        'PENDING', now(), now())
                """).param("id", UUID.randomUUID()).param("pub", publicationId)
                .param("report", reportId).param("channel", channel).param("template", template)
                .param("op", UUID.randomUUID()).update();
    }

    @Test
    void rcaReportIsImmutableAtDbLevel() {
        Seed seed = seedAlertChain();
        UUID reportId = seedReport(seed);

        // 报告不可变（BA-10②）：control_app 只有 select,insert（V7 L348），
        // UPDATE/DELETE 在权限面直接拒绝（零行也拒——权限检查先于扫描）
        assertThat(chainContains(() -> controlJdbc.sql(
                        "UPDATE rca_report SET raw_text = raw_text WHERE false").update(),
                "permission denied")).isTrue();
        assertThat(chainContains(() -> controlJdbc.sql(
                        "DELETE FROM rca_report WHERE false").update(),
                "permission denied")).isTrue();
        // 读回路径畅通
        assertThat(controlJdbc.sql("SELECT count(*) FROM rca_report WHERE id = :id")
                .param("id", reportId).query(Long.class).single()).isEqualTo(1L);
    }

    @Test
    void publicationStateMachineChecks() {
        Seed seed = seedAlertChain();
        UUID reportId = seedReport(seed);

        controlJdbc.sql("""
                INSERT INTO report_publication(id, report_id, state, created_at, updated_at)
                VALUES (:id, :report, 'PENDING', now(), now())
                """).param("id", UUID.randomUUID()).param("report", reportId).update();

        // 链外状态被拒
        assertThat(chainContains(() -> adminJdbc.sql("""
                        INSERT INTO report_publication(id, report_id, state, created_at, updated_at)
                        VALUES (:id, :report, 'FLYING', now(), now())
                        """).param("id", UUID.randomUUID()).param("report", UUID.randomUUID()).update(),
                "ck_publication_state")).isTrue();
        // 一报告一发布记录（unique report_id）
        assertThat(chainContains(() -> adminJdbc.sql("""
                        INSERT INTO report_publication(id, report_id, state, created_at, updated_at)
                        VALUES (:id, :report, 'READY', now(), now())
                        """).param("id", UUID.randomUUID()).param("report", reportId).update(),
                "report_publication_report_id_key")).isTrue();
    }

    private UUID seedReport(Seed seed) {
        UUID reportId = UUID.randomUUID();
        controlJdbc.sql("""
                INSERT INTO rca_report(id, run_id, attempt_id, schema_version, validation_status,
                    package_json, raw_text, usage_missing, created_at)
                VALUES (:id, :run, :attempt, 2, 'STRUCTURE_VALIDATED',
                        CAST('{"schema_version":2}' AS jsonb), 'raw', true, now())
                """).param("id", reportId).param("run", seed.runId())
                .param("attempt", seed.attemptId()).update();
        return reportId;
    }

    private UUID seedPublication(Seed seed, UUID reportId) {
        UUID publicationId = UUID.randomUUID();
        controlJdbc.sql("""
                INSERT INTO report_publication(id, report_id, state, created_at, updated_at)
                VALUES (:id, :report, 'READY', now(), now())
                """).param("id", publicationId).param("report", reportId).update();
        return publicationId;
    }

    // ------------------------------------------------------------------ 授权矩阵

    @Test
    void grantsFollowLeastPrivilegeOnRealRoles() {
        Seed seed = seedAlertChain();
        UUID resultId = UUID.randomUUID();
        insertResultStart(resultId, seed);
        UUID reportId = seedReport(seed);
        UUID publicationId = seedPublication(seed, reportId);
        insertOutbox(publicationId, reportId, "test", "am3-candidate-v1");

        // control_app：四表可读写自身面；investigation_result 拒 DELETE/INSERT 外的整行 UPDATE 面
        // （列级 UPDATE 只开口终态列；无 UPDATE 授权的列写 → permission denied）
        assertThat(chainContains(() -> controlJdbc.sql(
                        "UPDATE rca_investigation_result SET attempt_id = attempt_id WHERE false").update(),
                "permission denied")).isTrue();
        assertThat(chainContains(() -> controlJdbc.sql(
                        "DELETE FROM rca_investigation_result WHERE false").update(),
                "permission denied")).isTrue();
        assertThat(chainContains(() -> controlJdbc.sql(
                        "DELETE FROM rca_tool_call WHERE false").update(),
                "permission denied")).isTrue();
        // 终态列 UPDATE 放行（CAS 回写路径）
        controlJdbc.sql("""
                UPDATE rca_investigation_result SET execution_status = 'UNKNOWN',
                    finished_at = now() WHERE id = :id AND execution_status = 'STARTED'
                """).param("id", resultId).update();

        // notify_app：投递面可改（列级），不可 INSERT，不可读报告正文之外的告警域表
        assertThat(chainContains(() -> notifyJdbc.sql(
                        "INSERT INTO notify_outbox(id, publication_id, report_id, channel,"
                                + " template_version, operation_id, payload_json, state,"
                                + " created_at, updated_at)"
                                + " VALUES (:id, :pub, :report, 'x', 't', :op, '{}'::jsonb,"
                                + " 'PENDING', now(), now())")
                .param("id", UUID.randomUUID()).param("pub", publicationId)
                .param("report", reportId).param("op", UUID.randomUUID()).update(),
                "permission denied")).isTrue();
        assertThat(chainContains(() -> notifyJdbc.sql(
                        "UPDATE rca_investigation_result SET model = model WHERE false").update(),
                "permission denied")).isTrue();
        assertThat(chainContains(() -> notifyJdbc.sql(
                        "SELECT count(*) FROM incident").query(Long.class).single(),
                "permission denied")).isTrue();
        // 投递终态翻转放行
        notifyJdbc.sql("""
                UPDATE notify_outbox SET state = 'SENT', sent_at = now(),
                    updated_at = now() WHERE channel = 'test'
                """).update();

        // eval_app：只读（评分数据源），零写入
        assertThat(evalJdbc.sql("SELECT count(*) FROM rca_investigation_result")
                .query(Long.class).single()).isEqualTo(1L);
        assertThat(chainContains(() -> evalJdbc.sql(
                        "UPDATE rca_investigation_result SET model = model WHERE false").update(),
                "permission denied")).isTrue();
        assertThat(chainContains(() -> evalJdbc.sql(
                        "INSERT INTO rca_tool_call(investigation_result_id, tool_call_id,"
                                + " sequence_no, tool_name, run_id, observed_generation,"
                                + " schema_version, created_at)"
                                + " VALUES (:rid, 'x', 1, 't', :run, 0, 2, now())")
                .param("rid", resultId).param("run", seed.runId()).update(),
                "permission denied")).isTrue();

        // publisher_app 显式冻结：连 SELECT 都拒绝
        assertThat(chainContains(() -> publisherJdbc.sql(
                        "SELECT count(*) FROM notify_outbox").query(Long.class).single(),
                "permission denied")).isTrue();
    }

    /** 断言辅助:执行应抛异常,且整条 cause 链文本包含预期片段 */
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
}
