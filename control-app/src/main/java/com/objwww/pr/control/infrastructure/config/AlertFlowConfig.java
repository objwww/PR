package com.objwww.pr.control.infrastructure.config;

import com.objwww.pr.control.alert.application.AlertClock;
import com.objwww.pr.control.alert.application.AlertIntakeLimits;
import com.objwww.pr.control.alert.application.AlertInboxProcessor;
import com.objwww.pr.control.alert.application.AlertIntakeService;
import com.objwww.pr.control.alert.application.ControlAlertRouter;
import com.objwww.pr.control.alert.application.DeterministicSupervisor;
import com.objwww.pr.control.alert.application.IncidentProjector;
import com.objwww.pr.control.alert.application.RcaRunOrchestrator;
import com.objwww.pr.control.alert.application.RcaTaskExecutor;
import com.objwww.pr.control.alert.application.RcaWorker;
import com.objwww.pr.control.alert.application.ReportCompletedNotifier;
import com.objwww.pr.control.alert.application.RunConfigSwitchService;
import com.objwww.pr.control.alert.domain.model.RcaEngine;
import com.objwww.pr.control.alert.domain.repository.AlertEventRepository;
import com.objwww.pr.control.alert.domain.repository.AlertInboxRepository;
import com.objwww.pr.control.alert.domain.repository.ExternalInvocationRepository;
import com.objwww.pr.control.alert.domain.repository.IncidentRepository;
import com.objwww.pr.control.alert.domain.repository.InvestigationResultRepository;
import com.objwww.pr.control.alert.domain.repository.NotifyOutboxRepository;
import com.objwww.pr.control.alert.domain.repository.ReportPublicationRepository;
import com.objwww.pr.control.alert.domain.repository.RcaAttemptRepository;
import com.objwww.pr.control.alert.domain.repository.RcaReportRepository;
import com.objwww.pr.control.alert.domain.repository.RcaRunRepository;
import com.objwww.pr.control.alert.domain.repository.RcaTaskRepository;
import com.objwww.pr.control.alert.domain.repository.RcaToolCallRepository;
import com.objwww.pr.control.alert.domain.repository.SchedulerSlotRepository;
import com.objwww.pr.control.alert.domain.service.AlertIdentityFactory;
import com.objwww.pr.control.alert.domain.service.DeferredPolicy;
import com.objwww.pr.control.alert.domain.service.EvidencePackageValidator;
import com.objwww.pr.control.alert.domain.service.SlaPolicy;
import com.objwww.pr.control.domain.port.ArtifactStore;
import com.objwww.pr.control.infrastructure.cas.LocalCasArtifactStore;
import com.objwww.pr.control.infrastructure.nativeexec.NativeInvestigationExecutor;
import com.objwww.pr.control.infrastructure.observability.AlertMetrics;
import com.objwww.pr.control.release.application.CanaryRouter;
import com.objwww.pr.control.release.domain.repository.CanaryDecisionLogRepository;
import com.objwww.pr.control.release.domain.repository.ConfigBundleRepository;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.SmartLifecycle;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.transaction.support.TransactionOperations;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 告警流装配（docker profile 手工装配；@Profile("docker") 惯例沿 PersistenceConfig）。
 * AlertWebhookController 自带 @RestController+@Profile("docker")（旧线 WebhookController 同款，
 * 组件扫描尊重 profile）；投影器与 inbox 消费循环在此装配（T05）。
 */
@Configuration
@Profile("docker")
public class AlertFlowConfig {

    @Bean
    public AlertIntakeLimits alertIntakeLimits(
            @Value("${app.alert.intake.max-body-bytes:524288}") int maxBodyBytes,
            @Value("${app.alert.intake.max-alerts:200}") int maxAlerts,
            @Value("${app.alert.intake.max-label-chars:2000}") int maxLabelChars,
            @Value("${app.alert.intake.max-total-label-chars:32000}") int maxTotalLabelChars,
            @Value("${app.alert.intake.max-depth:32}") int maxDepth,
            @Value("${app.alert.intake.gzip-max-bytes:2097152}") int gzipMaxBytes) {
        return new AlertIntakeLimits(maxBodyBytes, maxAlerts, maxLabelChars,
                maxTotalLabelChars, maxDepth, gzipMaxBytes);
    }

    @Bean
    public AlertIntakeService alertIntakeService(AlertInboxRepository inbox, AlertIntakeLimits limits,
            @Value("${app.alert.intake.injection-scan.enabled:true}") boolean injectionScanEnabled) {
        // PA-A3（L0-4/L0-5）：注入扫描 fail-safe 默认开启——命中初始态直插 QUARANTINED
        // 隔离区（claim 面不可达），人工复核后放行；关闭须显式配置（登记偏离）
        return new AlertIntakeService(inbox, limits, AlertClock.system(),
                injectionScanEnabled ? new com.objwww.pr.control.alert.application
                        .AlertInjectionScanner() : null);
    }

    /**
     * M5-16 防自噬路由：独立控制面 bearer（compose :? 必填；配置空 = 恒拒 fail-closed）+
     * AM route/monitoring_scope 白名单（逗号分隔；默认 rca-oncall/rca_system）。
     * ROUTED 行直写 alert_inbox PROCESSED+SUPPRESSED（V7 预留枚举），不进投影器。
     */
    @Bean
    public ControlAlertRouter controlAlertRouter(AlertInboxRepository inbox,
                                                 AlertIntakeLimits limits,
                                                 @Value("${app.alert.control-router.bearer:}")
                                                 String controlBearer,
                                                 @Value("${app.alert.control-router.allowed-receivers:rca-oncall}")
                                                 String allowedReceivers,
                                                 @Value("${app.alert.control-router.allowed-scopes:rca_system}")
                                                 String allowedScopes) {
        return new ControlAlertRouter(controlBearer,
                Set.of(allowedReceivers.split(",")), Set.of(allowedScopes.split(",")),
                inbox, AlertClock.system(), limits);
    }

    @Bean
    public AlertIdentityFactory alertIdentityFactory(
            @Value("${app.alert.identity.key-labels:alertname,service,service_name,namespace,job}")
            String keyLabels,
            @Value("${app.alert.identity.dynamic-annotations:current_value,value,observation_value}")
            String dynamicAnnotations) {
        return new AlertIdentityFactory(List.of(keyLabels.split(",")),
                List.of(dynamicAnnotations.split(",")));
    }

    @Bean
    public DeferredPolicy deferredPolicy(
            @Value("${app.alert.defer.backlog-threshold:100}") int backlogThreshold) {
        return new DeferredPolicy(backlogThreshold);
    }

    @Bean
    public SlaPolicy slaPolicy(
            @Value("${app.alert.sla.warning:PT10M}") Duration warningSla,
            @Value("${app.alert.sla.info:PT60M}") Duration infoSla) {
        return new SlaPolicy(warningSla, infoSla);
    }

    @Bean
    public com.objwww.pr.control.alert.domain.classification.IncidentClassifier incidentClassifier() {
        // UX-01：规则表冻结在代码内（Git 审查演进，首期不热更）
        return new com.objwww.pr.control.alert.domain.classification.IncidentClassifier();
    }

    @Bean
    public IncidentProjector incidentProjector(AlertEventRepository events,
                                               IncidentRepository incidents,
                                               RcaRunRepository runs,
                                               RcaTaskRepository tasks,
                                               AlertIdentityFactory identity,
                                               DeferredPolicy deferredPolicy,
                                               SlaPolicy sla,
                                               CanaryRouter canaryRouter,
                                               com.objwww.pr.control.alert.domain.classification.IncidentClassifier incidentClassifier,
                                               com.objwww.pr.control.alert.domain.repository.IncidentCategoryRepository incidentCategoryRepository) {
        return new IncidentProjector(events, incidents, runs, tasks,
                identity, deferredPolicy, sla, AlertClock.system(), canaryRouter,
                incidentClassifier, incidentCategoryRepository);
    }

    @Bean
    public AlertInboxProcessor alertInboxProcessor(AlertInboxRepository inbox,
                                                   IncidentProjector projector,
                                                   TransactionOperations tx,
                                                   @Value("${app.alert.inbox.owner:control-1}") String owner,
                                                   @Value("${app.alert.inbox.lease:PT2M}") Duration lease,
                                                   @Value("${app.alert.defer.backoff:PT30S}") Duration deferBackoff,
                                                   @Value("${app.alert.inbox.error-backoff:PT10S}") Duration errorBackoff,
                                                   @Value("${app.alert.inbox.poll-interval:PT2S}") Duration pollInterval,
                                                   org.springframework.beans.factory.ObjectProvider<
                                                           io.micrometer.tracing.Tracer> tracer) {
        // PA-A6：tracing 面随装配（tracer 缺席 = null 不建 span，诚实降级）
        return new AlertInboxProcessor(inbox, projector, tx, AlertClock.system(), owner,
                lease, deferBackoff, errorBackoff, pollInterval, tracer.getIfAvailable());
    }

    @Bean
    public EvidencePackageValidator evidencePackageValidator(
            @Value("${app.alert.evidence.max-response-bytes:1048576}") int maxResponseBytes,
            @Value("${app.alert.evidence.max-evidence-items:20}") int maxEvidenceItems,
            @Value("${app.alert.evidence.max-field-chars:4000}") int maxFieldChars) {
        // M3-02：schema_version 按包内显式路由（v1/v2），不再由配置指定期望版本。
        // M6-07：键族由 app.alert.holmes.* 更名 app.alert.evidence.*（holmes 专属
        // 配置键随退场回收；验证器是 NATIVE 共享面，语义不变，默认值不变）。
        return new EvidencePackageValidator(maxResponseBytes, maxEvidenceItems, maxFieldChars);
    }

    // ---------------- PB-B2：权威解析 + Scope 快照 + 授权扩张（V115） ----------------

    @Bean
    public com.objwww.pr.control.alert.application.mutation.ResourceResolver
    resourceResolver(org.springframework.jdbc.core.simple.JdbcClient jdbc) {
        return new com.objwww.pr.control.infrastructure.persistence.PostgresResourceResolver(
                jdbc);
    }

    @Bean
    public com.objwww.pr.control.alert.application.mutation.ActionIntentStore
    actionIntentStore(org.springframework.jdbc.core.simple.JdbcClient jdbc,
            org.springframework.transaction.support.TransactionOperations tx) {
        return new com.objwww.pr.control.infrastructure.persistence.PostgresActionIntentStore(
                jdbc, tx);
    }

    @Bean
    public com.objwww.pr.control.alert.application.mutation.ScopeExpansionLedger
    scopeExpansionLedger(org.springframework.jdbc.core.simple.JdbcClient jdbc,
            org.springframework.transaction.support.TransactionOperations tx) {
        return new com.objwww.pr.control.infrastructure.persistence.PostgresScopeExpansionLedger(
                jdbc, tx);
    }

    @Bean
    public com.objwww.pr.control.alert.application.mutation.IntentResourceResolver
    intentResourceResolver(
            com.objwww.pr.control.alert.application.mutation.ResourceResolver resolver,
            com.objwww.pr.control.alert.application.mutation.ActionIntentStore store,
            com.objwww.pr.control.alert.domain.event.RcaEventAppender events,
            org.springframework.transaction.support.TransactionOperations tx,
            @Value("${app.alert.mutation.policy-version:pb-prod-v1}") String policyVersion) {
        return new com.objwww.pr.control.alert.application.mutation.IntentResourceResolver(
                resolver, store, events, tx, policyVersion, java.time.Clock.systemUTC());
    }

    @Bean
    public com.objwww.pr.control.alert.application.mutation.ScopeExpansionService
    scopeExpansionService(
            com.objwww.pr.control.alert.application.mutation.ResourceResolver resolver,
            com.objwww.pr.control.alert.application.mutation.ScopeExpansionLedger ledger,
            com.objwww.pr.control.alert.domain.event.RcaEventAppender events,
            org.springframework.transaction.support.TransactionOperations tx,
            @Value("${app.alert.mutation.policy-version:pb-prod-v1}") String policyVersion) {
        return new com.objwww.pr.control.alert.application.mutation.ScopeExpansionService(
                resolver, ledger, events, tx, policyVersion, java.time.Clock.systemUTC());
    }

    // ---------------- PB-B3：Resource Coordinator（V116，§2.9） ----------------

    @Bean
    public com.objwww.pr.control.alert.application.mutation.ResourceLockStore
    resourceLockStore(org.springframework.jdbc.core.simple.JdbcClient jdbc,
            org.springframework.transaction.support.TransactionOperations tx) {
        return new com.objwww.pr.control.infrastructure.persistence.PostgresResourceLockStore(
                jdbc, tx);
    }

    @Bean
    public com.objwww.pr.control.alert.application.mutation.ResourceCoordinator
    resourceCoordinator(
            com.objwww.pr.control.alert.application.mutation.ResourceLockStore store,
            @Value("${app.alert.mutation.lock-ttl:PT10M}") java.time.Duration lockTtl) {
        return new com.objwww.pr.control.alert.application.mutation.ResourceCoordinator(
                store, lockTtl, java.time.Clock.systemUTC());
    }

    // ---------------- PB-B4：消费模板 + Outbox 派发 + dry-run Runner（V117） ----------------

    @Bean
    public com.objwww.pr.control.alert.application.mutation.OperationLedgerStore
    operationLedgerStore(org.springframework.jdbc.core.simple.JdbcClient jdbc,
            org.springframework.transaction.support.TransactionOperations tx) {
        return new com.objwww.pr.control.infrastructure.persistence.PostgresOperationLedgerStore(
                jdbc, tx);
    }

    @Bean
    public com.objwww.pr.control.alert.application.mutation.OperationOutboxStore
    operationOutboxStore(org.springframework.jdbc.core.simple.JdbcClient jdbc,
            org.springframework.transaction.support.TransactionOperations tx) {
        return new com.objwww.pr.control.infrastructure.persistence.PostgresOperationOutboxStore(
                jdbc, tx);
    }

    @Bean
    public com.objwww.pr.control.alert.application.mutation.OperationPlanner operationPlanner(
            com.objwww.pr.control.alert.application.mutation.ActionIntentStore intents,
            com.objwww.pr.control.alert.application.mutation.OperationLedgerStore operations,
            com.objwww.pr.control.alert.application.mutation.OperationOutboxStore outbox,
            com.objwww.pr.control.alert.application.mutation.ResourceLockStore locks,
            com.objwww.pr.control.alert.application.approval.ApprovalPlannerGate approvalGate,
            com.objwww.pr.control.alert.domain.event.RcaEventAppender events,
            org.springframework.transaction.support.TransactionOperations tx,
            @Value("${app.alert.mutation.lock-ttl:PT10M}") java.time.Duration lockTtl,
            @Value("${app.alert.mutation.dry-run-plan.enabled:false}") boolean planEnabled,
            @Value("${app.alert.mutation.approval.enabled:false}") boolean approvalEnabled,
            @Value("${app.alert.mutation.policy-version:pb-prod-v1}") String policyVersion,
            org.springframework.beans.factory.ObjectProvider<
                    com.objwww.pr.control.alert.application.mutation.UnlockScopeStore>
                    unlockScopes) {
        return new com.objwww.pr.control.alert.application.mutation.OperationPlanner(
                intents, operations, outbox, locks, approvalGate, events, tx, lockTtl,
                planEnabled, approvalEnabled, policyVersion, unlockScopes.getIfAvailable(),
                java.time.Clock.systemUTC());
    }

    @Bean
    public com.objwww.pr.control.alert.application.mutation.DryRunActionRunner
    dryRunActionRunner(@Value("${app.alert.mutation.runner.behavior:SUCCEED}")
            String behavior) {
        return new com.objwww.pr.control.alert.application.mutation.DryRunActionRunner(
                com.objwww.pr.control.alert.application.mutation.DryRunActionRunner.Behavior
                        .valueOf(behavior.trim().toUpperCase()));
    }

    /** PD-D1：真执行 Runner 不设独立 @Bean（endpoint 缺席 = null，@Bean 禁 null）——
     *  在 dispatcher 装配处内联构造；真派发面缺席时 UNKNOWN→ESCALATED 兜底 */
    @Bean
    public com.objwww.pr.control.alert.application.mutation.OperationOutboxDispatcher
    operationOutboxDispatcher(
            com.objwww.pr.control.alert.application.mutation.OperationOutboxStore outbox,
            com.objwww.pr.control.alert.application.mutation.OperationLedgerStore operations,
            com.objwww.pr.control.alert.application.mutation.ResourceLockStore locks,
            com.objwww.pr.control.alert.application.mutation.DryRunActionRunner runner,
            com.objwww.pr.control.alert.domain.event.RcaEventAppender events,
            @Value("${app.alert.inbox.owner:control-1}") String owner,
            @Value("${app.alert.mutation.dispatch-lease:PT1M}") java.time.Duration lease,
            @Value("${app.alert.mutation.dispatch-interval:PT5S}") java.time.Duration interval,
            @Value("${app.alert.mutation.executor.endpoint:}") String realEndpoint) {
        com.objwww.pr.control.alert.application.mutation.ActionRunner realRunner =
                realEndpoint == null || realEndpoint.isBlank() ? null
                        : com.objwww.pr.control.infrastructure.runner.HttpActionRunner.create(
                        realEndpoint.trim(), 5000, 15000);
        return new com.objwww.pr.control.alert.application.mutation.OperationOutboxDispatcher(
                outbox, operations, locks, runner, realRunner, events, owner + "-dispatcher",
                lease, interval, java.time.Clock.systemUTC());
    }

    // ---------------- PB-B5：mutation 对账循环 + reschedule 闸 ----------------

    @Bean
    public com.objwww.pr.control.alert.application.mutation.OperationReconciler
    operationReconciler(
            com.objwww.pr.control.alert.application.mutation.OperationLedgerStore operations,
            com.objwww.pr.control.alert.application.mutation.OperationOutboxStore outbox,
            com.objwww.pr.control.alert.application.mutation.ResourceLockStore locks,
            com.objwww.pr.control.alert.domain.event.RcaEventAppender events,
            @Value("${app.alert.mutation.reconcile-verdict:VERIFIED}") String verdict,
            @Value("${app.alert.mutation.hanging-threshold:PT10M}")
                    java.time.Duration hangingThreshold,
            @Value("${app.alert.mutation.reconcile-interval:PT1M}")
                    java.time.Duration interval) {
        return new com.objwww.pr.control.alert.application.mutation.OperationReconciler(
                operations, outbox, locks, events,
                com.objwww.pr.control.alert.application.mutation.OperationReconciler.Verdict
                        .valueOf(verdict.trim().toUpperCase()),
                hangingThreshold, interval, java.time.Clock.systemUTC());
    }

    @Bean
    public com.objwww.pr.control.alert.application.mutation.MutationActiveGate
    mutationActiveGate(
            com.objwww.pr.control.alert.application.mutation.OperationLedgerStore operations) {
        return new com.objwww.pr.control.alert.application.mutation.MutationActiveGate(
                operations);
    }

    // ---------------- PC-C1：审批四账本服务面（V119） ----------------

    @Bean
    /** 返回具体类型：Spring 按工厂方法签名做注入类型预测，窄接口 ApprovalPlannerGate
     *  的注入依赖该声明（PC-C2 195 启动失败教训——抽象返回类型会藏住实现接口） */
    public com.objwww.pr.control.infrastructure.persistence.PostgresApprovalStore approvalStore(
            org.springframework.jdbc.core.simple.JdbcClient jdbc,
            org.springframework.transaction.support.TransactionOperations tx) {
        return new com.objwww.pr.control.infrastructure.persistence.PostgresApprovalStore(
                jdbc, tx);
    }

    @Bean
    public com.objwww.pr.control.alert.application.approval.ApprovalRequestService
    approvalRequestService(
            com.objwww.pr.control.alert.application.mutation.ActionIntentStore intents,
            com.objwww.pr.control.alert.application.approval.ApprovalStore store,
            com.objwww.pr.control.alert.domain.event.RcaEventAppender events,
            org.springframework.transaction.support.TransactionOperations tx,
            @Value("${app.alert.mutation.policy-version:pb-prod-v1}") String policyVersion) {
        return new com.objwww.pr.control.alert.application.approval.ApprovalRequestService(
                intents, store, events, tx, policyVersion, java.time.Clock.systemUTC());
    }

    @Bean
    public com.objwww.pr.control.alert.application.approval.ApprovalDecisionService
    approvalDecisionService(
            com.objwww.pr.control.alert.application.approval.ApprovalStore store,
            com.objwww.pr.control.alert.domain.event.RcaEventAppender events,
            org.springframework.transaction.support.TransactionOperations tx,
            @Value("${app.alert.approval.grant-ttl:PT10M}") java.time.Duration grantTtl) {
        return new com.objwww.pr.control.alert.application.approval.ApprovalDecisionService(
                store, events, tx, grantTtl, java.time.Clock.systemUTC());
    }

    @Bean
    public com.objwww.pr.control.alert.application.approval.ApprovalSweepLoop
    approvalSweepLoop(
            com.objwww.pr.control.alert.application.approval.ApprovalStore store,
            com.objwww.pr.control.alert.domain.event.RcaEventAppender events,
            @Value("${app.alert.approval.sweep-interval:PT30S}") Duration sweepInterval) {
        return new com.objwww.pr.control.alert.application.approval.ApprovalSweepLoop(
                store, events, sweepInterval, java.time.Clock.systemUTC());
    }

    // ---------------- PC-C3：durable suspension + 双时钟（V120） ----------------

    @Bean
    public com.objwww.pr.control.alert.application.mutation.UnlockScopeStore unlockScopeStore(
            org.springframework.jdbc.core.simple.JdbcClient jdbc,
            org.springframework.transaction.support.TransactionOperations tx) {
        return new com.objwww.pr.control.infrastructure.persistence.PostgresUnlockScopeStore(
                jdbc, tx);
    }

    @Bean
    public com.objwww.pr.control.alert.application.approval.SuspensionStore suspensionStore(
            org.springframework.jdbc.core.simple.JdbcClient jdbc,
            org.springframework.transaction.support.TransactionOperations tx) {
        return new com.objwww.pr.control.infrastructure.persistence.PostgresSuspensionStore(
                jdbc, tx);
    }

    @Bean
    public com.objwww.pr.control.alert.application.approval.ApprovalSuspensionService
    approvalSuspensionService(
            com.objwww.pr.control.alert.application.approval.SuspensionStore store,
            com.objwww.pr.control.alert.domain.event.RcaEventAppender events,
            org.springframework.transaction.support.TransactionOperations tx) {
        return new com.objwww.pr.control.alert.application.approval.ApprovalSuspensionService(
                store, events, tx, java.time.Clock.systemUTC());
    }

    // M6-07 Holmes 退场：holmesClient / holmesInvestigationExecutor 两 bean 已摘除
    // （holmesgpt 容器 + infra/holmes 包同批下线；RcaEngine.HOLMES 枚举保留为历史
    // 读面，C-62）。EvidencePackageValidator 键族更名 app.alert.evidence.*（上节）。

    /** M3-08：STRUCTURE_VALIDATED 即铸 publication(READY) + 每渠道 outbox（候选标记） */
    @Bean
    public ReportCompletedNotifier reportCompletedNotifier(ReportPublicationRepository publications,
                                                           NotifyOutboxRepository outbox,
                                                           @Value("${app.alert.notify.channels:test}") String channels,
                                                           @Value("${app.alert.notify.template-version:am3-candidate-v1}")
                                                           String templateVersion,
                                                           @Value("${app.alert.notify.max-excerpt-chars:280}")
                                                           int maxExcerptChars) {
        return new ReportCompletedNotifier(publications, outbox,
                List.of(channels.split(",")), templateVersion, maxExcerptChars);
    }

    /** M3-07：attempt 原文 CAS 落档（脱敏文本内容寻址；目录可整体迁移） */
    @Bean
    public ArtifactStore artifactStore(
            @Value("${app.artifact.cas-dir:./var/cas}") String casDir) {
        return new LocalCasArtifactStore(Path.of(casDir));
    }

    @Bean
    public RcaRunOrchestrator rcaRunOrchestrator(RcaTaskRepository tasks,
                                                 RcaRunRepository runs,
                                                 RcaAttemptRepository attempts,
                                                 RcaReportRepository reports,
                                                 IncidentRepository incidents,
                                                 SchedulerSlotRepository slots,
                                                 InvestigationResultRepository investigationResults,
                                                 RcaToolCallRepository toolCalls,
                                                 ReportCompletedNotifier notifier,
                                                 ArtifactStore artifacts,
                                                 SlaPolicy sla,
                                                 AlertMetrics alertMetrics,
                                                 CanaryRouter canaryRouter,
                                                 com.objwww.pr.control.alert.domain.repository
                                                         .ReportWinnerRepository winners,
                                                 com.objwww.pr.control.release.application.CanaryEvidenceSampleCollector canaryCollector,
                                                 com.objwww.pr.control.ops.application.OperatorCaseService operatorCaseService,
                                                 @Value("${app.alert.worker.slot-scope:rca}") String slotScope) {
        // M6-07：fallback 与 holmesShadowSampler 参数已随退场摘除（铸造点拆面）
        return new RcaRunOrchestrator(tasks, runs, attempts, reports, incidents,
                slots, investigationResults, toolCalls, notifier, artifacts,
                sla, AlertClock.system(), slotScope, alertMetrics, canaryRouter, winners,
                canaryCollector, operatorCaseService);
    }

    /** B4：canary 采集适配器（NATIVE run 收尾链唯一写入方；LIVE 生产溯源门） */
    @Bean
    public com.objwww.pr.control.release.application.CanaryEvidenceSampleCollector
    canaryEvidenceSampleCollector(
            com.objwww.pr.control.release.domain.repository.CanaryEvidenceSampleRepository
                    canaryEvidenceSampleRepository) {
        return new com.objwww.pr.control.release.application.CanaryEvidenceSampleCollector(
                canaryEvidenceSampleRepository, java.time.Clock.systemUTC());
    }

    /** B4：bundle 派生窗口身份/策略源（capability not ready = 诚实缺席，任务空转） */
    @Bean
    public BundleBackedCanarySources bundleBackedCanarySources(
            com.objwww.pr.control.release.domain.repository.ConfigBundleRepository bundles,
            NativeCapabilityProbe probe) {
        return new BundleBackedCanarySources(bundles, probe);
    }

    /** B4：canary 周期评窗任务（worker 拍内独立容错调用） */
    @Bean
    public com.objwww.pr.control.release.application.CanaryWindowTask canaryWindowTask(
            com.objwww.pr.control.release.domain.repository.CanaryEvidenceSampleRepository
                    canaryEvidenceSampleRepository,
            com.objwww.pr.control.release.domain.repository.CanaryWindowVerdictRepository
                    canaryWindowVerdictRepository,
            BundleBackedCanarySources bundleBackedCanarySources) {
        return new com.objwww.pr.control.release.application.CanaryWindowTask(
                canaryEvidenceSampleRepository, canaryWindowVerdictRepository,
                new com.objwww.pr.control.release.domain.service.CanaryWindowEvaluator(),
                bundleBackedCanarySources,
                (from, to) -> java.util.List.of(), // 对照组：M6-07 Holmes 退场后暂缺 → INCONCLUSIVE 诚实面
                bundleBackedCanarySources, java.time.Clock.systemUTC());
    }

    // M6-07 Holmes 退场：fallbackService（M6-04 HOLMES RERUN 铸造面）、
    // holmesShadowSampler/holmesShadowWorker/holmesShadowScheduler（M6-05 对照期
    // 观察面）四 bean 已摘除。类保留（V33/V34 语义由直构 IT 持续回归），生产
    // docker profile 零 holmes 铸造入口（无写入入口不变量，技术方案 §4.4）。
    // EngineComparisonRecorder bean 回迁 Am4ShadowTriggerConfig（BA-56 上收的
    // 消费者——docker profile 影子 worker——已退役，公共面不再承载）。

    // ---------------- AM5 M5-09/10：发布与切流（release 域路由决策） ----------------

    /**
     * M6-01 NATIVE 执行面能力探针（落点 10；C-67 无热开关）：就绪与 capability
     * digest 均为装配事实纯函数——缺任一 bean/配置即 not ready，路由面
     * NATIVE_DEFERRED；装配变更只经重启生效。
     */
    @Bean
    public NativeCapabilityProbe nativeCapabilityProbe(
            ObjectProvider<ConfigBundleRepository> bundles,
            ObjectProvider<DeterministicSupervisor> supervisor,
            ObjectProvider<RcaTaskRepository> tasks,
            ObjectProvider<RcaRunRepository> runs,
            ObjectProvider<com.objwww.pr.control.alert.domain.evidence.EvidenceRepository> evidence,
            ObjectProvider<com.objwww.pr.control.alert.domain.evidence.EvidenceSnapshotRepository> snapshots,
            ObjectProvider<com.objwww.pr.control.alert.application.agent.MetricsAgent> metricsAgent,
            ObjectProvider<com.objwww.pr.control.alert.application.agent.LogsAgent> logsAgent,
            ObjectProvider<com.objwww.pr.control.alert.application.agent.ChangeAgent> changeAgent,
            ObjectProvider<com.objwww.pr.control.alert.application.agent.NativeRcaAgent> nativeRcaAgent,
            ObjectProvider<com.objwww.pr.control.alert.domain.claim.ClaimStore> claims,
            ObjectProvider<EvidencePackageValidator> validator,
            @Value("${app.alert.native.metrics-expr:}") String metricsExpr,
            @Value("${app.alert.native.tool-registry-digest:}") String toolRegistryDigest) {
        Map<String, Object> components = new java.util.LinkedHashMap<>();
        components.put("configBundleRepository", bundles.getIfAvailable());
        components.put("deterministicSupervisor", supervisor.getIfAvailable());
        components.put("rcaTaskRepository", tasks.getIfAvailable());
        components.put("rcaRunRepository", runs.getIfAvailable());
        components.put("evidenceRepository", evidence.getIfAvailable());
        components.put("evidenceSnapshotRepository", snapshots.getIfAvailable());
        components.put("metricsAgent", metricsAgent.getIfAvailable());
        components.put("logsAgent", logsAgent.getIfAvailable());
        components.put("changeAgent", changeAgent.getIfAvailable());
        components.put("nativeRcaAgent", nativeRcaAgent.getIfAvailable());
        components.put("claimStore", claims.getIfAvailable());
        components.put("validator", validator.getIfAvailable());
        return new NativeCapabilityProbe(components, metricsExpr, toolRegistryDigest);
    }

    /**
     * M6-01 NATIVE 执行器：probe 不就绪 = 部署态 NATIVE_DEFERRED，本 bean 返回
     * null（NullBean，注入面不可得——RcaWorker 映射表随之缺 NATIVE 槽），绝不带病
     * 构造（构造器 blank 校验是第二道栅栏）。
     */
    @Bean
    public NativeInvestigationExecutor nativeInvestigationExecutor(
            ObjectProvider<ConfigBundleRepository> bundles,
            ObjectProvider<DeterministicSupervisor> supervisor,
            ObjectProvider<RcaTaskRepository> tasks,
            ObjectProvider<RcaRunRepository> runs,
            ObjectProvider<com.objwww.pr.control.alert.domain.evidence.EvidenceRepository> evidence,
            ObjectProvider<com.objwww.pr.control.alert.domain.evidence.EvidenceSnapshotRepository> snapshots,
            ObjectProvider<com.objwww.pr.control.alert.application.agent.MetricsAgent> metricsAgent,
            ObjectProvider<com.objwww.pr.control.alert.application.agent.LogsAgent> logsAgent,
            ObjectProvider<com.objwww.pr.control.alert.application.agent.ChangeAgent> changeAgent,
            ObjectProvider<com.objwww.pr.control.alert.application.agent.NativeRcaAgent> nativeRcaAgent,
            ObjectProvider<com.objwww.pr.control.alert.domain.claim.ClaimStore> claims,
            ObjectProvider<EvidencePackageValidator> validator,
            NativeCapabilityProbe probe,
            com.objwww.pr.control.infrastructure.observability.AlertMetrics alertMetrics,
            ObjectProvider<com.objwww.pr.control.alert.application.RunBudgetGate> budgetGate,
            ObjectProvider<Map<com.objwww.pr.control.alert.domain.budget.BudgetKind, Long>> budgetLimits,
            com.objwww.pr.control.alert.domain.tool.RcaToolInvocationLedger toolLedger,
            ObjectProvider<com.objwww.pr.control.alert.domain.repository.TaskExecutionBindingRepository> taskBindings,
            ObjectProvider<com.objwww.pr.control.alert.application.agent.AgentRegistry> agents,
            ObjectProvider<com.objwww.pr.control.alert.domain.repository.PrimaryCheckpointRepository>
                    checkpoints,
            ObjectProvider<com.objwww.pr.control.alert.application.agent.BoundedLlmRoleRunner>
                    boundedLlmRunner,
            ObjectProvider<com.objwww.pr.control.alert.domain.agent.AgentProfile> primaryProfile,
            com.objwww.pr.control.alert.domain.agent.RcaModelCallLedger rcaModelCallLedger,
            ObjectProvider<com.objwww.pr.control.alert.application.agent.DelegationReceiptService>
                    delegationReceiptService,
            org.springframework.beans.factory.ObjectProvider<
                    org.springframework.transaction.support.TransactionOperations> txProvider,
            @Value("${app.alert.native.metrics-expr:}") String metricsExpr,
            @Value("${app.alert.native.tool-registry-digest:}") String toolRegistryDigest) {
        if (!probe.ready()) {
            return null;
        }
        // R7-X2：兼容适配运行器（旧三角色 role→Agent 映射归装配面，执行器不认角色名）
        var metricsInstance = java.util.Objects.requireNonNull(metricsAgent.getIfAvailable(),
                "MetricsAgent 缺件（兼容适配必要件）");
        var logsInstance = java.util.Objects.requireNonNull(logsAgent.getIfAvailable(),
                "LogsAgent 缺件（兼容适配必要件）");
        var changeInstance = java.util.Objects.requireNonNull(changeAgent.getIfAvailable(),
                "ChangeAgent 缺件（兼容适配必要件）");
        var agentRegistry = java.util.Objects.requireNonNull(agents.getIfAvailable(),
                "AgentRegistry 缺件（R7-X2 分派面必要件）");
        var bindingRepository = java.util.Objects.requireNonNull(taskBindings.getIfAvailable(),
                "任务绑定仓储缺件（R7-X1 分派面必要件）");
        Map<String, com.objwww.pr.control.alert.application.agent.SingleToolRoleRunner.RoleQueryHandler>
                handlers = new java.util.LinkedHashMap<>();
        handlers.put("metrics", (ctx, start, end) -> metricsInstance.investigate(ctx,
                new com.objwww.pr.control.alert.application.agent.MetricsAgent.MetricsQuery(
                        metricsExpr, start, end,
                        com.objwww.pr.control.alert.domain.identity.InvestigationInputs.STEP)));
        // BA-117：RoleQueryHandler 契约是纪元秒串，而 logs/change 工具执行器域内只收
        // ISO-8601（B-32 良构面）——纪元秒原样透传会在触网前 INVALID_ARGS，确定性
        // 单工具子任务无模型修 args = 秒死（真窗 qwen/deepseek 四子任务全灭实证）。
        // 裁定为装配面单点转换：工具边界保持单一 ISO 契约（与模型侧 schema 一致），
        // metrics 契约本就是纪元秒，不动。
        handlers.put("logs", (ctx, start, end) -> logsInstance.investigate(ctx,
                new com.objwww.pr.control.alert.application.agent.LogsAgent.LogsQuery(
                        epochSecondsToIso(start), epochSecondsToIso(end))));
        handlers.put("change", (ctx, start, end) -> changeInstance.investigate(ctx,
                new com.objwww.pr.control.alert.application.agent.ChangeAgent.ChangeQuery(
                        epochSecondsToIso(start), epochSecondsToIso(end))));
        // R7-X6：主模式运行器（BOUNDED_LLM）在册时目录双运行器；否则纯兼容面
        var boundedRunner = boundedLlmRunner.getIfAvailable();
        java.util.List<com.objwww.pr.control.alert.application.agent.RoleRunner> runnerList =
                boundedRunner == null
                        ? List.of(new com.objwww.pr.control.alert.application.agent
                                .SingleToolRoleRunner(handlers))
                        : List.of(new com.objwww.pr.control.alert.application.agent
                                        .SingleToolRoleRunner(handlers),
                                boundedRunner);
        com.objwww.pr.control.alert.application.agent.RunnerDirectory runners =
                new com.objwww.pr.control.alert.application.agent.RunnerDirectory(runnerList);
        return new NativeInvestigationExecutor(bundles.getIfAvailable(),
                supervisor.getIfAvailable(), tasks.getIfAvailable(), runs.getIfAvailable(),
                evidence.getIfAvailable(), snapshots.getIfAvailable(),
                nativeRcaAgent.getIfAvailable(),
                claims.getIfAvailable(), validator.getIfAvailable(),
                toolRegistryDigest, AlertClock.system(), alertMetrics,
                java.util.Objects.requireNonNull(budgetGate.getIfAvailable(),
                        "RunBudgetGate 缺件（EX-A1 预算面为 NATIVE 必要件）"),
                java.util.Objects.requireNonNull(budgetLimits.getIfAvailable(),
                        "预算限额面缺件（EX-A1 am4BudgetLimits）"),
                java.util.Objects.requireNonNull(toolLedger,
                        "工具调用账本缺件（EX-A3 恢复 checkpoint 面）"),
                bindingRepository, agentRegistry, runners,
                java.util.Objects.requireNonNull(checkpoints.getIfAvailable(),
                        "主任务检查点仓储缺件（R7-X6 主模式 FINAL 投影面）"),
                rcaModelCallLedger,
                primaryProfile.getIfAvailable(),
                delegationReceiptService.getIfAvailable(),
                txProvider.getIfAvailable());
    }

    /**
     * MC21~23：子任务回执准入（单事务封闭裁决：幂等短路/终态围栏/身份面/限长/结构
     * 契约）。生产方=NativeInvestigationExecutor 委派子任务终态；消费方=ContextAssembler
     * 合并面（当前轮 ACCEPTED 行）。
     */
    @Bean
    public com.objwww.pr.control.alert.application.agent.DelegationReceiptService
    delegationReceiptService(
            com.objwww.pr.control.alert.domain.repository.DelegationReceiptRepository
                    delegationReceiptRepository,
            RcaRunRepository runs,
            RcaTaskRepository tasks,
            com.objwww.pr.control.alert.domain.repository.DelegationDecisionRepository
                    delegationDecisions,
            com.objwww.pr.control.alert.domain.evidence.EvidenceRepository evidenceRepository,
            org.springframework.transaction.support.TransactionOperations tx,
            com.fasterxml.jackson.databind.ObjectMapper objectMapper) {
        // RV04/T22：证据仓入参——support/counter 引用 Host 校验（本 run 证据行成员面）
        return new com.objwww.pr.control.alert.application.agent.DelegationReceiptService(
                delegationReceiptRepository, runs, tasks, delegationDecisions,
                evidenceRepository, tx, AlertClock.system(), objectMapper);
    }

    /** MC31/32：人工补充材料受理（认证端身份由控制器传入；CAS 行锁串行化） */
    @Bean
    public com.objwww.pr.control.alert.application.OperatorMaterialService
    operatorMaterialService(
            com.objwww.pr.control.alert.domain.repository.OperatorMaterialRepository
                    operatorMaterialRepository,
            com.objwww.pr.control.alert.domain.repository.IncidentRepository
                    incidentRepository,
            org.springframework.transaction.support.TransactionOperations tx) {
        return new com.objwww.pr.control.alert.application.OperatorMaterialService(
                operatorMaterialRepository, incidentRepository, tx, AlertClock.system());
    }

    /**
     * BA-117 装配缝转换：RoleQueryHandler 契约的纪元秒串 → 工具执行器域内契约的
     * ISO-8601 Instant 串（logs/change 用；metrics 工具契约即纪元秒，不过本缝）。
     * 非数字 = 上游契约破坏，fail-fast 不静默透传。
     */
    static String epochSecondsToIso(String epochSeconds) {
        try {
            return Instant.ofEpochSecond(Long.parseLong(epochSeconds)).toString();
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(
                    "RoleQueryHandler 窗界契约破坏：非纪元秒串 " + epochSeconds, e);
        }
    }

    /** 状态观察面（C-64）的能力快照：装配时定格，interfaces 不触探针类型（分层缝） */
    @Bean
    public com.objwww.pr.control.release.interfaces.CanaryStatusController.Capability canaryCapability(
            NativeCapabilityProbe probe) {
        return new com.objwww.pr.control.release.interfaces.CanaryStatusController.Capability(
                probe.ready(), probe.missing(),
                probe.ready() ? probe.capabilityDigest().hex() : null);
    }

    /**
     * M5-10 CanaryRouter：nativeReady 由能力探针给出（M6-01 落点 10）——缺件即
     * NATIVE 意愿降级 HOLMES（NATIVE_DEFERRED，立即回退为一等操作）；不新增
     * nativeReady 热开关（C-67：percent 归 bundle、capability 归装配，分责）。
     */
    @Bean
    public CanaryRouter canaryRouter(ConfigBundleRepository configBundleRepository,
                                     CanaryDecisionLogRepository decisionLog,
                                     NativeCapabilityProbe nativeCapabilityProbe) {
        return new CanaryRouter(configBundleRepository, decisionLog,
                nativeCapabilityProbe.ready(), Instant::now);
    }

    /**
     * M6-01 落点 10：引擎执行器映射表<b>显式构造</b>——M6-07 后 NATIVE 槽位由探针
     * 裁决的就绪执行器补位；HOLMES 槽位已随退场摘除（缺执行器 = RcaWorker
     * EXECUTOR_MISSING fail-closed 终态，M6-01 预埋语义）。悬挂宽限不再由 holmes
     * read-timeout 派生（BA-13② 语义保留：宽限必须 &gt; 单次调查最长在途窗）。
     */
    @Bean
    public RcaWorker rcaWorker(RcaTaskRepository tasks,
                               RcaRunRepository runs,
                               RcaAttemptRepository attempts,
                               InvestigationResultRepository investigationResults,
                               IncidentRepository incidents,
                               SchedulerSlotRepository slots,
                               ExternalInvocationRepository invocations,
                               com.objwww.pr.control.alert.domain.tool.RcaToolInvocationLedger toolLedger,
                               ObjectProvider<NativeInvestigationExecutor> nativeExecutor,
                               RcaRunOrchestrator orchestrator,
                               TransactionOperations tx,
                               @Value("${app.alert.worker.owner:control-1}") String owner,
                               @Value("${app.alert.worker.slot-scope:rca}") String slotScope,
                               @Value("${app.alert.worker.task-lease:PT10M}") Duration taskLease,
                               @Value("${app.alert.worker.heartbeat-interval:PT30S}") Duration heartbeatInterval,
                               @Value("${app.alert.worker.poll-interval:PT2S}") Duration pollInterval,
                               // BA-13②:回收退避可配置;悬挂宽限自足默认（原 holmes
                               // read-timeout PT8M+2m 派生面随退场摘除）
                               @Value("${app.alert.worker.retry-backoff:PT1M}") Duration retryBackoff,
                               @Value("${app.alert.worker.hanging-grace:PT10M}") Duration hangingGrace,
                               @Value("${app.alert.worker.investigation-schema-version:2}") int investigationSchemaVersion,
                               RunConfigSwitchService runConfigSwitchService,
                               com.objwww.pr.control.release.application.CanaryWindowTask canaryWindowTask,
                               com.objwww.pr.control.alert.application.ReportFinalizeExecutor
                                       reportFinalizeExecutor,
                               AlertMetrics alertMetrics) {
        Map<RcaEngine, RcaTaskExecutor> executors = new java.util.EnumMap<>(RcaEngine.class);
        NativeInvestigationExecutor nativeExecutorInstance = nativeExecutor.getIfAvailable();
        if (nativeExecutorInstance != null) {
            executors.put(RcaEngine.NATIVE, nativeExecutorInstance);
        }
        return new RcaWorker(tasks, runs, attempts, investigationResults, incidents, slots,
                invocations, toolLedger, executors, reportFinalizeExecutor, orchestrator, tx,
                AlertClock.system(), owner, slotScope, taskLease, heartbeatInterval, pollInterval,
                retryBackoff, hangingGrace, investigationSchemaVersion, runConfigSwitchService,
                canaryWindowTask, alertMetrics);
    }

    /** SR §4.3：报告收尾恢复执行器（只组既有持久材料，不隐式 LLM/重查现场） */
    @Bean
    public com.objwww.pr.control.alert.application.ReportFinalizeExecutor reportFinalizeExecutor(
            com.objwww.pr.control.alert.domain.repository.RcaReportRepository reports,
            InvestigationResultRepository investigationResults,
            com.objwww.pr.control.domain.port.ArtifactStore artifacts) {
        return new com.objwww.pr.control.alert.application.ReportFinalizeExecutor(
                reports, investigationResults, artifacts);
    }

    /**
     * SR §4/§5：Run 停滞对账看门狗——灰度三态（默认 ALERT_ONLY 只告警；空串/缺省
     * 回退 ALERT_ONLY，CL-07 同款装配语义），验证误报后再开 SAFE_RECOVER/AUTO_EXPIRE。
     */
    @Bean
    public com.objwww.pr.control.alert.application.RunReconciler runReconciler(
            RcaRunRepository runs,
            RcaTaskRepository tasks,
            com.objwww.pr.control.alert.domain.repository.RcaReportRepository reports,
            InvestigationResultRepository investigationResults,
            IncidentRepository incidents,
            com.objwww.pr.control.alert.domain.repository.RcaAttemptRepository attempts,
            com.objwww.pr.control.alert.domain.event.RcaEventAppender events,
            TransactionOperations tx,
            SlaPolicy sla,
            com.objwww.pr.control.infrastructure.observability.AlertMetrics alertMetrics,
            @Value("${app.alert.reconcile.mode:}") String modeText,
            @Value("${app.alert.reconcile.poll-interval:PT30S}") Duration pollInterval,
            @Value("${app.alert.reconcile.batch-limit:50}") int batchLimit,
            @Value("${app.alert.reconcile.stuck-threshold:PT5M}") Duration stuckThreshold,
            org.springframework.beans.factory.ObjectProvider<
                    com.objwww.pr.control.alert.application.approval.ApprovalSuspensionService>
                    suspensionService) {
        com.objwww.pr.control.alert.application.RunReconciler.Mode mode =
                modeText == null || modeText.isBlank()
                        ? com.objwww.pr.control.alert.application.RunReconciler.Mode.ALERT_ONLY
                        : com.objwww.pr.control.alert.application.RunReconciler.Mode.valueOf(
                                modeText.trim().toUpperCase());
        // WC-5：观测面随装配接线（扫描时长/失败/决策计数/覆盖与积压 gauge 族）；
        // PA-A1：attempt 进度读面 + LIVE_BUT_STUCK 阈值随装配接线（灰度同 mode——
        // ALERT_ONLY 只分类告警，SAFE_RECOVER 及以上才终止）；
        // PC-C3：审批挂起豁免面（service 缺席 = null 豁免关闭，旧装配零漂移）
        var suspensionSvc = suspensionService.getIfAvailable();
        return new com.objwww.pr.control.alert.application.RunReconciler(
                runs, tasks, reports, investigationResults, incidents, events, tx,
                sla, AlertClock.system(), mode, pollInterval, batchLimit, alertMetrics,
                attempts, stuckThreshold,
                suspensionSvc == null ? null : suspensionSvc::hasActiveSuspension);
    }

    /** M4-05/06：DAG 建边环检测 + READY/BLOCKED 推进器（生产调用方 = M4-25/26 接入） */
    @Bean
    public com.objwww.pr.control.alert.application.DagExecutionService dagExecutionService(
            com.objwww.pr.control.alert.domain.repository.TaskEdgeRepository edges,
            RcaTaskRepository tasks) {
        return new com.objwww.pr.control.alert.application.DagExecutionService(edges, tasks);
    }

    /** EX-A4b（F24）：等待重驱扫描循环——WAITING_CAPABILITY/DEFERRED 事故恢复后补铸 */
    @Bean
    public com.objwww.pr.control.alert.application.IncidentWaitingRedrive incidentWaitingRedrive(
            IncidentRepository incidents,
            RcaRunRepository runs,
            RcaTaskRepository tasks,
            CanaryRouter canaryRouter,
            DeferredPolicy deferredPolicy,
            SlaPolicy sla,
            TransactionOperations tx,
            @Value("${app.alert.redrive.poll-interval:PT30S}") Duration pollInterval) {
        // BA-146：tx 必传——决策行/run 行原子对（V31 deferred FK）只能在事务内成立
        return new com.objwww.pr.control.alert.application.IncidentWaitingRedrive(
                incidents, runs, tasks, canaryRouter, deferredPolicy, sla,
                AlertClock.system(), pollInterval, tx);
    }

    /** PA-A2（V112）：每日验链作业——全 run 哈希链重算比对（R9 口径：assumed DB
     *  write boundary 下的篡改可证）。interval 非正 = 关闭。 */
    @Bean
    public com.objwww.pr.control.alert.application.EventChainVerifyLoop eventChainVerifyLoop(
            com.objwww.pr.control.alert.domain.event.RcaEventAppender events,
            @Value("${app.alert.event-chain.verify-interval:PT24H}") Duration verifyInterval) {
        return new com.objwww.pr.control.alert.application.EventChainVerifyLoop(
                events, verifyInterval);
    }

    /** 消费循环（inbox 投影 + RCA worker + 等待重驱 + Run 对账看门狗 + 每日验链 + 派发循环）
     *  随容器启停（T10 部署启动真执行链；M6-05 holmes shadow 调度循环已随退场摘除）。
     *  PB-B4：OperationOutboxDispatcher 默认关闭（dry-run-plan-enabled=false 时无行可派），
     *  interval=null 即自关，装配始终在册以便演示窗打开。 */
    @Bean
    public SmartLifecycle alertFlowLifecycle(
            AlertInboxProcessor inboxProcessor, RcaWorker rcaWorker,
            com.objwww.pr.control.alert.application.IncidentWaitingRedrive redrive,
            com.objwww.pr.control.alert.application.RunReconciler runReconciler,
            com.objwww.pr.control.alert.application.EventChainVerifyLoop eventChainVerifyLoop,
            com.objwww.pr.control.alert.application.mutation.OperationOutboxDispatcher
                    operationOutboxDispatcher,
            com.objwww.pr.control.alert.application.mutation.OperationReconciler
                    operationReconciler,
            com.objwww.pr.control.alert.application.approval.ApprovalSweepLoop
                    approvalSweepLoop) {
        return new SmartLifecycle() {
            private volatile boolean running;

            @Override
            public void start() {
                inboxProcessor.start();
                rcaWorker.start();
                redrive.start();
                runReconciler.start();
                eventChainVerifyLoop.start();
                operationOutboxDispatcher.start();
                operationReconciler.start();
                approvalSweepLoop.start();
                running = true;
            }

            @Override
            public void stop() {
                running = false;
                approvalSweepLoop.stop();
                operationReconciler.stop();
                operationOutboxDispatcher.stop();
                eventChainVerifyLoop.stop();
                runReconciler.stop();
                redrive.stop();
                rcaWorker.stop();
                inboxProcessor.stop();
            }

            @Override
            public boolean isRunning() {
                return running;
            }
        };
    }
}
