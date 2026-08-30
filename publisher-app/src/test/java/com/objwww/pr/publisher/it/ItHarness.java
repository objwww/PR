package com.objwww.pr.publisher.it;

import com.objwww.pr.control.application.IntakeCommand;
import com.objwww.pr.control.application.IntakeService;
import com.objwww.pr.control.application.OutboxWriter;
import com.objwww.pr.control.application.PublicationRequest;
import com.objwww.pr.control.application.ReviewOrchestrator;
import com.objwww.pr.control.application.ReviewStepExecutor;
import com.objwww.pr.control.application.SnapshotService;
import com.objwww.pr.control.application.StepCompletion;
import com.objwww.pr.control.application.StepExecutor;
import com.objwww.pr.control.application.StepOutcome;
import com.objwww.pr.control.application.T2Outcome;
import com.objwww.pr.control.application.WorkItemWorker;
import com.objwww.pr.control.domain.ai.ModelBudgetGuard;
import com.objwww.pr.control.domain.model.PrSubjectState;
import com.objwww.pr.control.domain.model.ReviewRun;
import com.objwww.pr.control.domain.model.RunStep;
import com.objwww.pr.control.domain.model.StepAttempt;
import com.objwww.pr.control.domain.model.WorkItem;
import com.objwww.pr.control.domain.port.ArtifactStore;
import com.objwww.pr.control.domain.repository.ArtifactRepository;
import com.objwww.pr.control.domain.repository.PRRevisionRepository;
import com.objwww.pr.control.domain.repository.PRSubjectRepository;
import com.objwww.pr.control.domain.repository.ReviewFindingRepository;
import com.objwww.pr.control.domain.repository.ReviewRunRepository;
import com.objwww.pr.control.domain.repository.RunStepRepository;
import com.objwww.pr.control.domain.repository.StepAttemptRepository;
import com.objwww.pr.control.domain.repository.WorkItemRepository;
import com.objwww.pr.control.domain.review.FindingMapper;
import com.objwww.pr.control.domain.review.ReviewAgentLoop;
import com.objwww.pr.control.domain.review.ReviewBudget;
import com.objwww.pr.control.domain.service.ExecutionLedger;
import com.objwww.pr.control.domain.service.Projector;
import com.objwww.pr.control.domain.service.RevisionService;
import com.objwww.pr.control.domain.service.RunProjection;
import com.objwww.pr.control.domain.snapshot.SafeTarExtractor;
import com.objwww.pr.control.domain.tool.PolicyEngine;
import com.objwww.pr.control.domain.tool.ToolRegistry;
import com.objwww.pr.control.infrastructure.cas.LocalCasArtifactStore;
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
import com.objwww.pr.control.interfaces.webhook.PullRequestEvent;
import com.objwww.pr.publisher.application.OutboxClaimer;
import com.objwww.pr.publisher.application.OutboxRecoveryScanner;
import com.objwww.pr.publisher.domain.handler.CreateCheckHandler;
import com.objwww.pr.publisher.domain.handler.PublicationHandler;
import com.objwww.pr.publisher.domain.handler.PublishReviewHandler;
import com.objwww.pr.publisher.domain.handler.UpdateCheckHandler;
import com.objwww.pr.publisher.domain.port.ExecutionEventAppender;
import com.objwww.pr.publisher.domain.port.PublicationStore;
import com.objwww.pr.publisher.domain.service.FencedPublicationExecutor;
import com.objwww.pr.publisher.infrastructure.credential.CredentialBroker;
import com.objwww.pr.publisher.infrastructure.credential.TokenScope;
import com.objwww.pr.publisher.infrastructure.github.GitHubWriteAdapter;
import com.objwww.pr.publisher.infrastructure.persistence.CasPayloadReader;
import com.objwww.pr.publisher.infrastructure.persistence.PostgresExecutionEventAppender;
import com.objwww.pr.publisher.infrastructure.persistence.PostgresPublicationStore;
import com.objwww.pr.shared.CommandType;
import com.objwww.pr.shared.Digest;
import com.objwww.pr.shared.ExecutionEvent;
import com.objwww.pr.shared.OperationId;
import org.springframework.aop.Pointcut;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.aop.support.DefaultPointcutAdvisor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * L3/L4 双进程单 JVM 线束（M0-T17）：同一 PG 上同时装配 Control 侧全套真实组件
 * （control_app 角色 DataSource）与 Publisher 侧全套真实组件（publisher_app 角色
 * DataSource + 真 GitHubWriteAdapter 打 WireMock），驱动一律用 runOnce() 确定性单轮，
 * 不起后台线程。
 *
 * <p>事务语义与生产一致：ReviewOrchestrator 的 @Transactional 经
 * TransactionInterceptor + CGLIB 代理生效（等价 docker profile 的 Spring 装配），
 * Worker/T2 内部仍走同一代理，整笔 T1/T2 要么全落要么全回滚（EX-07 靠它）。
 *
 * <p>PollRequestEvent → IntakeService 用直连 Executor（accept 即同步 dispatch）。
 */
final class ItHarness {

    static final long INSTALLATION_ID = 77L;
    static final String BASE_REF = "main";
    static final String BASE_SHA = "base0" + "0".repeat(35);
    static final String POLICY = "policy-v1";
    static final String PROMPT = "prompt-v1";
    static final String TOOLSET = "toolset-v1";

    private final Path casDir;
    private final String githubApiBase;

    // ---- 共享件
    final ItModelClient modelClient = new ItModelClient();
    final FakeGitHubSourcePort sourcePort = new FakeGitHubSourcePort();
    final ArtifactStore casStore;
    final CasPayloadReader payloadReader;

    // ---- Control 侧
    final PRSubjectRepository subjectRepo;
    final PostgresPRRevisionRepository revisionRepo;
    final ReviewRunRepository runRepo;
    final RunStepRepository stepRepo;
    final WorkItemRepository workItemRepo;
    final StepAttemptRepository attemptRepo;
    final PostgresArtifactRepository artifactRepo;
    final ReviewFindingRepository findingRepo;
    final PostgresExecutionEventRepository eventRepo;
    final PostgresSequenceAllocator sequenceAllocator;
    private final RevisionService revisionService = new RevisionService();
    private final ExecutionLedger ledger;
    private final OutboxWriter outboxWriter;
    private ReviewOrchestrator orchestratorProxy;
    private ArtifactRepository artifactRepoInUse;
    final IntakeService intakeService;
    final SnapshotService snapshotService;
    private final SafeTarExtractor extractor = new SafeTarExtractor();
    private final ReviewAgentLoop agentLoop;
    private ReviewBudget budget = ReviewBudget.DEFAULT;

    // ---- Publisher 侧
    final PostgresPublicationStore postgresStore;
    private PublicationStore storeOverride;
    private final List<PublicationHandler> handlers = List.of(
            new CreateCheckHandler(), new PublishReviewHandler(), new UpdateCheckHandler());
    private final Duration reconcileRetryDelay = Duration.ofMillis(10);
    private final int probeMaxPages = 2;
    private final Duration unknownRetryDelay = Duration.ofMillis(10);
    private final int maxReconcileNotFound = 2;

    ItHarness(Path casDir, String githubApiBase) {
        this.casDir = casDir;
        this.githubApiBase = githubApiBase;

        JdbcClient control = PostgresITBase.controlJdbc;
        JdbcClient publisher = PostgresITBase.publisherJdbc;

        // ---- Control 装配（手工等价 ReviewFlowConfig）
        subjectRepo = new PostgresPRSubjectRepository(control);
        revisionRepo = new PostgresPRRevisionRepository(control);
        runRepo = new PostgresReviewRunRepository(control);
        stepRepo = new PostgresRunStepRepository(control);
        workItemRepo = new PostgresWorkItemRepository(control);
        attemptRepo = new PostgresStepAttemptRepository(control);
        artifactRepo = new PostgresArtifactRepository(control);
        artifactRepoInUse = artifactRepo;
        findingRepo = new PostgresReviewFindingRepository(control);
        eventRepo = new PostgresExecutionEventRepository(control, PostgresITBase.OM);
        sequenceAllocator = new PostgresSequenceAllocator(control);
        casStore = new LocalCasArtifactStore(casDir);
        ledger = new ExecutionLedger(eventRepo);
        outboxWriter = new OutboxWriter(new PostgresOutboxCommandRepository(control),
                sequenceAllocator, casStore, artifactRepo);
        orchestratorProxy = transactionalProxy(buildOrchestrator());
        snapshotService = new SnapshotService(sourcePort, extractor, casStore, artifactRepo);
        agentLoop = new ReviewAgentLoop(modelClient, new ModelBudgetGuard(),
                new FindingMapper(), new PolicyEngine(new ToolRegistry()));
        intakeService = new IntakeService(snapshotService, orchestratorProxy, casStore, artifactRepo,
                Runnable::run, POLICY, PROMPT, TOOLSET);

        // ---- Publisher 装配（手工等价 PublisherWiringConfig）
        ExecutionEventAppender appender = new PostgresExecutionEventAppender(publisher, PostgresITBase.OM);
        postgresStore = new PostgresPublicationStore(publisher, PostgresITBase.publisherTx, appender);
        payloadReader = new CasPayloadReader(casDir, PostgresITBase.OM);
    }

    // ------------------------------------------------------------------ Control 驱动

    private ReviewOrchestrator buildOrchestrator() {
        return new ReviewOrchestrator(subjectRepo, revisionRepo, runRepo, stepRepo,
                workItemRepo, attemptRepo, findingRepo, revisionService, ledger, outboxWriter,
                PostgresITBase.OM);
    }

    /** @Transactional 代理：与生产 docker profile 的 Spring AOP 语义一致 */
    private ReviewOrchestrator transactionalProxy(ReviewOrchestrator target) {
        TransactionInterceptor interceptor = new TransactionInterceptor(
                new org.springframework.jdbc.datasource.DataSourceTransactionManager(
                        PostgresITBase.controlDataSource()),
                new AnnotationTransactionAttributeSource());
        ProxyFactory factory = new ProxyFactory(target);
        factory.addAdvisor(new DefaultPointcutAdvisor(Pointcut.TRUE, interceptor));
        return (ReviewOrchestrator) factory.getProxy();
    }

    /** EX-07 用：换掉 artifact 登记实现（T2 中途炸）后重建编排代理 */
    void swapArtifactRepository(ArtifactRepository replacement) {
        this.artifactRepoInUse = replacement;
        // outboxWriter 持有 artifactRepo 引用，需一并重建
        OutboxWriter writer = new OutboxWriter(new PostgresOutboxCommandRepository(
                PostgresITBase.controlJdbc), sequenceAllocator, casStore, replacement);
        orchestratorProxy = transactionalProxy(new ReviewOrchestrator(subjectRepo, revisionRepo,
                runRepo, stepRepo, workItemRepo, attemptRepo, findingRepo, revisionService,
                ledger, writer, PostgresITBase.OM));
    }

    void restoreArtifactRepository() {
        swapArtifactRepository(artifactRepo);
        artifactRepoInUse = artifactRepo;
    }

    ReviewOrchestrator orchestrator() {
        return orchestratorProxy;
    }

    /** 新建一个 worker（workerId 可变，模拟进程重启换身份） */
    WorkItemWorker newWorker(String workerId) {
        StepExecutor reviewExecutor = new ReviewStepExecutor(runRepo, revisionRepo, casStore,
                artifactRepo, extractor, agentLoop, budget, PostgresITBase.OM);
        // 心跳间隔拉大：确定性测试不依赖心跳线程；租约时长 60s
        return new WorkItemWorker(workItemRepo, stepRepo, attemptRepo, List.of(reviewExecutor),
                orchestratorProxy, workerId, 60, 60_000, 0, 0, 50);
    }

    void reviewBudget(ReviewBudget custom) {
        this.budget = custom;
    }

    // ------------------------------------------------------------------ Publisher 驱动

    /** 当前生效的 PublicationStore（可被 sabotage 包装替换） */
    PublicationStore store() {
        return storeOverride != null ? storeOverride : postgresStore;
    }

    void sabotageStore(PublicationStore wrapper) {
        this.storeOverride = wrapper;
    }

    void restoreStore() {
        this.storeOverride = null;
    }

    /** 每次调用新建：executor/claimer/scanner 构造期捕获 store， sabotage 后必须重建 */
    FencedPublicationExecutor newExecutor() {
        return new FencedPublicationExecutor(newGitHubAdapter(), store(), payloadReader, handlers,
                reconcileRetryDelay, probeMaxPages);
    }

    OutboxClaimer newClaimer() {
        return new OutboxClaimer(store(), newExecutor(), "it-publisher",
                Duration.ofSeconds(30), 10, 0, 0);
    }

    OutboxRecoveryScanner newScanner() {
        return new OutboxRecoveryScanner(store(), newExecutor(), handlers,
                unknownRetryDelay, maxReconcileNotFound, 50, 0, 0);
    }

    private GitHubWriteAdapter newGitHubAdapter() {
        CredentialBroker broker = new CredentialBroker() {
            @Override
            public String token(TokenScope scope) {
                return "it-token";
            }

            @Override
            public String token(long installationId, TokenScope scope) {
                return "it-token";
            }
        };
        return new GitHubWriteAdapter(broker, githubApiBase, PostgresITBase.OM, Duration.ofSeconds(5));
    }

    // ------------------------------------------------------------------ 夹具

    /** pull_request 事件（opened/synchronize/reopened 通用） */
    static PullRequestEvent prEvent(String deliveryId, long repositoryId, String repoFullName,
                                    int prNumber, String headSha, String action) {
        return new PullRequestEvent(deliveryId, action, INSTALLATION_ID, repositoryId,
                repoFullName, prNumber, "open", false, false, headSha, BASE_REF, BASE_SHA);
    }

    /** 最小 webhook 原文（EX-08/ST-05 用；与 GitHubWebhookParser 必需字段对齐） */
    static byte[] webhookBody(long repositoryId, String repoFullName, int prNumber,
                              String headSha, String action) {
        return ("""
                {"action":"%s","number":%d,
                 "installation":{"id":%d},
                 "repository":{"id":%d,"full_name":"%s"},
                 "pull_request":{"state":"open","draft":false,"merged":false,
                   "head":{"sha":"%s"},"base":{"ref":"%s","sha":"%s"}}}
                """).formatted(action, prNumber, INSTALLATION_ID, repositoryId, repoFullName,
                headSha, BASE_REF, BASE_SHA).getBytes(StandardCharsets.UTF_8);
    }

    /** webhook 原文 HMAC-SHA256 签名头值（EX-08） */
    static String sign(String secret, byte[] body) {
        try {
            javax.crypto.Mac mac = javax.crypto.Mac.getInstance("HmacSHA256");
            mac.init(new javax.crypto.spec.SecretKeySpec(
                    secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] raw = mac.doFinal(body);
            StringBuilder hex = new StringBuilder("sha256=");
            for (byte b : raw) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** 注册 T0 源内容并派发 intake（直连 Executor = 同步完成 T0+T1）；返回新 Run */
    ReviewRun dispatchOpened(PullRequestEvent event, byte[] tarGz, String diffText) {
        sourcePort.registerSnapshot(event.headSha(), tarGz)
                .registerDiff(event.baseSha(), event.headSha(), diffText);
        intakeService.accept(event, webhookBody(event.repositoryId(), event.repositoryFullName(),
                event.prNumber(), event.headSha(), event.action()));
        return runRepo.findByRunKey(revisionService.runKey(currentRevisionId(event), POLICY,
                PROMPT, TOOLSET, event.deliveryId())).orElseThrow();
    }

    private UUID currentRevisionId(PullRequestEvent event) {
        UUID subjectId = subjectRepo.findByRepositoryAndPrNumber(event.repositoryId(),
                event.prNumber()).orElseThrow().getId();
        return subjectRepo.findById(subjectId).orElseThrow().getCurrentRevisionId();
    }

    /** 直接建 IntakeCommand 跑 T1（绕开 T0：digest 手工给定，CAS 内容自备或不备） */
    ReviewRun runIntakeDirect(PullRequestEvent event, Digest diffDigest, Digest snapshotDigest) {
        return orchestrator().runIntake(new IntakeCommand(event.installationId(), event.repositoryId(),
                event.repositoryFullName(), event.prNumber(), PrSubjectState.OPEN, false, false,
                event.headSha(), event.baseRef(), event.baseSha(), null,
                diffDigest, snapshotDigest, POLICY, PROMPT, TOOLSET, event.deliveryId()));
    }

    /** T2 完成（代理事务内） */
    T2Outcome completeStep(StepCompletion completion) {
        return orchestrator().completeStep(completion);
    }

    /** Worker 未消费时手工领取 + 记 STARTED attempt（模拟"领到一半"的执行中状态） */
    record ClaimedWork(WorkItem workItem, RunStep step, StepAttempt attempt) {
    }

    ClaimedWork claimManually(String workerId) {
        WorkItem item = workItemRepo.claimNext(workerId, Instant.now(), 60).orElseThrow();
        RunStep step = stepRepo.findById(item.getStepId()).orElseThrow();
        StepAttempt attempt = new StepAttempt(UUID.randomUUID(), step.getId(), item.getId(),
                item.getAttemptCount(), item.getLeaseEpoch(), workerId,
                com.objwww.pr.shared.AttemptStatus.STARTED, null, null,
                step.getInputArtifactDigest(), null, null, null, null, Instant.now(), null);
        attemptRepo.save(attempt);
        return new ClaimedWork(item, step, attempt);
    }

    /** 直接向 outbox 铸造一条命令（T2 语义：control 角色 + 同事务领 sequence） */
    com.objwww.pr.shared.OutboxCommand seedCommand(UUID prSubjectId, UUID reviewRunId, UUID prRevisionId,
                                                   String aggregateKey, CommandType type,
                                                   Map<String, Object> payload,
                                                   List<PublicationRequest.DependencyEdge> deps) {
        OperationId opId = OperationId.random();
        Map<String, Object> full = new java.util.LinkedHashMap<>(payload);
        full.put("operation_id", opId.toString());
        if (type == CommandType.PUBLISH_REVIEW) {
            full.put("marker", PublishReviewHandler.markerOf(opId));
        }
        byte[] json;
        try {
            json = PostgresITBase.OM.writeValueAsString(full).getBytes(StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        final OperationId finalOpId = opId;
        return PostgresITBase.controlTx.execute(s -> outboxWriter.requestPublication(
                new PublicationRequest(finalOpId, prSubjectId, reviewRunId, prRevisionId,
                        aggregateKey, type, POLICY, json, deps)));
    }

    // ------------------------------------------------------------------ 账本/投影

    List<ExecutionEvent> eventsOf(UUID runId) {
        return eventRepo.findByRunIdOrdered(runId);
    }

    RunProjection fold(UUID runId) {
        return new Projector().fold(eventsOf(runId));
    }
}
