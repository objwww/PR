package com.objwww.pr.control.infrastructure.config;

import com.objwww.pr.control.domain.service.ExecutionEventRepository;
import com.objwww.pr.control.infrastructure.persistence.PostgresExecutionEventRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import javax.sql.DataSource;

/**
 * PG 持久化接线（仅 docker profile）。
 *
 * <p>为什么选 @Profile("docker") 而非 @ConditionalOnProperty("app.persistence.enabled")：
 * 本仓库既有约定就是 profile 驱动的数据源开关——默认 profile 在 application.yml 里直接排除了
 * DataSource/Flyway 自动配置（根本没有 DataSource），application-docker.yml 恢复之。
 * 属性开关会在这个 profile 开关之上再叠一层冗余配置面（两个开关的真值表需要心智维护），
 * 而 @Profile("docker") 与 DataSource 的存在性天然同步：有 DataSource 的 profile 才装配
 * 这些 bean。默认 profile 空跑因此不可能被本类破坏。
 *
 * <p>DataSource 本身由 Boot 自动配置提供；本类只装配 DataSource 之上的 repository bean。
 * 所有实现类刻意不带组件注解（包扫描安全），唯一装配点在这里。
 *
 * <p>AM1-T00 清障后 PR 域仓储全部删除；本类当前只保留 M3 模型治理账本与执行事件账本
 * （AM4 Java 替换 HolmesGPT 时复用）。告警域仓储（V7 九表）由 AM1-T03 在此追加。
 */
@Configuration
@Profile("docker")
public class PersistenceConfig {

    @Bean
    public JdbcClient jdbcClient(DataSource dataSource) {
        return JdbcClient.create(dataSource);
    }

    @Bean
    public NamedParameterJdbcTemplate namedParameterJdbcTemplate(DataSource dataSource) {
        return new NamedParameterJdbcTemplate(dataSource);
    }

    @Bean
    public com.objwww.pr.control.domain.ai.ModelCallLedgerRepository modelCallLedgerRepository(JdbcClient jdbc, DataSource dataSource) {
        return new com.objwww.pr.control.infrastructure.persistence.PostgresModelCallLedgerRepository(
                new JdbcTemplate(dataSource));
    }

    @Bean
    public ExecutionEventRepository executionEventRepository(JdbcClient jdbc, ObjectMapper objectMapper) {
        return new PostgresExecutionEventRepository(jdbc, objectMapper);
    }

    /** T00 清障漏网回流(G0-10 发现):ModelGateway 保留但本 bean 生产者被误删,docker profile 起不来 */
    @Bean
    public com.objwww.pr.control.domain.service.ExecutionLedger executionLedger(
            ExecutionEventRepository repository) {
        return new com.objwww.pr.control.domain.service.ExecutionLedger(repository);
    }

    // ---------------- AM1 告警域仓储（V7 九表，T03 装配） ----------------

    @Bean
    public com.objwww.pr.control.alert.domain.repository.AlertInboxRepository alertInboxRepository(
            JdbcClient jdbc, ObjectMapper objectMapper) {
        return new com.objwww.pr.control.infrastructure.persistence.PostgresAlertInboxRepository(jdbc, objectMapper);
    }

    @Bean
    public com.objwww.pr.control.alert.domain.repository.AlertEventRepository alertEventRepository(
            JdbcClient jdbc, ObjectMapper objectMapper) {
        return new com.objwww.pr.control.infrastructure.persistence.PostgresAlertEventRepository(jdbc, objectMapper);
    }

    @Bean
    public com.objwww.pr.control.alert.domain.repository.IncidentRepository incidentRepository(JdbcClient jdbc) {
        return new com.objwww.pr.control.infrastructure.persistence.PostgresIncidentRepository(jdbc);
    }

    @Bean
    public com.objwww.pr.control.alert.domain.repository.RcaRunRepository rcaRunRepository(JdbcClient jdbc) {
        return new com.objwww.pr.control.infrastructure.persistence.PostgresRcaRunRepository(jdbc);
    }

    @Bean
    public com.objwww.pr.control.alert.domain.repository.RcaTaskRepository rcaTaskRepository(JdbcClient jdbc) {
        return new com.objwww.pr.control.infrastructure.persistence.PostgresRcaTaskRepository(jdbc);
    }

    @Bean
    public com.objwww.pr.control.alert.domain.repository.RcaAttemptRepository rcaAttemptRepository(JdbcClient jdbc) {
        return new com.objwww.pr.control.infrastructure.persistence.PostgresRcaAttemptRepository(jdbc);
    }

    @Bean
    public com.objwww.pr.control.alert.domain.repository.RcaReportRepository rcaReportRepository(JdbcClient jdbc) {
        return new com.objwww.pr.control.infrastructure.persistence.PostgresRcaReportRepository(jdbc);
    }

    @Bean
    public com.objwww.pr.control.alert.domain.repository.ExternalInvocationRepository externalInvocationRepository(
            JdbcClient jdbc) {
        return new com.objwww.pr.control.infrastructure.persistence.PostgresExternalInvocationRepository(jdbc);
    }

    @Bean
    public com.objwww.pr.control.alert.domain.repository.SchedulerSlotRepository schedulerSlotRepository(
            JdbcClient jdbc) {
        return new com.objwww.pr.control.infrastructure.persistence.PostgresSchedulerSlotRepository(jdbc);
    }
}
