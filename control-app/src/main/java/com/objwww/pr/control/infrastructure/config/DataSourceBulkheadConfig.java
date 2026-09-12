package com.objwww.pr.control.infrastructure.config;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.simple.JdbcClient;

import javax.sql.DataSource;

/**
 * FUT-26 连接舱壁（B3 步 2，架构 v1.2 §13 预算：ingress 4 / worker 8 / 合计 12）：
 * 入口（webhook 受理/读面查询）与 Worker（领取/投影/收尾）隔离连接池——worker 池
 * 耗尽时入口连接不受拖累，入口拿不到连接快速失败（5s connection-timeout → 503，
 * 映射面已由 AlertWebhookController 的 DataAccessException 兜底承担，步 3 测试证明）。
 *
 * <p>装配纪律：worker 侧 @Primary——PersistenceConfig 既有 bean 的 {@code JdbcClient}
 * 注入透明落 worker 池（零行为漂移；池径 12→8 为 §13 目标值）；入口读面链按 B3 迁移
 * 清单逐链改注 {@code ingressJdbcClient}（试点=ReportQueryConfig.rcaReportReader，
 * 纯 SELECT 无跨面事务；inbox/intake 链按拆解前置核实逐链迁移，发现跨面事务即暂缓
 * 登记）。未迁移 bean 静默落 @Primary worker 池=继续可用，不炸。
 *
 * <p>会话级超时（步 1）两池同享：lock 2s / idle-in-transaction 30s。
 * Flyway 不在本容器（compose one-shot）；eval profile 不装配本类（application-eval.yml
 * 免疫，拆解禁区）。
 */
@Configuration
@Profile("docker")
public class DataSourceBulkheadConfig {

    private static final String INIT_SQL =
            "SET lock_timeout='2s'; SET idle_in_transaction_session_timeout='30s'";

    @Bean
    public DataSource ingressDataSource(
            @Value("${app.datasource.ingress.url:jdbc:postgresql://postgres:5432/pr_agent}") String url,
            @Value("${app.datasource.ingress.username:control_app}") String username,
            @Value("${app.datasource.ingress.password:${CONTROL_DB_PASSWORD}}") String password,
            @Value("${app.datasource.ingress.pool-size:4}") int poolSize) {
        return hikari(url, username, password, poolSize, "alert-ingress");
    }

    @Bean
    @Primary
    public DataSource workerDataSource(
            @Value("${app.datasource.worker.url:jdbc:postgresql://postgres:5432/pr_agent}") String url,
            @Value("${app.datasource.worker.username:control_app}") String username,
            @Value("${app.datasource.worker.password:${CONTROL_DB_PASSWORD}}") String password,
            @Value("${app.datasource.worker.pool-size:8}") int poolSize) {
        return hikari(url, username, password, poolSize, "alert-worker");
    }

    /** worker 池客户端（@Primary）：既有 bean 的 JdbcClient 注入透明落 worker（零漂移） */
    @Bean
    @Primary
    public JdbcClient workerJdbcClient(DataSource workerDataSource) {
        return JdbcClient.create(workerDataSource);
    }

    /** 入口池客户端（具名）：入口读面链按迁移清单逐链改注 */
    @Bean
    public JdbcClient ingressJdbcClient(DataSource ingressDataSource) {
        return JdbcClient.create(ingressDataSource);
    }

    private static HikariDataSource hikari(String url, String username, String password,
            int poolSize, String poolName) {
        // 生产 fail-fast（启动期连接不上即失败，docker health 门语义）
        return hikari(url, username, password, poolSize, poolName, 1);
    }

    /** 包私有测试钩子：initFailTimeout=-1 懒初始化（本机无 PG 的 L0 断言面） */
    static HikariDataSource hikari(String url, String username, String password,
            int poolSize, String poolName, long initializationFailTimeout) {
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(url);
        config.setUsername(username);
        config.setPassword(password);
        config.setMaximumPoolSize(poolSize);
        config.setPoolName(poolName);
        config.setConnectionTimeout(5_000);
        config.setConnectionInitSql(INIT_SQL);
        config.setInitializationFailTimeout(initializationFailTimeout);
        return new HikariDataSource(config);
    }
}
