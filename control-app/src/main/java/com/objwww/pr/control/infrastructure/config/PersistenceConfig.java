package com.objwww.pr.control.infrastructure.config;

import com.objwww.pr.control.domain.service.ExecutionEventRepository;
import com.objwww.pr.control.infrastructure.persistence.PostgresExecutionEventRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
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

    /** M4-04/05：rca_task_edge 读写（V8+V18 约束面上的薄仓储） */
    @Bean
    public com.objwww.pr.control.alert.domain.repository.TaskEdgeRepository taskEdgeRepository(JdbcClient jdbc) {
        return new com.objwww.pr.control.infrastructure.persistence.PostgresTaskEdgeRepository(jdbc);
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

    // ---------------- AM4 预算账本（V13，M4-08 装配；Gate/DoomLoopGuard 由消费方任务 M4-25/26 接线） ----------------

    @Bean
    public com.objwww.pr.control.alert.domain.budget.RunBudgetLedger runBudgetLedger(
            JdbcClient jdbc,
            org.springframework.transaction.support.TransactionOperations tx) {
        return new com.objwww.pr.control.infrastructure.persistence.PostgresRunBudgetLedger(jdbc, tx);
    }

    /** M4-09：Incident 跨 Run 窗口预算（消费方 = run 派生路径，阶段 C 接线） */
    @Bean
    public com.objwww.pr.control.alert.domain.budget.IncidentBudgetLedger incidentBudgetLedger(
            JdbcClient jdbc,
            org.springframework.transaction.support.TransactionOperations tx) {
        return new com.objwww.pr.control.infrastructure.persistence.PostgresIncidentBudgetLedger(jdbc, tx);
    }

    /** M4-10/11：统一事件账本（join 调用方事务的 REQUIRED 模板 + 进度事件 REQUIRES_NEW 模板） */
    @Bean
    public com.objwww.pr.control.alert.domain.event.RcaEventAppender rcaEventAppender(
            JdbcClient jdbc,
            org.springframework.transaction.support.TransactionOperations tx,
            org.springframework.transaction.PlatformTransactionManager txManager) {
        org.springframework.transaction.support.TransactionTemplate independent =
                new org.springframework.transaction.support.TransactionTemplate(txManager);
        independent.setPropagationBehavior(
                org.springframework.transaction.TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        return new com.objwww.pr.control.infrastructure.persistence.PostgresRcaEventAppender(
                jdbc, tx, independent);
    }

    /** M4-18：只读工具调用账本（消费方 = ToolGateway 执行路径接线，M4-27 起） */
    @Bean
    public com.objwww.pr.control.alert.domain.tool.RcaToolInvocationLedger rcaToolInvocationLedger(
            JdbcClient jdbc,
            org.springframework.transaction.support.TransactionOperations tx) {
        return new com.objwww.pr.control.infrastructure.persistence.PostgresRcaToolInvocationLedger(
                jdbc, tx);
    }

    // ---------------- AM4 证据与快照（V16，M4-19/20 装配；消费方 = M4-27+ 执行器族） ----------------

    @Bean
    public com.objwww.pr.control.alert.domain.evidence.EvidenceRepository evidenceRepository(
            JdbcClient jdbc,
            org.springframework.transaction.support.TransactionOperations tx,
            ObjectMapper objectMapper) {
        return new com.objwww.pr.control.infrastructure.persistence.PostgresEvidenceRepository(
                jdbc, tx, objectMapper);
    }

    @Bean
    public com.objwww.pr.control.alert.domain.evidence.EvidenceSnapshotRepository evidenceSnapshotRepository(
            JdbcClient jdbc,
            org.springframework.transaction.support.TransactionOperations tx) {
        return new com.objwww.pr.control.infrastructure.persistence.PostgresEvidenceSnapshotRepository(
                jdbc, tx);
    }

    // ---------------- AM4 断言投影（V17，M4-21/22 装配；消费方 = M4-26 Supervisor 裁决路径） ----------------

    @Bean
    public com.objwww.pr.control.alert.domain.claim.ClaimStore claimStore(
            JdbcClient jdbc,
            org.springframework.transaction.support.TransactionOperations tx,
            ObjectMapper objectMapper,
            com.objwww.pr.control.alert.domain.event.RcaEventAppender rcaEventAppender) {
        return new com.objwww.pr.control.infrastructure.persistence.PostgresClaimStore(
                jdbc, tx, objectMapper, rcaEventAppender);
    }

    // ---------------- AM3 调查落档/通知编排仓储（V9，M3-04 装配） ----------------

    @Bean
    public com.objwww.pr.control.alert.domain.repository.InvestigationResultRepository investigationResultRepository(
            JdbcClient jdbc) {
        return new com.objwww.pr.control.infrastructure.persistence.PostgresInvestigationResultRepository(jdbc);
    }

    @Bean
    public com.objwww.pr.control.alert.domain.repository.RcaToolCallRepository rcaToolCallRepository(
            JdbcClient jdbc) {
        return new com.objwww.pr.control.infrastructure.persistence.PostgresRcaToolCallRepository(jdbc);
    }

    @Bean
    public com.objwww.pr.control.alert.domain.repository.ReportPublicationRepository reportPublicationRepository(
            JdbcClient jdbc) {
        return new com.objwww.pr.control.infrastructure.persistence.PostgresReportPublicationRepository(jdbc);
    }

    @Bean
    public com.objwww.pr.control.alert.domain.repository.NotifyOutboxRepository notifyOutboxRepository(
            JdbcClient jdbc) {
        return new com.objwww.pr.control.infrastructure.persistence.PostgresNotifyOutboxRepository(jdbc);
    }

    // ---------------- AM3 评测持久化（V10，M3-14 装配；生产消费方 = M3-15 eval profile） ----------------

    @Bean
    public com.objwww.pr.control.eval.domain.repository.EvalRunRepository evalRunRepository(
            JdbcClient jdbc) {
        return new com.objwww.pr.control.infrastructure.persistence.PostgresEvalRunRepository(jdbc);
    }

    // ---------------- AM5 发布域（V24，M5-09 装配；消费方 = release/interfaces API） ----------------

    @Bean
    public com.objwww.pr.control.release.domain.repository.ConfigBundleRepository configBundleRepository(
            DataSource dataSource) {
        return new com.objwww.pr.control.infrastructure.persistence.PostgresConfigBundleRepository(
                dataSource);
    }

    @Bean
    public com.objwww.pr.control.release.application.ConfigBundleService configBundleService(
            com.objwww.pr.control.release.domain.repository.ConfigBundleRepository repository) {
        return new com.objwww.pr.control.release.application.ConfigBundleService(repository);
    }

    // M5-10 Canary 决策审计追加面（V25；BA-45：195 真启动实证装配缺口——canaryRouter
    // 依赖本 bean 而 M5-10 只交付了实现类，IT 手工 new 不能替代 Spring 装配面证据）
    @Bean
    public com.objwww.pr.control.release.domain.repository.CanaryDecisionLogRepository canaryDecisionLogRepository(
            JdbcClient jdbc) {
        return new com.objwww.pr.control.infrastructure.persistence.PostgresCanaryDecisionLogRepository(
                jdbc);
    }

    // M6-01 Canary 窗口判定追加面（V30；消费方 = M6-03 窗口任务 / ⑧canary status 只读面）
    @Bean
    public com.objwww.pr.control.release.domain.repository.CanaryWindowVerdictRepository canaryWindowVerdictRepository(
            JdbcClient jdbc) {
        return new com.objwww.pr.control.infrastructure.persistence.PostgresCanaryWindowVerdictRepository(
                jdbc);
    }

    // M6-02 引擎对照结论追加面（V32；消费方 = EngineComparisonRecorder，M6-05 反向影子复用）
    @Bean
    public com.objwww.pr.control.release.domain.repository.EngineComparisonRepository engineComparisonRepository(
            JdbcClient jdbc) {
        return new com.objwww.pr.control.infrastructure.persistence.PostgresEngineComparisonRepository(
                jdbc);
    }

    // ---------------- AM5 处置域（V26，M5-11 装配；HTTP 面 = M5-12 Operator API） ----------------

    @Bean
    public com.objwww.pr.control.ops.domain.repository.OperatorCaseRepository operatorCaseRepository(
            JdbcClient jdbc) {
        return new com.objwww.pr.control.infrastructure.persistence.PostgresOperatorCaseRepository(jdbc);
    }

    @Bean
    public com.objwww.pr.control.ops.application.OperatorCaseService operatorCaseService(
            com.objwww.pr.control.ops.domain.repository.OperatorCaseRepository repository) {
        return new com.objwww.pr.control.ops.application.OperatorCaseService(repository,
                java.time.Instant::now);
    }

    @Bean
    public com.objwww.pr.control.ops.application.OperatorQueryService operatorQueryService(
            com.objwww.pr.control.ops.domain.repository.OperatorCaseRepository repository) {
        return new com.objwww.pr.control.ops.application.OperatorQueryService(repository,
                java.time.Instant::now);
    }

    // ---------------- AM5 观测域（M5-13 装配；HTTP 面 = alert/interfaces EventQueryController） ----------------

    /** rca_event 只读面（表+游标真相源；append-only 由 V14/写侧保证，读侧零迁移） */
    @Bean
    public com.objwww.pr.control.alert.domain.repository.RcaEventReader rcaEventReader(
            JdbcClient jdbc) {
        return new com.objwww.pr.control.infrastructure.persistence.PostgresRcaEventReader(jdbc);
    }

    @Bean
    public com.objwww.pr.control.alert.application.EventQueryService eventQueryService(
            com.objwww.pr.control.alert.domain.repository.RcaEventReader rcaEventReader) {
        return new com.objwww.pr.control.alert.application.EventQueryService(rcaEventReader,
                new com.objwww.pr.control.alert.application.EventPayloadSanitizer(64));
    }

    @Bean
    public com.objwww.pr.control.alert.application.SseStreamService sseStreamService(
            com.objwww.pr.control.alert.application.EventQueryService eventQueryService) {
        return new com.objwww.pr.control.alert.application.SseStreamService(eventQueryService,
                java.time.Duration.ofSeconds(30));
    }

    @Bean
    public com.objwww.pr.control.alert.application.RunQueryService runQueryService(
            com.objwww.pr.control.alert.domain.repository.RcaRunRepository rcaRunRepository,
            com.objwww.pr.control.alert.domain.repository.RcaTaskRepository rcaTaskRepository,
            com.objwww.pr.control.alert.domain.repository.TaskEdgeRepository taskEdgeRepository) {
        return new com.objwww.pr.control.alert.application.RunQueryService(
                rcaRunRepository, rcaTaskRepository, taskEdgeRepository, java.time.Instant::now);
    }

    // ---------------- AM5 命令域（V27，M5-14 装配；HTTP 面 = alert/interfaces RunCommandController） ----------------

    @Bean
    public com.objwww.pr.control.alert.domain.repository.OperatorCommandRepository operatorCommandRepository(
            JdbcClient jdbc) {
        return new com.objwww.pr.control.infrastructure.persistence.PostgresOperatorCommandRepository(
                jdbc);
    }

    @Bean
    public com.objwww.pr.control.alert.application.CommandService commandService(
            com.objwww.pr.control.alert.domain.repository.OperatorCommandRepository operatorCommandRepository,
            com.objwww.pr.control.alert.domain.repository.RcaRunRepository rcaRunRepository,
            com.objwww.pr.control.alert.domain.event.RcaEventAppender rcaEventAppender) {
        return new com.objwww.pr.control.alert.application.CommandService(
                operatorCommandRepository, rcaRunRepository, rcaEventAppender,
                java.time.Instant::now);
    }

    // ---------------- AM5 保留域（V28/V29，M5-18 装配；归档执行面 = M5-19 ArchiveService） ----------------

    @Bean
    public com.objwww.pr.control.ops.domain.repository.RetentionPolicyRepository retentionPolicyRepository(
            JdbcClient jdbc) {
        return new com.objwww.pr.control.infrastructure.persistence.PostgresRetentionPolicyRepository(
                jdbc);
    }

    @Bean
    public com.objwww.pr.control.ops.domain.repository.PartitionCatalog partitionCatalog(JdbcClient jdbc) {
        return new com.objwww.pr.control.infrastructure.persistence.PostgresPartitionCatalog(jdbc);
    }

    @Bean
    public com.objwww.pr.control.ops.application.RetentionService retentionService(
            com.objwww.pr.control.ops.domain.repository.RetentionPolicyRepository retentionPolicyRepository,
            com.objwww.pr.control.ops.domain.repository.PartitionCatalog partitionCatalog) {
        return new com.objwww.pr.control.ops.application.RetentionService(
                retentionPolicyRepository, partitionCatalog, java.time.Instant::now);
    }

    // ---------------- AM5 冷归档（M5-19 装配；冷层形态开放项 O-5，本地盘卷先落） ----------------

    @Bean
    public com.objwww.pr.control.ops.domain.repository.ArchiveManifestRepository archiveManifestRepository(
            JdbcClient jdbc) {
        return new com.objwww.pr.control.infrastructure.persistence.PostgresArchiveManifestRepository(
                jdbc);
    }

    @Bean
    public com.objwww.pr.control.ops.domain.repository.PartitionArchiveGateway partitionArchiveGateway(
            javax.sql.DataSource dataSource) {
        return new com.objwww.pr.control.infrastructure.persistence.PostgresPartitionArchiveGateway(
                new org.springframework.jdbc.core.JdbcTemplate(dataSource));
    }

    @Bean
    public com.objwww.pr.control.ops.domain.repository.ColdArchiveStore coldArchiveStore(
            @Value("${app.ops.archive.cold-dir:./var/archive}") String coldDir) {
        return new com.objwww.pr.control.infrastructure.archive.LocalColdArchiveStore(
                java.nio.file.Path.of(coldDir));
    }

    @Bean
    public com.objwww.pr.control.ops.application.ArchiveService archiveService(
            com.objwww.pr.control.ops.domain.repository.RetentionPolicyRepository retentionPolicyRepository,
            com.objwww.pr.control.ops.application.RetentionService retentionService,
            com.objwww.pr.control.ops.domain.repository.PartitionArchiveGateway partitionArchiveGateway,
            com.objwww.pr.control.ops.domain.repository.ColdArchiveStore coldArchiveStore,
            com.objwww.pr.control.ops.domain.repository.ArchiveManifestRepository archiveManifestRepository) {
        return new com.objwww.pr.control.ops.application.ArchiveService(
                retentionPolicyRepository, retentionService, partitionArchiveGateway,
                coldArchiveStore, archiveManifestRepository);
    }
}
