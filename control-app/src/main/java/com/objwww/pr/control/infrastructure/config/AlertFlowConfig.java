package com.objwww.pr.control.infrastructure.config;

import com.objwww.pr.control.alert.application.AlertClock;
import com.objwww.pr.control.alert.application.AlertIntakeLimits;
import com.objwww.pr.control.alert.application.AlertInboxProcessor;
import com.objwww.pr.control.alert.application.AlertIntakeService;
import com.objwww.pr.control.alert.application.IncidentProjector;
import com.objwww.pr.control.alert.application.RcaRunOrchestrator;
import com.objwww.pr.control.alert.application.RcaTaskExecutor;
import com.objwww.pr.control.alert.application.RcaWorker;
import com.objwww.pr.control.alert.application.ReportCompletedNotifier;
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
import com.objwww.pr.control.infrastructure.observability.AlertMetrics;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.SmartLifecycle;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.transaction.support.TransactionOperations;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

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
                                               SlaPolicy sla) {
        return new IncidentProjector(events, incidents, runs, tasks,
                identity, deferredPolicy, sla, AlertClock.system());
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
                                                       @Value("${app.alert.holmes.version:}") String holmesVersion,
                                                       @Value("${app.alert.holmes.max-events:20}") int maxEvents,
                                                       @Value("${app.alert.holmes.heartbeat-interval:PT30S}") Duration heartbeatInterval,
                                                       // M3-08：输出契约升 v2（RESPONSE_FORMAT strict json_schema），
                                                       // 包内显式 schema_version 缺失时按此版本兜底
                                                       @Value("${app.alert.holmes.expected-schema-version:2}") int expectedSchemaVersion,
                                                       AlertMetrics alertMetrics) {
        return new HolmesInvestigationExecutor(client, events, ledger, tx, validator,
                AlertClock.system(), model, holmesVersion, maxEvents, heartbeatInterval,
                expectedSchemaVersion, alertMetrics);
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
                                                 @Value("${app.alert.worker.slot-scope:rca}") String slotScope) {
        return new RcaRunOrchestrator(tasks, runs, attempts, reports, incidents,
                slots, investigationResults, toolCalls, notifier, artifacts,
                sla, AlertClock.system(), slotScope, alertMetrics);
    }

    @Bean
    public RcaWorker rcaWorker(RcaTaskRepository tasks,
                               RcaRunRepository runs,
                               RcaAttemptRepository attempts,
                               InvestigationResultRepository investigationResults,
                               IncidentRepository incidents,
                               SchedulerSlotRepository slots,
                               ExternalInvocationRepository invocations,
                               RcaTaskExecutor executor,
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
        return new RcaWorker(tasks, runs, attempts, investigationResults, incidents, slots,
                invocations, executor, orchestrator, tx, AlertClock.system(), owner, slotScope,
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

    /** 两个消费循环（inbox 投影 + RCA worker）随容器启停（T10 部署启动真执行链） */
    @Bean
    public SmartLifecycle alertFlowLifecycle(AlertInboxProcessor inboxProcessor, RcaWorker rcaWorker) {
        return new SmartLifecycle() {
            private volatile boolean running;

            @Override
            public void start() {
                inboxProcessor.start();
                rcaWorker.start();
                running = true;
            }

            @Override
            public void stop() {
                running = false;
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
