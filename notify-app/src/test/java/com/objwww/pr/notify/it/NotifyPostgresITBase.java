package com.objwww.pr.notify.it;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * notify-app 集成测试基座（control-app PostgresITBase 同形态）：
 * 真 PG + 真实角色/授权 + Flyway 全链迁移。差异点：迁移本体在 control-app 模块
 * （V1~V11 单一事实源），经 filesystem location 引用——failsafe 工作目录为模块根，
 * {@code ../control-app/src/main/resources/db/migration} 即达。
 *
 * <p>本机无 docker 时整类自动跳过；真跑归 195 全门（M3-30 verify）。
 */
@Testcontainers(disabledWithoutDocker = true)
public abstract class NotifyPostgresITBase {

    protected static final String NOTIFY_ROLE = "notify_app";
    protected static final String NOTIFY_PASSWORD = "it-notify-pass";

    private static final List<String> ALL_TABLES = List.of(
            "pr_subject", "pr_revision", "review_run", "run_step", "work_item", "step_attempt",
            "execution_event", "outbox_command", "outbox_dependency", "publication_resource",
            "review_finding", "artifact", "webhook_inbox", "step_checkpoint", "repair_request",
            "model_call_ledger", "tool_call", "sandbox_job", "artifact_grant",
            "alert_inbox", "alert_event", "incident", "rca_run", "rca_task", "rca_attempt",
            "rca_report", "external_invocation_ledger", "scheduler_slot", "rca_task_edge",
            "rca_investigation_result", "rca_tool_call", "report_publication", "notify_outbox",
            "eval_case_result", "eval_run");

    @SuppressWarnings("resource")
    protected static final PostgreSQLContainer<?> PG =
            new PostgreSQLContainer<>("postgres:16-alpine")
                    .withCreateContainerCmdModifier(cmd -> cmd.getHostConfig()
                            .withSecurityOpts(List.of("seccomp=unconfined")));

    private static HikariDataSource adminDs;
    private static HikariDataSource notifyDs;

    protected static JdbcClient adminJdbc;
    protected static JdbcClient notifyJdbc;

    private static synchronized void ensureStarted() {
        if (adminDs != null) {
            return;
        }
        PG.start();
        adminDs = pool(PG.getUsername(), PG.getPassword(), 2);
        adminJdbc = JdbcClient.create(adminDs);

        adminJdbc.sql("""
                do $$
                begin
                    if not exists (select from pg_roles where rolname = 'control_app') then
                        create role control_app login password 'it-control-pass';
                    end if;
                    if not exists (select from pg_roles where rolname = 'publisher_app') then
                        create role publisher_app login password 'it-publisher-pass';
                    end if;
                    if not exists (select from pg_roles where rolname = 'notify_app') then
                        create role notify_app login password '%s';
                    else
                        alter role notify_app with login password '%s';
                    end if;
                    if not exists (select from pg_roles where rolname = 'eval_app') then
                        create role eval_app login password 'it-eval-pass';
                    end if;
                end
                $$;
                """.formatted(NOTIFY_PASSWORD, NOTIFY_PASSWORD)).update();

        Flyway.configure()
                .dataSource(adminDs)
                .locations("filesystem:../control-app/src/main/resources/db/migration")
                .placeholders(Map.of("notify_password", NOTIFY_PASSWORD))
                .load()
                .migrate();

        notifyDs = pool(NOTIFY_ROLE, NOTIFY_PASSWORD, 6);
        notifyJdbc = JdbcClient.create(notifyDs);
    }

    private static HikariDataSource pool(String user, String password, int size) {
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(PG.getJdbcUrl());
        config.setUsername(user);
        config.setPassword(password);
        config.setMaximumPoolSize(size);
        return new HikariDataSource(config);
    }

    static {
        ensureStarted();
    }

    @BeforeEach
    void truncateAll() {
        adminJdbc.sql("TRUNCATE " + String.join(", ", ALL_TABLES) + " RESTART IDENTITY CASCADE")
                .update();
    }

    // ------------------------------------------------------------------ 种子助手

    /**
     * incident → rca_run → rca_task → rca_attempt → STRUCTURE_VALIDATED 报告
     * （notify_outbox/report_publication 的 FK 面；列清单与 AlertV10EvalRunIT.seedChain 同源）。
     */
    protected record ReportSeed(UUID reportId) {
    }

    protected ReportSeed seedValidatedReport() {
        UUID incidentId = UUID.randomUUID();
        UUID runId = UUID.randomUUID();
        UUID taskId = UUID.randomUUID();
        UUID attemptId = UUID.randomUUID();
        UUID reportId = UUID.randomUUID();
        adminJdbc.sql("""
                INSERT INTO incident(id, incident_key, status, generation, episode_started_at,
                    first_seen_at, last_event_at, created_at, updated_at)
                VALUES (:id, :key, 'FIRING', 0, now(), now(), now(), now(), now())
                """).param("id", incidentId)
                .param("key", "alertname=NotifyIT|service=it-" + incidentId).update();
        adminJdbc.sql("""
                INSERT INTO rca_run(id, incident_id, generation, trigger_kind, state,
                    investigation_hash, created_at, updated_at)
                VALUES (:id, :inc, 0, 'INITIAL', 'SUCCEEDED', :hash, now(), now())
                """).param("id", runId).param("inc", incidentId)
                .param("hash", "it-" + runId).update();
        adminJdbc.sql("""
                INSERT INTO rca_task(id, run_id, task_key, state, priority,
                    available_at, ready_since, deadline_at, created_at, updated_at)
                VALUES (:id, :run, 'HOLMES_INVESTIGATE', 'READY', 100,
                    now(), now(), now(), now(), now())
                """).param("id", taskId).param("run", runId).update();
        adminJdbc.sql("""
                INSERT INTO rca_attempt(id, task_id, attempt_no, lease_epoch, worker_id,
                    status, started_at)
                VALUES (:id, :task, 1, 0, 'it-worker', 'SUCCEEDED', now())
                """).param("id", attemptId).param("task", taskId).update();
        adminJdbc.sql("""
                INSERT INTO rca_report(id, run_id, attempt_id, schema_version, validation_status,
                    package_json, raw_text, usage_missing, created_at)
                VALUES (:id, :run, :attempt, 2, 'STRUCTURE_VALIDATED',
                        CAST('{"schema_version":2}' AS jsonb), 'raw', true, now())
                """).param("id", reportId).param("run", runId)
                .param("attempt", attemptId).update();
        return new ReportSeed(reportId);
    }

    /** report_publication(READY) + 一条 PENDING outbox 行（生产方 ReportCompletedNotifier 同形态）。 */
    protected UUID seedPublicationWithOutbox(ReportSeed seed, String channel,
                                             UUID operationId) {
        UUID publicationId = UUID.randomUUID();
        UUID outboxId = UUID.randomUUID();
        adminJdbc.sql("""
                INSERT INTO report_publication(id, report_id, state, lease_epoch,
                    attempt_count, max_attempts, created_at, updated_at)
                VALUES (:id, :report, 'READY', 0, 0, 5, now(), now())
                """).param("id", publicationId).param("report", seed.reportId()).update();
        adminJdbc.sql("""
                INSERT INTO notify_outbox(id, publication_id, report_id, channel,
                    template_version, operation_id, payload_json, state,
                    attempt_count, max_attempts, created_at, updated_at)
                VALUES (:id, :pub, :report, :channel, 'am3-notice-v1', :op,
                        CAST(:payload AS jsonb), 'PENDING', 0, 5, now(), now())
                """).param("id", outboxId).param("pub", publicationId)
                .param("report", seed.reportId()).param("channel", channel)
                .param("op", operationId.toString())
                .param("payload", "{\"operation_id\":\"" + operationId
                        + "\",\"notice\":\"候选\",\"summary\":\"s\",\"candidate\":true}")
                .update();
        return publicationId;
    }

    protected String outboxState(UUID outboxId) {
        return adminJdbc.sql("SELECT state FROM notify_outbox WHERE id = :id")
                .param("id", outboxId).query(String.class).single();
    }

    protected String publicationState(UUID publicationId) {
        return adminJdbc.sql("SELECT state FROM report_publication WHERE id = :id")
                .param("id", publicationId).query(String.class).single();
    }
}
