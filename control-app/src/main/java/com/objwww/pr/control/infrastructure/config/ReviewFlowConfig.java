package com.objwww.pr.control.infrastructure.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.objwww.pr.control.application.InboxProcessor;
import com.objwww.pr.control.application.IntakeService;
import com.objwww.pr.control.application.OutboxWriter;
import com.objwww.pr.control.application.PrEventAuthoritativeReader;
import com.objwww.pr.control.application.PrStateReconciler;
import com.objwww.pr.control.application.ReviewOrchestrator;
import com.objwww.pr.control.application.ReviewStepExecutor;
import com.objwww.pr.control.application.SnapshotService;
import com.objwww.pr.control.application.StepExecutor;
import com.objwww.pr.control.application.WorkItemWorker;
import com.objwww.pr.control.application.CheckpointWriter;
import com.objwww.pr.control.application.RepairDispatchService;
import com.objwww.pr.control.application.RepairPlanner;
import com.objwww.pr.control.domain.ai.ModelBudgetGuard;
import com.objwww.pr.control.domain.ai.ModelClient;
import com.objwww.pr.control.domain.port.ArtifactStore;
import com.objwww.pr.control.domain.port.CredentialTokenPort;
import com.objwww.pr.control.domain.port.GitHubPrMetadataPort;
import com.objwww.pr.control.domain.port.GitHubSourcePort;
import com.objwww.pr.control.domain.repository.ArtifactRepository;
import com.objwww.pr.control.domain.repository.OutboxCommandRepository;
import com.objwww.pr.control.domain.repository.PRRevisionRepository;
import com.objwww.pr.control.domain.repository.PRSubjectRepository;
import com.objwww.pr.control.domain.repository.ReviewFindingRepository;
import com.objwww.pr.control.domain.repository.RepairRequestRepository;
import com.objwww.pr.control.domain.repository.ReviewRunRepository;
import com.objwww.pr.control.domain.repository.RunStepRepository;
import com.objwww.pr.control.domain.repository.StepAttemptRepository;
import com.objwww.pr.control.domain.repository.StepCheckpointRepository;
import com.objwww.pr.control.domain.repository.WebhookInboxRepository;
import com.objwww.pr.control.domain.repository.WorkItemRepository;
import com.objwww.pr.control.domain.service.ExecutionEventRepository;
import com.objwww.pr.control.domain.service.ExecutionLedger;
import com.objwww.pr.control.domain.service.RevisionService;
import com.objwww.pr.control.domain.service.SequenceAllocator;
import com.objwww.pr.control.domain.service.CheckpointResumeService;
import com.objwww.pr.control.domain.service.RepairCommandFactory;
import com.objwww.pr.control.domain.snapshot.SafeTarExtractor;
import com.objwww.pr.control.domain.review.FindingMapper;
import com.objwww.pr.control.domain.review.ReviewAgentLoop;
import com.objwww.pr.control.domain.review.ReviewBudget;
import com.objwww.pr.control.domain.tool.PolicyEngine;
import com.objwww.pr.control.domain.tool.ToolRegistry;
import com.objwww.pr.control.infrastructure.cas.LocalCasArtifactStore;
import com.objwww.pr.control.infrastructure.github.GitHubPrMetadataAdapter;
import com.objwww.pr.control.infrastructure.github.GitHubReadAdapter;
import com.objwww.pr.control.infrastructure.github.HttpCredentialTokenPort;
import com.objwww.pr.control.infrastructure.model.SpringAiModelClient;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

/**
 * 评审链路（webhook → T0/T1/T2）接线，仅 docker profile（理由同 PersistenceConfig：
 * 无 DataSource 的默认 profile 下 repository 无从装配，整条链路随之不暴露）。
 *
 * <p>domain 服务（RevisionService/ExecutionLedger/FindingMapper/PolicyEngine/ReviewAgentLoop 等）
 * 保持零框架注解，唯一装配点在这里；application 服务（SnapshotService/OutboxWriter/
 * ReviewOrchestrator/IntakeService）同样显式 @Bean 注册，@Transactional 经代理生效。
 */
@Configuration
@Profile("docker")
public class ReviewFlowConfig {

    // ---------- domain 纯服务 ----------

    @Bean
    public RevisionService revisionService() {
        return new RevisionService();
    }

    @Bean
    public ExecutionLedger executionLedger(ExecutionEventRepository executionEventRepository) {
        return new ExecutionLedger(executionEventRepository);
    }

    @Bean
    public SafeTarExtractor safeTarExtractor() {
        return new SafeTarExtractor();
    }

    @Bean
    public FindingMapper findingMapper() {
        return new FindingMapper();
    }

    @Bean
    public ModelBudgetGuard modelBudgetGuard() {
        return new ModelBudgetGuard();
    }

    @Bean
    public ToolRegistry toolRegistry() {
        return new ToolRegistry();
    }

    @Bean
    public PolicyEngine policyEngine(ToolRegistry toolRegistry) {
        return new PolicyEngine(toolRegistry);
    }

    // ---------- 端口适配器 ----------

    @Bean
    public ArtifactStore artifactStore(@Value("${app.artifact.cas-dir:./var/cas}") String casDir) {
        return new LocalCasArtifactStore(Path.of(casDir));
    }

    @Bean
    public CredentialTokenPort credentialTokenPort(
            @Value("${app.github.token-endpoint}") String tokenEndpoint,
            @Value("${app.github.internal-token-secret}") String internalTokenSecret) {
        // 正式路径：Publisher CredentialBroker 只读 token 窄接口（评审修正 #6，T14）。
        // 本地手动联调的 fallback 是 EnvCredentialTokenPort（GITHUB_READONLY_TOKEN），不装配。
        return new HttpCredentialTokenPort(tokenEndpoint, internalTokenSecret);
    }

    @Bean
    public GitHubSourcePort gitHubSourcePort(
            CredentialTokenPort credentialTokenPort,
            @Value("${app.github.api-base:https://api.github.com}") String apiBase) {
        return new GitHubReadAdapter(credentialTokenPort, apiBase);
    }

    /** M1-T05：权威读 port（只读元数据 + sanity 读；token 经同一窄接口，零凭证持有） */
    @Bean
    public GitHubPrMetadataPort gitHubPrMetadataPort(
            CredentialTokenPort credentialTokenPort,
            @Value("${app.github.api-base:https://api.github.com}") String apiBase) {
        return new GitHubPrMetadataAdapter(credentialTokenPort, apiBase);
    }

    @Bean
    public ModelClient modelClient(OpenAiChatModel chatModel, ModelBudgetGuard budgetGuard,
                                   @Value("${spring.ai.openai.chat.options.model}") String model) {
        return new SpringAiModelClient(chatModel, budgetGuard, model);
    }

    // ---------- application 服务 ----------

    @Bean
    public ReviewAgentLoop reviewAgentLoop(ModelClient modelClient, ModelBudgetGuard budgetGuard,
                                           FindingMapper findingMapper, PolicyEngine policyEngine) {
        return new ReviewAgentLoop(modelClient, budgetGuard, findingMapper, policyEngine);
    }

    @Bean
    public CheckpointResumeService checkpointResumeService(StepCheckpointRepository checkpoints,
                                                           ArtifactRepository artifacts,
                                                           ArtifactStore artifactStore,
                                                           ExecutionLedger ledger,
                                                           ObjectMapper objectMapper) {
        return new CheckpointResumeService(checkpoints, artifacts, artifactStore, ledger, objectMapper);
    }

    @Bean
    public CheckpointWriter checkpointWriter(ArtifactRepository artifacts,
                                             StepCheckpointRepository checkpoints,
                                             ExecutionLedger ledger) {
        return new CheckpointWriter(artifacts, checkpoints, ledger);
    }

    @Bean
    public RepairCommandFactory repairCommandFactory(ObjectMapper objectMapper) {
        return new RepairCommandFactory(objectMapper);
    }

    @Bean
    public RepairDispatchService repairDispatchService(RepairRequestRepository requests,
                                                       ReviewRunRepository runs,
                                                       OutboxWriter outboxWriter,
                                                       ExecutionLedger ledger) {
        return new RepairDispatchService(requests, runs, outboxWriter, ledger);
    }

    @Bean(initMethod = "start", destroyMethod = "stop")
    public RepairPlanner repairPlanner(RepairRequestRepository requests, ArtifactStore artifactStore,
                                       RepairCommandFactory factory, RepairDispatchService dispatcher,
                                       @Value("${app.repair.scan-limit:20}") int limit,
                                       @Value("${app.repair.idle-sleep-ms:5000}") long idleSleepMs) {
        return new RepairPlanner(requests, artifactStore, factory, dispatcher, limit, idleSleepMs);
    }

    @Bean
    public ReviewStepExecutor reviewStepExecutor(ReviewRunRepository runRepository,
                                                 PRRevisionRepository revisionRepository,
                                                 ArtifactStore artifactStore,
                                                 ArtifactRepository artifactRepository,
                                                 SafeTarExtractor extractor,
                                                 ReviewAgentLoop agentLoop,
                                                 ObjectMapper objectMapper,
                                                 CheckpointResumeService resumeService,
                                                 CheckpointWriter checkpointWriter,
                                                 ExecutionLedger ledger,
                                                 @Value("${app.review.model-provider:openai-compatible}") String provider,
                                                 @Value("${spring.ai.openai.chat.options.model}") String model,
                                                 @Value("${app.review.model-version:configured}") String modelVersion) {
        return new ReviewStepExecutor(runRepository, revisionRepository, artifactStore,
                artifactRepository, extractor, agentLoop, ReviewBudget.DEFAULT, objectMapper,
                resumeService, checkpointWriter, ledger,
                provider + "/" + model + "/" + modelVersion);
    }

    /** WorkItem Worker（评审修正 #2）：虚拟线程循环，start/stop 由容器生命周期驱动 */
    @Bean(initMethod = "start", destroyMethod = "stop")
    public WorkItemWorker workItemWorker(WorkItemRepository workItems, RunStepRepository steps,
                                         StepAttemptRepository attempts,
                                         List<StepExecutor> executors,
                                         ReviewOrchestrator orchestrator,
                                         @Value("${app.worker.instance-id:control-worker-1}") String workerId,
                                         @Value("${app.worker.max-lease-seconds:600}") int maxLeaseSeconds,
                                         @Value("${app.worker.heartbeat-interval-ms:5000}") long heartbeatIntervalMs,
                                         @Value("${app.worker.idle-sleep-ms:1000}") long idleSleepMs,
                                         @Value("${app.worker.error-sleep-ms:5000}") long errorSleepMs,
                                         @Value("${app.worker.recovery-scan-limit:50}") int recoveryScanLimit) {
        return new WorkItemWorker(workItems, steps, attempts, executors, orchestrator,
                workerId, maxLeaseSeconds, heartbeatIntervalMs,
                idleSleepMs, errorSleepMs, recoveryScanLimit);
    }

    @Bean
    public SnapshotService snapshotService(GitHubSourcePort source, SafeTarExtractor extractor,
                                           ArtifactStore artifactStore,
                                           ArtifactRepository artifactRepository) {
        return new SnapshotService(source, extractor, artifactStore, artifactRepository);
    }

    @Bean
    public OutboxWriter outboxWriter(OutboxCommandRepository outboxRepository,
                                     SequenceAllocator sequenceAllocator,
                                     ArtifactStore artifactStore,
                                     ArtifactRepository artifactRepository) {
        return new OutboxWriter(outboxRepository, sequenceAllocator, artifactStore, artifactRepository);
    }

    @Bean
    public ReviewOrchestrator reviewOrchestrator(PRSubjectRepository subjectRepository,
                                                 PRRevisionRepository revisionRepository,
                                                 ReviewRunRepository runRepository,
                                                 RunStepRepository stepRepository,
                                                 WorkItemRepository workItemRepository,
                                                 StepAttemptRepository attemptRepository,
                                                 ReviewFindingRepository findingRepository,
                                                 RevisionService revisionService,
                                                 ExecutionLedger ledger,
                                                 OutboxWriter outboxWriter,
                                                 ObjectMapper objectMapper) {
        return new ReviewOrchestrator(subjectRepository, revisionRepository, runRepository,
                stepRepository, workItemRepository, attemptRepository, findingRepository,
                revisionService, ledger, outboxWriter, objectMapper);
    }

    @Bean
    public IntakeService intakeService(SnapshotService snapshotService, ReviewOrchestrator orchestrator,
                                       ArtifactStore artifactStore, ArtifactRepository artifactRepository,
                                       @Value("${app.review.policy-version:m0-policy-v1}") String policyVersion,
                                       @Value("${app.review.prompt-version:m0-prompt-v1}") String promptVersion,
                                       @Value("${app.review.toolset-version:m0-toolset-v1}") String toolsetVersion) {
        // M1-T04：IntakeService 不再是异步入口（Executor 已删），由 InboxProcessor 同步驱动
        return new IntakeService(snapshotService, orchestrator, artifactStore, artifactRepository,
                policyVersion, promptVersion, toolsetVersion);
    }

    /**
     * InboxProcessor（M1-T04 worker 段 + T05/T06 真路由，方案 §4.2/§4.3/§4.4）：
     * 零注解 worker，循环经 init/destroy 驱动。
     * workerId 默认 hostname-pid（配置注入优先）；M1 单实例（B-R7：租约字段已为多实例留形）。
     */
    @Bean(initMethod = "start", destroyMethod = "stop")
    public InboxProcessor inboxProcessor(WebhookInboxRepository inboxRepository, IntakeService intakeService,
                                         PrEventAuthoritativeReader prEventAuthoritativeReader,
                                         ReviewOrchestrator reviewOrchestrator,
                                         @Value("${app.review.policy-version:m0-policy-v1}") String policyVersion,
                                         @Value("${app.inbox.worker-id:}") String workerId,
                                         @Value("${app.inbox.lease-ttl-seconds:600}") long leaseTtlSeconds,
                                         @Value("${app.inbox.claim-limit:10}") int claimLimit,
                                         @Value("${app.inbox.backoff-base-seconds:30}") long backoffBaseSeconds,
                                         @Value("${app.inbox.max-attempts:5}") int maxAttempts,
                                         @Value("${app.inbox.idle-sleep-ms:1000}") long idleSleepMs,
                                         @Value("${app.inbox.error-sleep-ms:5000}") long errorSleepMs)
            throws UnknownHostException {
        String id = workerId.isBlank()
                ? InetAddress.getLocalHost().getHostName() + "-" + ProcessHandle.current().pid()
                : workerId;
        return new InboxProcessor(inboxRepository, intakeService, prEventAuthoritativeReader,
                reviewOrchestrator, policyVersion, id,
                Duration.ofSeconds(leaseTtlSeconds), claimLimit,
                Duration.ofSeconds(backoffBaseSeconds), maxAttempts, idleSleepMs, errorSleepMs);
    }

    /** M1-T05：权威读编排（§4.3 判定树，纯决策不写库）；policyVersion 与 IntakeService 同源 */
    @Bean
    public PrEventAuthoritativeReader prEventAuthoritativeReader(
            PRSubjectRepository subjectRepository, PRRevisionRepository revisionRepository,
            ReviewRunRepository runRepository, GitHubPrMetadataPort gitHubPrMetadataPort,
            @Value("${app.review.policy-version:m0-policy-v1}") String policyVersion) {
        return new PrEventAuthoritativeReader(subjectRepository, revisionRepository, runRepository,
                gitHubPrMetadataPort, policyVersion);
    }

    /**
     * PrStateReconciler（M1-T07，方案 §4.5）：公平扫描 + API 预算 + 速率感知退避的
     * 周期对账 worker。零注解，循环经 init/destroy 驱动；单实例（B-R7）。
     */
    @Bean(initMethod = "start", destroyMethod = "stop")
    public PrStateReconciler prStateReconciler(PRSubjectRepository subjectRepository,
                                               PRRevisionRepository revisionRepository,
                                               ReviewRunRepository runRepository,
                                               PrEventAuthoritativeReader prEventAuthoritativeReader,
                                               ReviewOrchestrator reviewOrchestrator,
                                               IntakeService intakeService,
                                               ExecutionLedger executionLedger,
                                               @Value("${app.review.policy-version:m0-policy-v1}") String policyVersion,
                                               @Value("${app.reconcile.pr-state.api-budget-per-round:20}") int apiBudgetPerRound,
                                               @Value("${app.reconcile.pr-state.interval-seconds:1800}") long intervalSeconds,
                                               @Value("${app.reconcile.pr-state.backoff-base-seconds:60}") long backoffBaseSeconds,
                                               @Value("${app.reconcile.pr-state.degraded-threshold:3}") int degradedThreshold,
                                               @Value("${app.reconcile.pr-state.scan-interval-ms:60000}") long scanIntervalMs,
                                               @Value("${app.reconcile.pr-state.error-sleep-ms:30000}") long errorSleepMs) {
        return new PrStateReconciler(subjectRepository, revisionRepository, runRepository,
                prEventAuthoritativeReader, reviewOrchestrator, intakeService, executionLedger,
                policyVersion, apiBudgetPerRound,
                Duration.ofSeconds(intervalSeconds), Duration.ofSeconds(backoffBaseSeconds),
                degradedThreshold, scanIntervalMs, errorSleepMs);
    }
}
