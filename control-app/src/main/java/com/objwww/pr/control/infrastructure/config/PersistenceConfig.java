package com.objwww.pr.control.infrastructure.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.objwww.pr.control.domain.repository.ArtifactRepository;
import com.objwww.pr.control.domain.repository.OutboxCommandRepository;
import com.objwww.pr.control.domain.repository.PRRevisionRepository;
import com.objwww.pr.control.domain.repository.PRSubjectRepository;
import com.objwww.pr.control.domain.repository.ReviewFindingRepository;
import com.objwww.pr.control.domain.repository.ReviewRunRepository;
import com.objwww.pr.control.domain.repository.RunStepRepository;
import com.objwww.pr.control.domain.repository.StepAttemptRepository;
import com.objwww.pr.control.domain.repository.WorkItemRepository;
import com.objwww.pr.control.domain.service.ExecutionEventRepository;
import com.objwww.pr.control.domain.service.SequenceAllocator;
import com.objwww.pr.control.infrastructure.persistence.PostgresArtifactRepository;
import com.objwww.pr.control.infrastructure.persistence.PostgresExecutionEventRepository;
import com.objwww.pr.control.infrastructure.persistence.PostgresOutboxCommandRepository;
import com.objwww.pr.control.infrastructure.persistence.PostgresPRRevisionRepository;
import com.objwww.pr.control.infrastructure.persistence.PostgresPRSubjectRepository;
import com.objwww.pr.control.infrastructure.persistence.PostgresReviewFindingRepository;
import com.objwww.pr.control.infrastructure.persistence.PostgresReviewRunRepository;
import com.objwww.pr.control.infrastructure.persistence.PostgresRunStepRepository;
import com.objwww.pr.control.infrastructure.persistence.PostgresSequenceAllocator;
import com.objwww.pr.control.infrastructure.persistence.PostgresStepAttemptRepository;
import com.objwww.pr.control.infrastructure.persistence.PostgresWorkItemRepository;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.simple.JdbcClient;

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
 * <p>DataSource 本身由 Boot 自动配置提供；本类只装配 DataSource 之上的 repository/allocator bean。
 * 所有实现类刻意不带组件注解（包扫描安全），唯一装配点在这里。
 */
@Configuration
@Profile("docker")
public class PersistenceConfig {

    @Bean
    public JdbcClient jdbcClient(DataSource dataSource) {
        return JdbcClient.create(dataSource);
    }

    @Bean
    public PRSubjectRepository prSubjectRepository(JdbcClient jdbc) {
        return new PostgresPRSubjectRepository(jdbc);
    }

    @Bean
    public PRRevisionRepository prRevisionRepository(JdbcClient jdbc) {
        return new PostgresPRRevisionRepository(jdbc);
    }

    @Bean
    public ReviewRunRepository reviewRunRepository(JdbcClient jdbc) {
        return new PostgresReviewRunRepository(jdbc);
    }

    @Bean
    public RunStepRepository runStepRepository(JdbcClient jdbc) {
        return new PostgresRunStepRepository(jdbc);
    }

    @Bean
    public WorkItemRepository workItemRepository(JdbcClient jdbc) {
        return new PostgresWorkItemRepository(jdbc);
    }

    @Bean
    public StepAttemptRepository stepAttemptRepository(JdbcClient jdbc) {
        return new PostgresStepAttemptRepository(jdbc);
    }

    @Bean
    public ReviewFindingRepository reviewFindingRepository(JdbcClient jdbc) {
        return new PostgresReviewFindingRepository(jdbc);
    }

    @Bean
    public OutboxCommandRepository outboxCommandRepository(JdbcClient jdbc) {
        return new PostgresOutboxCommandRepository(jdbc);
    }

    @Bean
    public ArtifactRepository artifactRepository(JdbcClient jdbc) {
        return new PostgresArtifactRepository(jdbc);
    }

    @Bean
    public ExecutionEventRepository executionEventRepository(JdbcClient jdbc, ObjectMapper objectMapper) {
        return new PostgresExecutionEventRepository(jdbc, objectMapper);
    }

    @Bean
    public SequenceAllocator sequenceAllocator(JdbcClient jdbc) {
        return new PostgresSequenceAllocator(jdbc);
    }
}
