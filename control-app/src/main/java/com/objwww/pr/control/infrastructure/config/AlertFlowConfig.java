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
    public AlertIntakeService alertIntakeService(AlertInboxRepository inbox, AlertIntakeLimits limits) {
        return new AlertIntakeService(inbox, limits, AlertClock.system());
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
                                                   @Value("${app.alert.inbox.poll-interval:PT2S}") Duration pollInterval) {
        return new AlertInboxProcessor(inbox, projector, tx, AlertClock.system(), owner,
                lease, deferBackoff, errorBackoff, pollInterval);
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
                                                 @Value("${app.alert.worker.slot-scope:rca}") String slotScope) {
        // M6-07：fallback 与 holmesShadowSampler 参数已随退场摘除（铸造点拆面）
        return new RcaRunOrchestrator(tasks, runs, attempts, reports, incidents,
                slots, investigationResults, toolCalls, notifier, artifacts,
                sla, AlertClock.system(), slotScope, alertMetrics, canaryRouter, winners);
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
            @Value("${app.alert.native.metrics-expr:}") String metricsExpr,
            @Value("${app.alert.native.tool-registry-digest:}") String toolRegistryDigest) {
        if (!probe.ready()) {
            return null;
        }
        return new NativeInvestigationExecutor(bundles.getIfAvailable(),
                supervisor.getIfAvailable(), tasks.getIfAvailable(), runs.getIfAvailable(),
                evidence.getIfAvailable(), snapshots.getIfAvailable(),
                metricsAgent.getIfAvailable(), logsAgent.getIfAvailable(),
                changeAgent.getIfAvailable(), nativeRcaAgent.getIfAvailable(),
                claims.getIfAvailable(), validator.getIfAvailable(),
                metricsExpr, toolRegistryDigest, AlertClock.system(), alertMetrics,
                java.util.Objects.requireNonNull(budgetGate.getIfAvailable(),
                        "RunBudgetGate 缺件（EX-A1 预算面为 NATIVE 必要件）"),
                java.util.Objects.requireNonNull(budgetLimits.getIfAvailable(),
                        "预算限额面缺件（EX-A1 am4BudgetLimits）"),
                java.util.Objects.requireNonNull(toolLedger,
                        "工具调用账本缺件（EX-A3 恢复 checkpoint 面）"));
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
                               @Value("${app.alert.worker.investigation-schema-version:2}") int investigationSchemaVersion) {
        Map<RcaEngine, RcaTaskExecutor> executors = new java.util.EnumMap<>(RcaEngine.class);
        NativeInvestigationExecutor nativeExecutorInstance = nativeExecutor.getIfAvailable();
        if (nativeExecutorInstance != null) {
            executors.put(RcaEngine.NATIVE, nativeExecutorInstance);
        }
        return new RcaWorker(tasks, runs, attempts, investigationResults, incidents, slots,
                invocations, toolLedger, executors, orchestrator, tx, AlertClock.system(),
                owner, slotScope, taskLease, heartbeatInterval, pollInterval, retryBackoff,
                hangingGrace, investigationSchemaVersion);
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
            @Value("${app.alert.redrive.poll-interval:PT30S}") Duration pollInterval) {
        return new com.objwww.pr.control.alert.application.IncidentWaitingRedrive(
                incidents, runs, tasks, canaryRouter, deferredPolicy, sla,
                AlertClock.system(), pollInterval);
    }

    /** 消费循环（inbox 投影 + RCA worker + 等待重驱）随容器启停（T10 部署启动真执行链；
     *  M6-05 holmes shadow 调度循环已随退场摘除） */
    @Bean
    public SmartLifecycle alertFlowLifecycle(
            AlertInboxProcessor inboxProcessor, RcaWorker rcaWorker,
            com.objwww.pr.control.alert.application.IncidentWaitingRedrive redrive) {
        return new SmartLifecycle() {
            private volatile boolean running;

            @Override
            public void start() {
                inboxProcessor.start();
                rcaWorker.start();
                redrive.start();
                running = true;
            }

            @Override
            public void stop() {
                running = false;
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
