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
import com.objwww.pr.control.infrastructure.holmes.HolmesClient;
import com.objwww.pr.control.infrastructure.holmes.HolmesInvestigationExecutor;
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
    public IncidentProjector incidentProjector(AlertEventRepository events,
                                               IncidentRepository incidents,
                                               RcaRunRepository runs,
                                               RcaTaskRepository tasks,
                                               AlertIdentityFactory identity,
                                               DeferredPolicy deferredPolicy,
                                               SlaPolicy sla,
                                               CanaryRouter canaryRouter) {
        return new IncidentProjector(events, incidents, runs, tasks,
                identity, deferredPolicy, sla, AlertClock.system(), canaryRouter);
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
            @Value("${app.alert.holmes.max-response-bytes:1048576}") int maxResponseBytes,
            @Value("${app.alert.holmes.max-evidence-items:20}") int maxEvidenceItems,
            @Value("${app.alert.holmes.max-field-chars:4000}") int maxFieldChars) {
        // M3-02：schema_version 按包内显式路由（v1/v2），不再由配置指定期望版本
        return new EvidencePackageValidator(maxResponseBytes, maxEvidenceItems, maxFieldChars);
    }

    @Bean
    public HolmesClient holmesClient(
            @Value("${app.alert.holmes.base-url:http://holmes:8080}") String baseUrl,
            @Value("${app.alert.holmes.api-key}") String apiKey,
            @Value("${app.alert.holmes.connect-timeout:PT5S}") Duration connectTimeout,
            @Value("${app.alert.holmes.read-timeout:PT8M}") Duration readTimeout,
            // 与 validator 同键同值：客户端先限读截断（BA-12②），验证链是超限的决策点
            @Value("${app.alert.holmes.max-response-bytes:1048576}") int maxResponseBytes) {
        return new HolmesClient(baseUrl, apiKey, connectTimeout, readTimeout, maxResponseBytes);
    }

    @Bean
    public RcaTaskExecutor holmesInvestigationExecutor(HolmesClient client,
                                                       AlertEventRepository events,
                                                       ExternalInvocationRepository ledger,
                                                       TransactionOperations tx,
                                                       EvidencePackageValidator validator,
                                                       @Value("${app.alert.holmes.model:}") String model,
                                                       @Value("${app.alert.holmes.sampling.temperature:}") String samplingTemperature,
                                                       @Value("${app.alert.holmes.sampling.top-p:}") String samplingTopP,
                                                       @Value("${app.alert.holmes.sampling.max-tokens:}") String samplingMaxTokens,
                                                       @Value("${app.alert.holmes.sampling.seed:}") String samplingSeed,
                                                       // M5-04：采样指纹与 eval_run 头（V10）同源——
                                                       // provider 指纹复用同一配置键，单一事实源
                                                       @Value("${app.alert.eval.provider-fingerprint}") String providerFingerprint,
                                                       @Value("${app.alert.holmes.version:}") String holmesVersion,
                                                       @Value("${app.alert.holmes.max-events:20}") int maxEvents,
                                                       @Value("${app.alert.holmes.heartbeat-interval:PT30S}") Duration heartbeatInterval,
                                                       // M3-08：输出契约升 v2（RESPONSE_FORMAT strict json_schema），
                                                       // 包内显式 schema_version 缺失时按此版本兜底
                                                       @Value("${app.alert.holmes.expected-schema-version:2}") int expectedSchemaVersion,
                                                       AlertMetrics alertMetrics) {
        // M5-04：未配置的采样参数 = null（诚实留空 → 指纹不完整 → 门禁拒绝，INV-AM5-3）
        HolmesInvestigationExecutor.SamplingSpec samplingSpec =
                new HolmesInvestigationExecutor.SamplingSpec(
                        doubleOrNull(samplingTemperature), doubleOrNull(samplingTopP),
                        integerOrNull(samplingMaxTokens), longOrNull(samplingSeed),
                        providerFingerprint);
        return new HolmesInvestigationExecutor(client, events, ledger, tx, validator,
                AlertClock.system(), model, samplingSpec, holmesVersion, maxEvents, heartbeatInterval,
                expectedSchemaVersion, alertMetrics);
    }

    private static Double doubleOrNull(String value) {
        return value == null || value.isBlank() ? null : Double.valueOf(value.trim());
    }

    private static Integer integerOrNull(String value) {
        return value == null || value.isBlank() ? null : Integer.valueOf(value.trim());
    }

    private static Long longOrNull(String value) {
        return value == null || value.isBlank() ? null : Long.valueOf(value.trim());
    }

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
                                                 com.objwww.pr.control.alert.application
                                                         .FallbackService fallback,
                                                 com.objwww.pr.control.alert.domain.repository
                                                         .ReportWinnerRepository winners,
                                                 com.objwww.pr.control.alert.application
                                                         .HolmesShadowSampler holmesShadowSampler,
                                                 @Value("${app.alert.worker.slot-scope:rca}") String slotScope) {
        return new RcaRunOrchestrator(tasks, runs, attempts, reports, incidents,
                slots, investigationResults, toolCalls, notifier, artifacts,
                sla, AlertClock.system(), slotScope, alertMetrics, canaryRouter,
                fallback, winners, holmesShadowSampler);
    }

    /**
     * M6-04 run 级 fallback（V33）：NATIVE run 安全/运行故障恰一次铸 HOLMES RERUN。
     * 开关面 {@code app.alert.fallback.enabled} 是 M6-06/07 退场的 sanctioned 闸；
     * 独立预算 {@code app.alert.fallback.daily-budget}（滚动 24h 窗）与 canary 预算分账。
     */
    @Bean
    public com.objwww.pr.control.alert.application.FallbackService fallbackService(
            RcaRunRepository runs,
            IncidentRepository incidents,
            RcaTaskRepository tasks,
            com.objwww.pr.control.alert.domain.event.RcaEventAppender events,
            com.objwww.pr.control.alert.domain.repository.RunFallbackRepository fallbacks,
            SlaPolicy sla,
            AlertMetrics alertMetrics,
            @Value("${app.alert.fallback.enabled:true}") boolean enabled,
            @Value("${app.alert.fallback.daily-budget:20}") int dailyBudget) {
        return new com.objwww.pr.control.alert.application.FallbackService(runs, incidents,
                tasks, events, fallbacks, sla, AlertClock.system(), alertMetrics, enabled,
                dailyBudget);
    }

    /**
     * M6-05 Holmes 只读对照期（V34 反向影子）：NATIVE SUCCEEDED run 确定性抽样入队
     * 影子工作。{@code app.alert.shadow.holmes.enabled} 缺省关（对既有部署零惊扰），
     * 195 部署面显式开；独立预算与 canary/fallback 预算分账。
     */
    @Bean
    public com.objwww.pr.control.alert.application.HolmesShadowSampler holmesShadowSampler(
            RcaRunRepository runs,
            com.objwww.pr.control.alert.domain.repository.HolmesShadowWorkRepository works,
            AlertMetrics alertMetrics,
            @Value("${app.alert.shadow.holmes.enabled:false}") boolean enabled,
            @Value("${app.alert.shadow.holmes.daily-budget:20}") int dailyBudget,
            @Value("${app.alert.shadow.holmes.sample-rate:100}") int sampleRate,
            @Value("${app.alert.shadow.holmes.max-attempts:3}") int maxAttempts) {
        return new com.objwww.pr.control.alert.application.HolmesShadowSampler(runs, works,
                AlertClock.system(), alertMetrics, enabled, dailyBudget, sampleRate,
                maxAttempts);
    }

    /**
     * BA-56：引擎对照结论记录器上收公共装配面——原挂 Am4ShadowTriggerConfig 只在
     * am4-shadow-trigger profile 存在，M6-05 生产影子 worker 复用后 docker profile
     * 常驻进程启动即缺 bean（195 真启动实证；一次性入口专属装配不能承载生产依赖）。
     */
    @Bean
    public com.objwww.pr.control.release.application.EngineComparisonRecorder engineComparisonRecorder(
            ConfigBundleRepository bundles,
            RcaRunRepository runs,
            RcaReportRepository reports,
            com.objwww.pr.control.alert.domain.claim.ClaimStore claims,
            com.objwww.pr.control.release.domain.repository.EngineComparisonRepository comparisons,
            AlertMetrics alertMetrics) {
        return new com.objwww.pr.control.release.application.EngineComparisonRecorder(
                bundles, runs, reports, claims, comparisons, alertMetrics);
    }

    @Bean
    public com.objwww.pr.control.alert.application.HolmesShadowWorker holmesShadowWorker(
            com.objwww.pr.control.alert.domain.repository.HolmesShadowWorkRepository works,
            RcaRunRepository runs,
            IncidentRepository incidents,
            RcaTaskRepository tasks,
            RcaAttemptRepository attempts,
            RcaTaskExecutor holmesInvestigationExecutor,
            com.objwww.pr.control.release.domain.repository.EngineComparisonRepository comparisons,
            com.objwww.pr.control.release.application.EngineComparisonRecorder recorder,
            SlaPolicy sla,
            AlertMetrics alertMetrics,
            @Value("${app.alert.worker.owner:control-1}") String owner) {
        return new com.objwww.pr.control.alert.application.HolmesShadowWorker(works, runs,
                incidents, tasks, attempts, holmesInvestigationExecutor, comparisons,
                recorder, sla, AlertClock.system(), alertMetrics, owner + "-holmes-shadow");
    }

    @Bean
    public com.objwww.pr.control.alert.application.HolmesShadowScheduler holmesShadowScheduler(
            com.objwww.pr.control.alert.domain.repository.HolmesShadowWorkRepository works,
            com.objwww.pr.control.alert.application.HolmesShadowWorker worker,
            @Value("${app.alert.shadow.holmes.lease:PT15M}") java.time.Duration lease,
            @Value("${app.alert.shadow.holmes.poll-interval:PT30S}") java.time.Duration pollInterval,
            @Value("${app.alert.shadow.holmes.batch-size:2}") int batchSize) {
        return new com.objwww.pr.control.alert.application.HolmesShadowScheduler(works,
                worker, AlertClock.system(), "control-1-holmes-shadow", lease,
                pollInterval, batchSize);
    }

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
                metricsExpr, toolRegistryDigest, AlertClock.system(), alertMetrics);
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
     * M6-01 落点 10：引擎执行器映射表<b>显式构造</b>（不再依赖 Spring 按类型聚装）——
     * HOLMES 恒在；NATIVE 槽位由探针裁决的就绪执行器补位。参数名对齐 bean 名消歧
     * （RcaTaskExecutor 现有两个实现）。
     */
    @Bean
    public RcaWorker rcaWorker(RcaTaskRepository tasks,
                               RcaRunRepository runs,
                               RcaAttemptRepository attempts,
                               InvestigationResultRepository investigationResults,
                               IncidentRepository incidents,
                               SchedulerSlotRepository slots,
                               ExternalInvocationRepository invocations,
                               RcaTaskExecutor holmesInvestigationExecutor,
                               ObjectProvider<NativeInvestigationExecutor> nativeExecutor,
                               RcaRunOrchestrator orchestrator,
                               TransactionOperations tx,
                               @Value("${app.alert.worker.owner:control-1}") String owner,
                               @Value("${app.alert.worker.slot-scope:rca}") String slotScope,
                               @Value("${app.alert.worker.task-lease:PT10M}") Duration taskLease,
                               @Value("${app.alert.worker.heartbeat-interval:PT30S}") Duration heartbeatInterval,
                               @Value("${app.alert.worker.poll-interval:PT2S}") Duration pollInterval,
                               // BA-13②:回收退避可配置;悬挂宽限由 holmes read-timeout 派生
                               // (宽限必须 > 单次调查最长在途窗,否则会把真在跑的调用误标 UNKNOWN)
                               @Value("${app.alert.worker.retry-backoff:PT1M}") Duration retryBackoff,
                               @Value("${app.alert.holmes.read-timeout:PT8M}") Duration holmesReadTimeout,
                               @Value("${app.alert.worker.hanging-grace:}") String hangingGraceOverride,
                               @Value("${app.alert.holmes.expected-schema-version:2}") int investigationSchemaVersion) {
        Duration hangingGrace = hangingGraceOverride == null || hangingGraceOverride.isBlank()
                ? holmesReadTimeout.plus(Duration.ofMinutes(2))
                : Duration.parse(hangingGraceOverride);
        Map<RcaEngine, RcaTaskExecutor> executors = new java.util.EnumMap<>(RcaEngine.class);
        executors.put(RcaEngine.HOLMES, holmesInvestigationExecutor);
        NativeInvestigationExecutor nativeExecutorInstance = nativeExecutor.getIfAvailable();
        if (nativeExecutorInstance != null) {
            executors.put(RcaEngine.NATIVE, nativeExecutorInstance);
        }
        return new RcaWorker(tasks, runs, attempts, investigationResults, incidents, slots,
                invocations, executors, orchestrator, tx, AlertClock.system(), owner, slotScope,
                taskLease, heartbeatInterval, pollInterval, retryBackoff, hangingGrace,
                investigationSchemaVersion);
    }

    /** M4-05/06：DAG 建边环检测 + READY/BLOCKED 推进器（生产调用方 = M4-25/26 接入） */
    @Bean
    public com.objwww.pr.control.alert.application.DagExecutionService dagExecutionService(
            com.objwww.pr.control.alert.domain.repository.TaskEdgeRepository edges,
            RcaTaskRepository tasks) {
        return new com.objwww.pr.control.alert.application.DagExecutionService(edges, tasks);
    }

    /** 消费循环（inbox 投影 + RCA worker + M6-05 holmes shadow）随容器启停（T10 部署启动真执行链） */
    @Bean
    public SmartLifecycle alertFlowLifecycle(AlertInboxProcessor inboxProcessor, RcaWorker rcaWorker,
            com.objwww.pr.control.alert.application.HolmesShadowScheduler holmesShadowScheduler) {
        return new SmartLifecycle() {
            private volatile boolean running;

            @Override
            public void start() {
                inboxProcessor.start();
                rcaWorker.start();
                holmesShadowScheduler.start();
                running = true;
            }

            @Override
            public void stop() {
                running = false;
                holmesShadowScheduler.stop();
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
