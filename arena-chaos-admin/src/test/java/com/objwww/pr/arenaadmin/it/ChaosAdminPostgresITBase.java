package com.objwww.pr.arenaadmin.it;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.sql.DataSource;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * arena-chaos-admin 集成测试基座（与 order-arena 的 ArenaPostgresITBase 同构；
 * 刻意复制而非共享测试夹具——两模块各自独立成镜像，测试自治是边界的一部分）。
 * 本基座额外持有 arena_app 视角（注入审计的写者），供恢复收口判定用例播种事实。
 */
@Testcontainers(disabledWithoutDocker = true)
public abstract class ChaosAdminPostgresITBase {

    private static final List<String> ARENA_TABLES = List.of(
            "oa_resource_ledger", "oa_payment_record", "oa_fulfillment_order",
            "oa_refund_order", "oa_idempotency_record", "oa_trade_order",
            "oa_compensation_outbox", "oa_probe_finding", "oa_injection_audit",
            "oa_scenario_map", "oa_chaos_event", "oa_chaos_session",
            "ground_truth_scenario");

    @SuppressWarnings("resource")
    protected static final PostgreSQLContainer<?> PG =
            new PostgreSQLContainer<>("postgres:16-alpine")
                    .withCreateContainerCmdModifier(cmd -> cmd.getHostConfig()
                            .withSecurityOpts(List.of("seccomp=unconfined")));

    private static HikariDataSource adminDs;
    private static HikariDataSource chaosAdminDs;
    private static HikariDataSource arenaDs;

    protected static JdbcClient adminJdbc;
    protected static JdbcClient chaosAdminJdbc;
    protected static JdbcClient arenaJdbc;
    protected static TransactionTemplate chaosAdminTx;

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
                    if not exists (select from pg_roles where rolname = 'arena_app') then
                        create role arena_app login password 'it-arena-pass';
                    end if;
                    if not exists (select from pg_roles where rolname = 'chaos_admin_app') then
                        create role chaos_admin_app login password 'it-chaos-pass';
                    end if;
                    if not exists (select from pg_roles where rolname = 'eval_app') then
                        create role eval_app login password 'it-eval-pass';
                    end if;
                end
                $$;
                """).update();
        Flyway.configure()
                .dataSource(adminDs)
                // 迁移单一事实源在 order-arena 模块（与 deploy/alert 的 arena-migrate 直挂一致）；
                // admin 模块镜像不携带 SQL，测试经 filesystem 直读仓库路径
                .locations("filesystem:../order-arena/src/main/resources/db/migration")
                .schemas("arena")
                .placeholders(Map.of(
                        "arena_password", "it-arena-pass",
                        "chaos_admin_password", "it-chaos-pass",
                        "eval_password", "it-eval-pass"))
                .load()
                .migrate();
        chaosAdminDs = pool("chaos_admin_app", "it-chaos-pass", 4);
        arenaDs = pool("arena_app", "it-arena-pass", 4);
        chaosAdminJdbc = JdbcClient.create(chaosAdminDs);
        arenaJdbc = JdbcClient.create(arenaDs);
        chaosAdminTx = new TransactionTemplate(
                new DataSourceTransactionManager((DataSource) chaosAdminDs));
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
        adminJdbc.sql("TRUNCATE arena." + String.join(", arena.", ARENA_TABLES)
                + " RESTART IDENTITY CASCADE").update();
    }

    /** admin 计数（断言 DB 全貌用，绕开角色视角） */
    protected long count(String table) {
        return adminJdbc.sql("SELECT count(*) FROM arena." + table)
                .query(Long.class).single();
    }

    /** arena 视角落一行注入审计（恢复收口判定的事实源） */
    protected void seedInjectionAudit(UUID sessionId, String faultType, String action,
                                      UUID orderId) {
        arenaJdbc.sql("""
                INSERT INTO arena.oa_injection_audit(id, session_id, fault_type, order_id,
                    action, detail)
                VALUES (:id, :session, :fault, :order, :action, '{}')
                """)
                .param("id", UUID.randomUUID()).param("session", sessionId)
                .param("fault", faultType)
                .param("order", orderId).param("action", action).update();
    }

    protected String sessionState(String scenarioId) {
        return adminJdbc.sql(
                        "SELECT state FROM arena.oa_chaos_session WHERE scenario_id = :s")
                .param("s", scenarioId).query(String.class).single();
    }

    protected long sessionGeneration(String scenarioId) {
        return adminJdbc.sql(
                        "SELECT generation FROM arena.oa_chaos_session WHERE scenario_id = :s")
                .param("s", scenarioId).query(Long.class).single();
    }
}
