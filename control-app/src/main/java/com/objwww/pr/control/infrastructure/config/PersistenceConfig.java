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
            @org.springframework.beans.factory.annotation.Qualifier("executionEventRepository")
            ExecutionEventRepository executionEventRepository) {
        return new com.objwww.pr.control.domain.service.ExecutionLedger(executionEventRepository);
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

    /** EX-C3a：认证事件审计面（V42 auth_event；append-only） */
    @Bean
    public com.objwww.pr.control.auth.domain.AuthEventRepository authEventRepository(JdbcClient jdbc) {
        return new com.objwww.pr.control.infrastructure.persistence.PostgresAuthEventRepository(jdbc);
    }

    /** AUTH-1：平台账号仓储（V44 platform_user；password_hash 唯一读出口=findForAuth） */
    @Bean
    public com.objwww.pr.control.auth.domain.PlatformUserRepository platformUserRepository(JdbcClient jdbc) {
        return new com.objwww.pr.control.infrastructure.persistence.PostgresPlatformUserRepository(jdbc);
    }

    /** AUTH-1：平台账号 UserDetailsService（SecurityConfig 双提供者之 DB 面） */
    @Bean
    public com.objwww.pr.control.infrastructure.auth.PlatformUserDetailsService platformUserDetailsService(
            com.objwww.pr.control.auth.domain.PlatformUserRepository platformUserRepository) {
        return new com.objwww.pr.control.infrastructure.auth.PlatformUserDetailsService(
                platformUserRepository);
    }

    @Bean
    public com.objwww.pr.control.alert.domain.repository.RcaRunRepository rcaRunRepository(
            JdbcClient jdbc,
            com.objwww.pr.control.alert.domain.repository.RunConfigEpochRepository
                    runConfigEpochRepository) {
        return new com.objwww.pr.control.infrastructure.persistence.PostgresRcaRunRepository(
                jdbc, runConfigEpochRepository);
    }

    /** EN-04：配置代际追加史（V63；UNIQUE(run_id, config_epoch) 唯一键守卫） */
    @Bean
    public com.objwww.pr.control.alert.domain.repository.RunConfigEpochRepository
    runConfigEpochRepository(JdbcClient jdbc) {
        return new com.objwww.pr.control.infrastructure.persistence.PostgresRunConfigEpochRepository(
                jdbc);
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

    /** R7-X1：任务→角色冻结绑定（V46；只增不改，恢复只读） */
    @Bean
    public com.objwww.pr.control.alert.domain.repository.TaskExecutionBindingRepository
    taskExecutionBindingRepository(JdbcClient jdbc, ObjectMapper objectMapper) {
        return new com.objwww.pr.control.infrastructure.persistence.PostgresTaskExecutionBindingRepository(
                jdbc, objectMapper);
    }

    /** R7-X4：主任务检查点（V47；task_id 幂等锚 upsert + 相位 CAS） */
    @Bean
    public com.objwww.pr.control.alert.domain.repository.PrimaryCheckpointRepository
    primaryCheckpointRepository(JdbcClient jdbc, ObjectMapper objectMapper) {
        return new com.objwww.pr.control.infrastructure.persistence.PostgresPrimaryCheckpointRepository(
                jdbc, objectMapper);
    }

    /** R7-X4/X11：委派裁决台账（V47；只增不改，uq(run,gap) 去重面） */
    @Bean
    public com.objwww.pr.control.alert.domain.repository.DelegationDecisionRepository
    delegationDecisionRepository(JdbcClient jdbc) {
        return new com.objwww.pr.control.infrastructure.persistence.PostgresDelegationDecisionRepository(
                jdbc);
    }

    /**
     * R7a-1：RCA 模型调用账本（V48；PENDING 先行=发送资格，终态 CAS）。
     * EN-04：open 在 run 行锁内验 epoch 栅栏（H04 调度闸，与切换应用同锁序）。
     */
    @Bean
    public com.objwww.pr.control.alert.domain.agent.RcaModelCallLedger rcaModelCallLedger(
            JdbcClient jdbc, ObjectMapper objectMapper,
            org.springframework.transaction.support.TransactionOperations tx) {
        return new com.objwww.pr.control.infrastructure.persistence.PostgresRcaModelCallLedger(
                jdbc, objectMapper, tx);
    }

    /** R7a-1：RCA 侧网关事件汇（MODEL_* 决策事件 → rca_event，绕开 pr_revision FK 面） */
    @Bean
    public com.objwww.pr.control.domain.service.ExecutionEventRepository rcaModelEventSink(
            com.objwww.pr.control.alert.domain.event.RcaEventAppender rcaEventAppender,
            ObjectMapper objectMapper) {
        return new com.objwww.pr.control.infrastructure.persistence.RcaModelEventSink(
                rcaEventAppender, objectMapper);
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

    // ---------------- AM6 run 级 fallback 栅栏（V33，M6-04 装配；消费方 = RcaRunOrchestrator） ----------------

    @Bean
    public com.objwww.pr.control.alert.domain.repository.RunFallbackRepository runFallbackRepository(
            JdbcClient jdbc) {
        return new com.objwww.pr.control.infrastructure.persistence.PostgresRunFallbackRepository(jdbc);
    }

    @Bean
    public com.objwww.pr.control.alert.domain.repository.ReportWinnerRepository reportWinnerRepository(
            JdbcClient jdbc) {
        return new com.objwww.pr.control.infrastructure.persistence.PostgresReportWinnerRepository(jdbc);
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

    /** EN-01（V60）：发布资产仓储——release_manifest 依赖闭包的解析面 */
    @Bean
    public com.objwww.pr.control.release.domain.repository.ReleaseAssetRepository releaseAssetRepository(
            DataSource dataSource) {
        return new com.objwww.pr.control.infrastructure.persistence.PostgresReleaseAssetRepository(
                dataSource);
    }

    /** EN-02（V61）：发布资格仓储——activate/rollback 资格门的数据面 */
    @Bean
    public com.objwww.pr.control.release.domain.repository.ReleaseQualificationRepository releaseQualificationRepository(
            DataSource dataSource) {
        return new com.objwww.pr.control.infrastructure.persistence.PostgresReleaseQualificationRepository(
                dataSource);
    }

    @Bean
    public com.objwww.pr.control.release.application.ConfigBundleService configBundleService(
            com.objwww.pr.control.release.domain.repository.ConfigBundleRepository repository,
            com.objwww.pr.control.release.domain.repository.ReleaseAssetRepository releaseAssetRepository,
            com.objwww.pr.control.release.domain.repository.ReleaseQualificationRepository releaseQualificationRepository) {
        return new com.objwww.pr.control.release.application.ConfigBundleService(repository,
                releaseAssetRepository, releaseQualificationRepository);
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

    // M6-05 Holmes shadow 持久工作面（V34；SKIP LOCKED 认领 + 租约 CAS，消费方 = HolmesShadowScheduler）
    @Bean
    public com.objwww.pr.control.alert.domain.repository.HolmesShadowWorkRepository holmesShadowWorkRepository(
            JdbcClient jdbc) {
        return new com.objwww.pr.control.infrastructure.persistence.PostgresHolmesShadowWorkRepository(
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

    // ---------------- UI-1 告警只读查询投影（/api/v1/**；HTTP 面 = alert/interfaces IncidentQueryController） ----------------

    /** UX-01：分类写面端口（incident 规则列/override 列 + incident_category_override 审计表） */
    @Bean
    public com.objwww.pr.control.alert.domain.repository.IncidentCategoryRepository incidentCategoryRepository(
            JdbcClient jdbc) {
        return new com.objwww.pr.control.infrastructure.persistence.PostgresIncidentCategoryRepository(jdbc);
    }

    /** UX-01：人工 override 命令服务（审计同事务；HTTP 面 = IncidentCategoryCommandController） */
    @Bean
    public com.objwww.pr.control.alert.application.CategoryOverrideService categoryOverrideService(
            com.objwww.pr.control.alert.domain.repository.IncidentCategoryRepository incidentCategoryRepository,
            org.springframework.transaction.support.TransactionOperations tx) {
        return new com.objwww.pr.control.alert.application.CategoryOverrideService(
                incidentCategoryRepository, tx, java.time.Instant::now);
    }

    @Bean
    public com.objwww.pr.control.alert.domain.repository.IncidentQueryReader incidentQueryReader(
            JdbcClient jdbc, ObjectMapper objectMapper) {
        return new com.objwww.pr.control.infrastructure.persistence.PostgresIncidentQueryReader(
                jdbc, objectMapper);
    }

    /** cases 口径复用 OperatorQueryService、duty/未读复用 DutyStore——总览跨域装配不新造口径 */
    @Bean
    public com.objwww.pr.control.alert.application.IncidentQueryService incidentQueryService(
            com.objwww.pr.control.alert.domain.repository.IncidentQueryReader incidentQueryReader,
            com.objwww.pr.control.ops.duty.domain.DutyStore dutyStore,
            com.objwww.pr.control.ops.application.OperatorQueryService operatorQueryService) {
        return new com.objwww.pr.control.alert.application.IncidentQueryService(
                incidentQueryReader, dutyStore, operatorQueryService, java.time.Instant::now);
    }

    // ---------------- UI-5 评测只读查询投影（/api/eval/**；V45 授权面；HTTP 面 = eval/interfaces EvalQueryController） ----------------

    @Bean
    public com.objwww.pr.control.eval.domain.repository.EvalQueryReader evalQueryReader(
            JdbcClient jdbc) {
        return new com.objwww.pr.control.infrastructure.persistence.PostgresEvalQueryReader(jdbc);
    }

    @Bean
    public com.objwww.pr.control.eval.application.EvalQueryService evalQueryService(
            com.objwww.pr.control.eval.domain.repository.EvalQueryReader evalQueryReader,
            ObjectMapper objectMapper) {
        return new com.objwww.pr.control.eval.application.EvalQueryService(
                evalQueryReader, objectMapper);
    }

    // ---------------- EV-04 评测发起/取消命令面（POST /api/eval/**；V81 授权面——control_app 对 eval_run_command 只增不查改，eval_run 仍零写） ----------------

    @Bean
    public com.objwww.pr.control.eval.domain.repository.EvalRunCommandRepository
            evalRunCommandRepository(JdbcClient jdbc) {
        return new com.objwww.pr.control.infrastructure.persistence
                .PostgresEvalRunCommandRepository(jdbc);
    }

    @Bean
    public com.objwww.pr.control.eval.application.EvalCommandService evalCommandService(
            com.objwww.pr.control.eval.domain.repository.EvalRunCommandRepository
                    evalRunCommandRepository,
            com.objwww.pr.control.eval.domain.repository.EvalQueryReader evalQueryReader,
            ObjectMapper objectMapper) {
        return new com.objwww.pr.control.eval.application.EvalCommandService(
                evalRunCommandRepository, evalQueryReader, objectMapper);
    }

    // ---------------- EV-07 配对工作台（GET /api/eval/compare 读面 + POST /api/eval/comparisons 落档；V85 授权面——control_app 对 eval_comparison 只增读） ----------------

    @Bean
    public com.objwww.pr.control.eval.domain.repository.EvalComparisonRepository
            evalComparisonRepository(JdbcClient jdbc) {
        return new com.objwww.pr.control.infrastructure.persistence
                .PostgresEvalComparisonRepository(jdbc);
    }

    @Bean
    public com.objwww.pr.control.eval.application.EvalCompareService evalCompareService(
            com.objwww.pr.control.eval.domain.repository.EvalQueryReader evalQueryReader,
            com.objwww.pr.control.eval.domain.repository.EvalComparisonRepository
                    evalComparisonRepository,
            ObjectMapper objectMapper) {
        return new com.objwww.pr.control.eval.application.EvalCompareService(
                evalQueryReader, evalComparisonRepository, objectMapper);
    }

    // ---------------- DR-02 故障演练作业链（/api/drills 读写面；V86 授权面——control_app 对 drill_job 只增 + 停止两列，状态机推进零开口） ----------------

    /** 场景模板目录（发布展示 DTO 面；drill-templates.yml 随 jar 封装，GT 零携带） */
    @Bean
    public com.objwww.pr.control.drill.application.DrillTemplateCatalog
            drillTemplateCatalog(
            org.springframework.core.io.ResourceLoader loader,
            @Value("${app.drill.template-path:classpath:drill/drill-templates.yml}")
            String path) throws java.io.IOException {
        try (var in = loader.getResource(path).getInputStream()) {
            return com.objwww.pr.control.drill.application.DrillTemplateCatalog.load(in);
        }
    }

    @Bean
    public com.objwww.pr.control.drill.domain.repository.DrillJobRepository
            drillJobRepository(JdbcClient jdbc) {
        return new com.objwww.pr.control.infrastructure.persistence
                .PostgresDrillJobRepository(jdbc);
    }

    @Bean
    public com.objwww.pr.control.drill.domain.repository.DrillEventRepository
            drillEventRepository(JdbcClient jdbc) {
        return new com.objwww.pr.control.infrastructure.persistence
                .PostgresDrillEventRepository(jdbc);
    }

    @Bean
    public com.objwww.pr.control.drill.application.DrillJobService drillJobService(
            com.objwww.pr.control.drill.domain.repository.DrillJobRepository
                    drillJobRepository,
            com.objwww.pr.control.drill.domain.repository.DrillEventRepository
                    drillEventRepository,
            com.objwww.pr.control.drill.application.DrillTemplateCatalog
                    drillTemplateCatalog,
            ObjectMapper objectMapper,
            @Value("${app.drill.target-envs:arena-195}") String targetEnvs) {
        return new com.objwww.pr.control.drill.application.DrillJobService(
                drillJobRepository, drillEventRepository, drillTemplateCatalog,
                objectMapper,
                java.util.Arrays.stream(targetEnvs.split(","))
                        .map(String::trim).filter(s -> !s.isEmpty()).toList());
    }

    // ---------------- UI-6 监控大盘聚合（/api/agent-ops/**；HTTP 面 = ops/interfaces AgentOpsController） ----------------

    @Bean
    public com.objwww.pr.control.ops.domain.repository.AgentOpsReader agentOpsReader(
            JdbcClient jdbc) {
        return new com.objwww.pr.control.infrastructure.persistence.PostgresAgentOpsReader(jdbc);
    }

    @Bean
    public com.objwww.pr.control.ops.application.AgentOpsSummaryService agentOpsSummaryService(
            com.objwww.pr.control.ops.domain.repository.AgentOpsReader agentOpsReader) {
        return new com.objwww.pr.control.ops.application.AgentOpsSummaryService(
                agentOpsReader, java.time.Instant::now);
    }

    // ---------------- UX-02 值班仿真机器人（/api/v1/duty-bot/**；V83 授权面——control_app 只增读） ----------------

    /** 仿真会话/消息存储（V83 chat_session/chat_message，insert-only） */
    @Bean
    public com.objwww.pr.control.ops.dutybot.domain.DutyBotStore dutyBotStore(
            JdbcClient jdbc, ObjectMapper objectMapper) {
        return new com.objwww.pr.control.infrastructure.persistence.PostgresDutyBotStore(
                jdbc, objectMapper);
    }

    /** 通知 outbox 只读状态面（V9 既有 SELECT 授权，零新授权） */
    @Bean
    public com.objwww.pr.control.ops.dutybot.domain.NotifyStatusReader notifyStatusReader(
            JdbcClient jdbc) {
        return new com.objwww.pr.control.infrastructure.persistence.PostgresNotifyStatusReader(jdbc);
    }

    /** 对话服务：DutyStore（值班快照）+ IncidentQueryReader（告警投影）复用既有口径，不新造 */
    @Bean
    public com.objwww.pr.control.ops.dutybot.application.DutyBotService dutyBotService(
            com.objwww.pr.control.ops.dutybot.domain.DutyBotStore dutyBotStore,
            com.objwww.pr.control.ops.duty.domain.DutyStore dutyStore,
            com.objwww.pr.control.alert.domain.repository.IncidentQueryReader incidentQueryReader,
            com.objwww.pr.control.ops.dutybot.domain.NotifyStatusReader notifyStatusReader,
            org.springframework.transaction.support.TransactionOperations tx,
            @Value("${app.duty-bot.ops-zone:Asia/Shanghai}") String opsZone) {
        return new com.objwww.pr.control.ops.dutybot.application.DutyBotService(
                dutyBotStore, dutyStore, incidentQueryReader, notifyStatusReader, tx,
                java.time.Instant::now, java.time.ZoneId.of(opsZone));
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

    /**
     * EN-04：运行中热更新服务（§227 既有命令账本扩容复用；§229 应用事务在
     * TransactionOperations 内按统一锁序执行）。发布域 bundle/资格仓储来自
     * release 侧既有 bean。
     */
    @Bean
    public com.objwww.pr.control.alert.application.RunConfigSwitchService runConfigSwitchService(
            com.objwww.pr.control.alert.domain.repository.OperatorCommandRepository operatorCommandRepository,
            com.objwww.pr.control.alert.domain.repository.RcaRunRepository rcaRunRepository,
            com.objwww.pr.control.alert.domain.repository.RcaTaskRepository rcaTaskRepository,
            com.objwww.pr.control.alert.domain.repository.RunConfigEpochRepository runConfigEpochRepository,
            com.objwww.pr.control.release.domain.repository.ConfigBundleRepository configBundleRepository,
            com.objwww.pr.control.release.domain.repository.ReleaseQualificationRepository releaseQualificationRepository,
            com.objwww.pr.control.alert.application.agent.AgentRegistry am4AgentRegistry,
            com.objwww.pr.control.alert.domain.agent.RcaModelCallLedger rcaModelCallLedger,
            com.objwww.pr.control.alert.domain.event.RcaEventAppender rcaEventAppender,
            org.springframework.transaction.support.TransactionOperations tx) {
        return new com.objwww.pr.control.alert.application.RunConfigSwitchService(
                operatorCommandRepository, rcaRunRepository, rcaTaskRepository,
                runConfigEpochRepository, configBundleRepository,
                releaseQualificationRepository, am4AgentRegistry, rcaModelCallLedger,
                rcaEventAppender, tx, java.time.Instant::now);
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
