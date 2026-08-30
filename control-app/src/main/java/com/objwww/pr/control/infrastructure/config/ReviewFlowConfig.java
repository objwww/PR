package com.objwww.pr.control.infrastructure.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.objwww.pr.control.application.IntakeService;
import com.objwww.pr.control.application.OutboxWriter;
import com.objwww.pr.control.application.ReviewOrchestrator;
import com.objwww.pr.control.application.ReviewStepExecutor;
import com.objwww.pr.control.application.SnapshotService;
import com.objwww.pr.control.application.StepExecutor;
import com.objwww.pr.control.application.WorkItemWorker;
import com.objwww.pr.control.domain.ai.ModelBudgetGuard;
import com.objwww.pr.control.domain.ai.ModelClient;
import com.objwww.pr.control.domain.port.ArtifactStore;
import com.objwww.pr.control.domain.port.CredentialTokenPort;
import com.objwww.pr.control.domain.port.GitHubSourcePort;
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
import com.objwww.pr.control.domain.service.ExecutionLedger;
import com.objwww.pr.control.domain.service.RevisionService;
import com.objwww.pr.control.domain.service.SequenceAllocator;
import com.objwww.pr.control.domain.snapshot.SafeTarExtractor;
import com.objwww.pr.control.domain.review.FindingMapper;
import com.objwww.pr.control.domain.review.ReviewAgentLoop;
import com.objwww.pr.control.domain.review.ReviewBudget;
import com.objwww.pr.control.domain.tool.PolicyEngine;
import com.objwww.pr.control.domain.tool.ToolRegistry;
import com.objwww.pr.control.infrastructure.cas.LocalCasArtifactStore;
import com.objwww.pr.control.infrastructure.github.GitHubReadAdapter;
import com.objwww.pr.control.infrastructure.github.HttpCredentialTokenPort;
import com.objwww.pr.control.infrastructure.model.SpringAiModelClient;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

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
    public ReviewStepExecutor reviewStepExecutor(ReviewRunRepository runRepository,
                                                 PRRevisionRepository revisionRepository,
                                                 ArtifactStore artifactStore,
                                                 ArtifactRepository artifactRepository,
                                                 SafeTarExtractor extractor,
                                                 ReviewAgentLoop agentLoop,
                                                 ObjectMapper objectMapper) {
        return new ReviewStepExecutor(runRepository, revisionRepository, artifactStore,
                artifactRepository, extractor, agentLoop, ReviewBudget.DEFAULT, objectMapper);
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

    /** intake 异步派发执行器：虚拟线程（每事件一线程，I/O 密集场景零池化心智负担） */
    @Bean
    public Executor intakeExecutor() {
        return Executors.newVirtualThreadPerTaskExecutor();
    }

    @Bean
    public IntakeService intakeService(SnapshotService snapshotService, ReviewOrchestrator orchestrator,
                                       ArtifactStore artifactStore, ArtifactRepository artifactRepository,
                                       Executor intakeExecutor,
                                       @Value("${app.review.policy-version:m0-policy-v1}") String policyVersion,
                                       @Value("${app.review.prompt-version:m0-prompt-v1}") String promptVersion,
                                       @Value("${app.review.toolset-version:m0-toolset-v1}") String toolsetVersion) {
        return new IntakeService(snapshotService, orchestrator, artifactStore, artifactRepository,
                intakeExecutor, policyVersion, promptVersion, toolsetVersion);
    }
}
