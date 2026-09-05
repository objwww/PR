package com.objwww.pr.arena.it;

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
 * order-arena 集成测试基座（AM2，沿 control-app PostgresITBase 形态）：
 * Testcontainers 真 PG（postgres:16-alpine）+ 真实角色/授权/Flyway 迁移（arena 域 V1~，
 * classpath:db/migration，历史表落 arena.flyway_schema_history）。
 *
 * <p>形态：
 * <ul>
 *   <li>静态容器全 IT 类共享一个 PG 实例；每个测试方法前 TRUNCATE arena 全部表清场
 *       （GT 的 append-only 是行级 BEFORE 触发器，TRUNCATE 不触发，admin 清场可行）；</li>
 *   <li>admin（容器超级用户）先执行与 deploy/db/01-roles.sh 等价的 DO 块创建五角色
 *       （V1 的 revoke 目标角色必须存在），再以 admin 身份带 placeholder 跑 Flyway；</li>
 *   <li>测试经多条 DataSource 触库：admin（清场/拨时间）、arena_app（业务路径角色）、
 *       chaos_admin_app（管理面路径角色）、eval_app（评测只读）——应用路径权限由真实授权兜底；</li>
 *   <li>INC-09：3.10 内核宿主必须 seccomp=unconfined；</li>
 *   <li>本机无 docker 时整类自动跳过（disabledWithoutDocker），真跑归 195 部署门。</li>
 * </ul>
 */
@Testcontainers(disabledWithoutDocker = true)
public abstract class ArenaPostgresITBase {

    protected static final String ARENA_ROLE = "arena_app";
    protected static final String CHAOS_ADMIN_ROLE = "chaos_admin_app";
    protected static final String EVAL_ROLE = "eval_app";
    protected static final String ARENA_PASSWORD = "it-arena-pass";
    protected static final String CHAOS_ADMIN_PASSWORD = "it-chaos-pass";
    protected static final String EVAL_PASSWORD = "it-eval-pass";

    /** arena 域全部表（V1~V5；CASCADE 兜底 FK 顺序） */
    private static final List<String> ARENA_TABLES = List.of(
            "oa_resource_ledger", "oa_payment_record", "oa_fulfillment_order",
            "oa_refund_order", "oa_idempotency_record", "oa_trade_order",
            "oa_compensation_outbox", "oa_probe_finding", "oa_injection_audit",
            "oa_scenario_map", "oa_chaos_event", "oa_chaos_session",
            "ground_truth_scenario");

    @SuppressWarnings("resource") // 容器由 ryuk 回收；静态生命周期贯穿整个 IT JVM
    protected static final PostgreSQLContainer<?> PG =
            new PostgreSQLContainer<>("postgres:16-alpine")
                    // INC-09：老内核宿主的 seccomp 不支持新镜像 syscall 面
                    .withCreateContainerCmdModifier(cmd -> cmd.getHostConfig()
                            .withSecurityOpts(List.of("seccomp=unconfined")));

    private static HikariDataSource adminDs;
    private static HikariDataSource arenaDs;
    private static HikariDataSource chaosAdminDs;
    private static HikariDataSource evalDs;

    protected static JdbcClient adminJdbc;
    protected static JdbcClient arenaJdbc;
    protected static JdbcClient chaosAdminJdbc;
    protected static JdbcClient evalJdbc;
    protected static TransactionTemplate arenaTx;

    private static synchronized void ensureStarted() {
        if (adminDs != null) {
            return;
        }
        PG.start();

        adminDs = pool(PG.getUsername(), PG.getPassword(), 2);
        adminJdbc = JdbcClient.create(adminDs);

        // 与 deploy/db/01-roles.sh 等价的幂等角色创建（V1 的 revoke/授权依赖角色存在）
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
                        create role arena_app login password '%s';
                    end if;
                    if not exists (select from pg_roles where rolname = 'chaos_admin_app') then
                        create role chaos_admin_app login password '%s';
                    end if;
                    if not exists (select from pg_roles where rolname = 'eval_app') then
                        create role eval_app login password '%s';
                    end if;
                end
                $$;
                """.formatted(ARENA_PASSWORD, CHAOS_ADMIN_PASSWORD, EVAL_PASSWORD))
                .update();

        Flyway.configure()
                .dataSource(adminDs)
                .locations("classpath:db/migration")
                .schemas("arena")
                .placeholders(Map.of(
                        "arena_password", ARENA_PASSWORD,
                        "chaos_admin_password", CHAOS_ADMIN_PASSWORD,
                        "eval_password", EVAL_PASSWORD))
                .load()
                .migrate();

        arenaDs = pool(ARENA_ROLE, ARENA_PASSWORD, 8);
        chaosAdminDs = pool(CHAOS_ADMIN_ROLE, CHAOS_ADMIN_PASSWORD, 4);
        evalDs = pool(EVAL_ROLE, EVAL_PASSWORD, 4);
        arenaJdbc = JdbcClient.create(arenaDs);
        chaosAdminJdbc = JdbcClient.create(chaosAdminDs);
        evalJdbc = JdbcClient.create(evalDs);
        arenaTx = new TransactionTemplate(new DataSourceTransactionManager(arenaDs));
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

    // ------------------------------------------------------------------ 测试动作助手

    protected static DataSource arenaDataSource() {
        return arenaDs;
    }

    /** admin 计数（断言 DB 全貌用，绕开角色视角） */
    protected long count(String table) {
        return adminJdbc.sql("SELECT count(*) FROM arena." + table).query(Long.class).single();
    }

    /**
     * admin 直插一条交易单（契约测试种子；业务路径走 TwoStepOrderService，不经过这里）。
     */
    protected UUID seedTradeOrder(String intentId, String correlationId, String bookingStatus,
                                  String payStatus, String discardReason) {
        return seedTradeOrder(intentId, correlationId, bookingStatus, payStatus,
                discardReason, "sku-9", 0);
    }

    /** 全参种子：sku 自定（模拟世界规则面）、createdAgoSeconds（卡单/超龄扫描面） */
    protected UUID seedTradeOrder(String intentId, String correlationId, String bookingStatus,
                                  String payStatus, String discardReason, String sku,
                                  int createdAgoSeconds) {
        UUID id = UUID.randomUUID();
        adminJdbc.sql("""
                INSERT INTO arena.oa_trade_order(id,intent_id,correlation_id,buyer_id,sku,quantity,
                    amount,booking_status,pay_status,discard_reason,created_at,
                    enabled_at,updated_at)
                VALUES (:id,:intent,:corr,'buyer-1',:sku,1,100.00,:bs,:ps,:reason,
                        now() - make_interval(secs => :ago),
                        CASE WHEN :bs = 'ENABLED' THEN now() END, now())
                """)
                .param("id", id).param("intent", intentId).param("corr", correlationId)
                .param("sku", sku)
                .param("bs", bookingStatus).param("ps", payStatus).param("reason", discardReason)
                .param("ago", createdAgoSeconds)
                .update();
        if (bookingStatus.equals("ENABLED") || bookingStatus.equals("DISCARDED")) {
            adminJdbc.sql("""
                    INSERT INTO arena.oa_fulfillment_order(id,trade_order_id,state)
                    VALUES (:id, :order, :state)
                    """)
                    .param("id", UUID.randomUUID()).param("order", id)
                    .param("state", bookingStatus.equals("ENABLED")
                            ? "CONFIRMED" : "CANCELLED")
                    .update();
        }
        return id;
    }

    /** 种子支付事实（F3 对账面；ageSeconds 供 UNKNOWN 超龄判定） */
    protected UUID seedPayment(UUID orderId, String kind, String result, int ageSeconds) {
        UUID id = UUID.randomUUID();
        adminJdbc.sql("""
                INSERT INTO arena.oa_payment_record(id,order_id,attempt_no,kind,result,amount,
                    initiated_at,settled_at)
                VALUES (:id,:order,1,:kind,:result,100.00,
                        now() - make_interval(secs => :age),
                        CASE WHEN :result IN ('SUCCEEDED','DECLINED') THEN now() END)
                """)
                .param("id", id).param("order", orderId).param("kind", kind)
                .param("result", result).param("age", ageSeconds)
                .update();
        return id;
    }

    /** 种子 chaos 会话（ACTIVE 必带 GT——V3 约束触发器；state/generation 可控） */
    protected UUID seedChaosSession(String scenarioId, String faultType, String target,
                                    String state, long generation, int ttlSeconds) {
        adminJdbc.sql("""
                INSERT INTO arena.ground_truth_scenario(id, schema_version, dataset_version,
                    scenario_id, activation_generation, config_digest, payload_digest,
                    applicable_scope, valid_from, review_status)
                VALUES (:id, 1, 'it-ds', :sid, 0, repeat('a', 64), repeat('b', 64),
                        'arena', now(), 'CONFIRMED')
                """).param("id", UUID.randomUUID()).param("sid", scenarioId).update();
        UUID sessionId = UUID.randomUUID();
        adminJdbc.sql("""
                INSERT INTO arena.oa_chaos_session(id, scenario_id, fault_type, target,
                    ttl_seconds, operator, config_digest, state, generation, expires_at)
                VALUES (:id, :sid, :ft, :target, :ttl, 'it-op', repeat('c', 64), :state,
                        :gen, now() + make_interval(secs => :ttl))
                """)
                .param("id", sessionId).param("sid", scenarioId).param("ft", faultType)
                .param("target", target).param("ttl", ttlSeconds).param("state", state)
                .param("gen", generation)
                .update();
        return sessionId;
    }

    /** 拨乱会话时间（TTL 过期面；created_at 同拨以守 ck_chaos_ttl_positive） */
    protected void expireSession(UUID sessionId) {
        adminJdbc.sql("""
                UPDATE arena.oa_chaos_session
                SET created_at = now() - interval '2 hours',
                    expires_at = now() - interval '1 second'
                WHERE id = :id
                """).param("id", sessionId).update();
    }
}
