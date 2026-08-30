package com.objwww.pr.publisher.infrastructure.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.objwww.pr.publisher.application.OutboxClaimer;
import com.objwww.pr.publisher.application.OutboxRecoveryScanner;
import com.objwww.pr.publisher.domain.handler.CreateCheckHandler;
import com.objwww.pr.publisher.domain.handler.PublicationHandler;
import com.objwww.pr.publisher.domain.handler.PublishReviewHandler;
import com.objwww.pr.publisher.domain.handler.UpdateCheckHandler;
import com.objwww.pr.publisher.domain.port.ExecutionEventAppender;
import com.objwww.pr.publisher.domain.port.PayloadReader;
import com.objwww.pr.publisher.domain.port.PublicationStore;
import com.objwww.pr.publisher.domain.service.FencedPublicationExecutor;
import com.objwww.pr.publisher.infrastructure.credential.AppJwtFactory;
import com.objwww.pr.publisher.infrastructure.credential.CredentialBroker;
import com.objwww.pr.publisher.infrastructure.credential.GitHubAppCredentialBroker;
import com.objwww.pr.publisher.infrastructure.credential.HttpInstallationTokenClient;
import com.objwww.pr.publisher.infrastructure.github.GitHubWriteAdapter;
import com.objwww.pr.publisher.infrastructure.persistence.CasPayloadReader;
import com.objwww.pr.publisher.infrastructure.persistence.PostgresExecutionEventAppender;
import com.objwww.pr.publisher.infrastructure.persistence.PostgresPublicationStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.List;

/**
 * Publisher 全链路接线（仅 docker profile；惯例同 control PersistenceConfig：
 * 默认 profile 无 DataSource，本类不装配，空跑不破）。
 *
 * <p>domain/application 类零 Spring 注解；OutboxClaimer/OutboxRecoveryScanner 的循环
 * 经 {@code initMethod/destroyMethod} 驱动，进程内全局单 worker（B-4 刻意取舍）。
 */
@Configuration
@Profile("docker")
public class PublisherWiringConfig {

    @Bean
    public JdbcClient jdbcClient(DataSource dataSource) {
        return JdbcClient.create(dataSource);
    }

    @Bean
    public TransactionTemplate transactionTemplate(DataSource dataSource) {
        return new TransactionTemplate(new DataSourceTransactionManager(dataSource));
    }

    @Bean
    public ExecutionEventAppender executionEventAppender(JdbcClient jdbc, ObjectMapper objectMapper) {
        return new PostgresExecutionEventAppender(jdbc, objectMapper);
    }

    @Bean
    public PublicationStore publicationStore(JdbcClient jdbc, TransactionTemplate tx,
                                             ExecutionEventAppender appender) {
        return new PostgresPublicationStore(jdbc, tx, appender);
    }

    @Bean
    public PayloadReader payloadReader(@Value("${publisher.payload.cas-root}") String casRoot,
                                       ObjectMapper objectMapper) {
        // Publisher 侧 CAS 只读挂载（与 control 的 app.artifact.cas-dir 同一内容寻址目录）
        return new CasPayloadReader(Path.of(casRoot), objectMapper);
    }

    @Bean
    public CredentialBroker credentialBroker(
            @Value("${publisher.github.app-id}") long appId,
            @Value("${publisher.github.installation-id}") long installationId,
            @Value("${publisher.github.private-key-path}") String privateKeyPath,
            @Value("${publisher.github.api-base:https://api.github.com}") String apiBase,
            @Value("${publisher.github.mint-repositories:}") List<String> mintRepositories) {
        // T14 完整实现：App 私钥（PKCS#8 PEM，私钥不出进程）→ JWT → 收窄 scope 的
        // installation token（TTL 内缓存）。本地手动联调 fallback = EnvCredentialBroker（不装配）。
        return new GitHubAppCredentialBroker(new AppJwtFactory(appId, Path.of(privateKeyPath)),
                new HttpInstallationTokenClient(apiBase), installationId,
                mintRepositories.stream().filter(s -> !s.isBlank()).toList(), Clock.systemUTC());
    }

    @Bean
    public GitHubWriteAdapter gitHubWriteAdapter(CredentialBroker credentialBroker,
                                                 ObjectMapper objectMapper,
                                                 @Value("${publisher.github.api-base:https://api.github.com}") String apiBase,
                                                 @Value("${publisher.github.request-timeout-seconds:30}") long timeoutSeconds) {
        return new GitHubWriteAdapter(credentialBroker, apiBase, objectMapper,
                Duration.ofSeconds(timeoutSeconds));
    }

    @Bean
    public List<PublicationHandler> publicationHandlers() {
        return List.of(new CreateCheckHandler(), new UpdateCheckHandler(), new PublishReviewHandler());
    }

    @Bean
    public FencedPublicationExecutor fencedPublicationExecutor(
            GitHubWriteAdapter gitHubWriteAdapter, PublicationStore store, PayloadReader payloadReader,
            List<PublicationHandler> handlers,
            @Value("${publisher.reconcile.retry-delay-seconds:60}") long reconcileRetryDelaySeconds,
            @Value("${publisher.reconcile.probe-max-pages:3}") int probeMaxPages) {
        return new FencedPublicationExecutor(gitHubWriteAdapter, store, payloadReader, handlers,
                Duration.ofSeconds(reconcileRetryDelaySeconds), probeMaxPages);
    }

    @Bean(initMethod = "start", destroyMethod = "stop")
    public OutboxClaimer outboxClaimer(PublicationStore store, FencedPublicationExecutor executor,
                                       @Value("${publisher.instance-id:publisher-1}") String instanceId,
                                       @Value("${publisher.lease-seconds:60}") long leaseSeconds,
                                       @Value("${publisher.claim.batch-size:10}") int batchSize,
                                       @Value("${publisher.claim.idle-sleep-ms:1000}") long idleSleepMs,
                                       @Value("${publisher.claim.error-sleep-ms:5000}") long errorSleepMs) {
        return new OutboxClaimer(store, executor, instanceId, Duration.ofSeconds(leaseSeconds),
                batchSize, idleSleepMs, errorSleepMs);
    }

    @Bean(initMethod = "start", destroyMethod = "stop")
    public OutboxRecoveryScanner outboxRecoveryScanner(
            PublicationStore store, FencedPublicationExecutor executor,
            List<PublicationHandler> handlers,
            @Value("${publisher.reconcile.unknown-retry-delay-seconds:120}") long unknownRetryDelaySeconds,
            @Value("${publisher.reconcile.max-not-found:5}") int maxReconcileNotFound,
            @Value("${publisher.reconcile.scan-limit:50}") int scanLimit,
            @Value("${publisher.reconcile.idle-sleep-ms:5000}") long idleSleepMs,
            @Value("${publisher.reconcile.error-sleep-ms:10000}") long errorSleepMs) {
        return new OutboxRecoveryScanner(store, executor, handlers,
                Duration.ofSeconds(unknownRetryDelaySeconds), maxReconcileNotFound,
                scanLimit, idleSleepMs, errorSleepMs);
    }
}
