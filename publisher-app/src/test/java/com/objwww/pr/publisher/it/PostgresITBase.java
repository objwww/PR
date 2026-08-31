package com.objwww.pr.publisher.it;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.sql.DataSource;
import java.nio.file.Path;
import java.util.List;

/**
 * 全部 M0 集成测试的基座：Testcontainers 真 PG（postgres:16-alpine）+ 真实角色/授权/Flyway 迁移。
 *
 * <p>形态（M0-T17 决议）：
 * <ul>
 *   <li>静态容器全 IT 类共享一个 PG 实例；每个测试方法前 TRUNCATE 全部 12 张表清场
 *       （RESTART IDENTITY 归零 execution_event.position 与序列）；</li>
 *   <li>admin（容器超级用户）先执行与 deploy/db/01-roles.sh 等价的 DO 块创建
 *       control_app / publisher_app 两角色，再以 admin 身份跑 Flyway V1+V2
 *       （V2 的 grant 必须以表 owner 身份执行）；</li>
 *   <li>之后测试只经三条 DataSource 触库：admin（清场/构造时间偏移等测试动作）、
 *       control_app、publisher_app——应用路径的角色权限由真实授权兜底（AFT-06 动态部分）；</li>
 *   <li>INC-09：3.10 内核宿主必须 seccomp=unconfined，否则 initdb EPERM；</li>
 *   <li>本机无 docker 时整类自动跳过（disabledWithoutDocker），本机 mvn test 不受影响。</li>
 * </ul>
 */
@Testcontainers(disabledWithoutDocker = true)
public abstract class PostgresITBase {

    protected static final String CONTROL_ROLE = "control_app";
    protected static final String PUBLISHER_ROLE = "publisher_app";
    protected static final String CONTROL_PASSWORD = "it-control-pass";
    protected static final String PUBLISHER_PASSWORD = "it-publisher-pass";

    /** V1 的 12 张 + V3 的 webhook_inbox（随 control-app test 依赖进 classpath；CASCADE 兜底） */
    private static final List<String> ALL_TABLES = List.of(
            "pr_subject", "pr_revision", "review_run", "run_step", "work_item", "step_attempt",
            "execution_event", "outbox_command", "outbox_dependency", "publication_resource",
            "review_finding", "artifact", "webhook_inbox");

    @SuppressWarnings("resource") // 容器由 ryuk 回收；静态生命周期贯穿整个 IT JVM
    protected static final PostgreSQLContainer<?> PG =
            new PostgreSQLContainer<>("postgres:16-alpine")
                    // INC-09：老内核宿主的 seccomp 不支持新镜像 syscall 面
                    .withCreateContainerCmdModifier(cmd -> cmd.getHostConfig()
                            .withSecurityOpts(List.of("seccomp=unconfined")));

    private static HikariDataSource adminDs;
    private static HikariDataSource controlDs;
    private static HikariDataSource publisherDs;

    protected static JdbcClient adminJdbc;
    protected static JdbcClient controlJdbc;
    protected static JdbcClient publisherJdbc;
    protected static TransactionTemplate controlTx;
    protected static TransactionTemplate publisherTx;
    protected static final ObjectMapper OM = new ObjectMapper();

    private static synchronized void ensureStarted() {
        if (adminDs != null) {
            return;
        }
        PG.start();

        adminDs = pool(PG.getUsername(), PG.getPassword(), 2);
        adminJdbc = JdbcClient.create(adminDs);

        // 与 deploy/db/01-roles.sh 等价的幂等角色创建（授权在 V2，以 owner 身份跑 Flyway）
        adminJdbc.sql("""
                do $$
                begin
                    if not exists (select from pg_roles where rolname = 'control_app') then
                        create role control_app login password '%s';
                    else
                        alter role control_app with login password '%s';
                    end if;
                    if not exists (select from pg_roles where rolname = 'publisher_app') then
                        create role publisher_app login password '%s';
                    else
                        alter role publisher_app with login password '%s';
                    end if;
                end
                $$;
                """.formatted(CONTROL_PASSWORD, CONTROL_PASSWORD, PUBLISHER_PASSWORD, PUBLISHER_PASSWORD))
                .update();

        Flyway.configure()
                .dataSource(adminDs)
                .locations("classpath:db/migration")
                .load()
                .migrate();

        controlDs = pool(CONTROL_ROLE, CONTROL_PASSWORD, 6);
        publisherDs = pool(PUBLISHER_ROLE, PUBLISHER_PASSWORD, 6);
        controlJdbc = JdbcClient.create(controlDs);
        publisherJdbc = JdbcClient.create(publisherDs);
        controlTx = new TransactionTemplate(new DataSourceTransactionManager(controlDs));
        publisherTx = new TransactionTemplate(new DataSourceTransactionManager(publisherDs));
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

    /** Control/Publisher 共享的 CAS 目录（每测试类一个；内容寻址，清表不清目录无害） */
    @TempDir
    protected static Path casDir;

    @BeforeEach
    void truncateAll() {
        adminJdbc.sql("TRUNCATE " + String.join(", ", ALL_TABLES) + " RESTART IDENTITY CASCADE")
                .update();
    }

    // 连接池与静态容器同生命周期（贯穿整个 failsafe fork），不随单个测试类关闭。

    // ------------------------------------------------------------------ 测试动作助手

    protected static DataSource controlDataSource() {
        return controlDs;
    }

    protected static DataSource publisherDataSource() {
        return publisherDs;
    }

    /** admin 计数（断言 DB 全貌用，绕开角色视角） */
    protected long count(String table) {
        return adminJdbc.sql("SELECT count(*) FROM " + table).query(Long.class).single();
    }

    /** 读 subject 游标三元组（publication_epoch, next_outbox_sequence, last_resolved_sequence） */
    protected long[] subjectCursor(java.util.UUID prSubjectId) {
        return adminJdbc.sql("""
                        SELECT publication_epoch, next_outbox_sequence, last_resolved_sequence
                          FROM pr_subject WHERE id = :id
                        """)
                .param("id", prSubjectId)
                .query((rs, n) -> new long[]{rs.getLong(1), rs.getLong(2), rs.getLong(3)})
                .single();
    }
}
