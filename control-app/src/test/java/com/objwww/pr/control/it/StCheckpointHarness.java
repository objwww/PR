package com.objwww.pr.control.it;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.objwww.pr.control.application.CheckpointWriter;
import com.objwww.pr.control.application.OutboxWriter;
import com.objwww.pr.control.application.ReviewOrchestrator;
import com.objwww.pr.control.application.ReviewStepExecutor;
import com.objwww.pr.control.application.StepCompletion;
import com.objwww.pr.control.application.StepExecutionContext;
import com.objwww.pr.control.application.StepExecutor;
import com.objwww.pr.control.application.StepOutcome;
import com.objwww.pr.control.application.T2Outcome;
import com.objwww.pr.control.application.WorkItemWorker;
import com.objwww.pr.control.domain.ai.MockModelGateway;
import com.objwww.pr.control.domain.ai.ModelGatewayPort;
import com.objwww.pr.control.domain.ai.ModelRouteCatalog;
import com.objwww.pr.control.domain.ai.ModelRouteIdentity;
import com.objwww.pr.control.domain.model.RunStep;
import com.objwww.pr.control.domain.model.StepAttempt;
import com.objwww.pr.control.domain.model.WorkItem;
import com.objwww.pr.control.domain.port.ArtifactStore;
import com.objwww.pr.control.domain.review.FindingMapper;
import com.objwww.pr.control.domain.review.ReviewAgentLoop;
import com.objwww.pr.control.domain.review.ReviewBudget;
import com.objwww.pr.control.domain.service.CheckpointResumeService;
import com.objwww.pr.control.domain.service.ExecutionLedger;
import com.objwww.pr.control.domain.service.RevisionService;
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
import com.objwww.pr.control.infrastructure.persistence.PostgresStepCheckpointRepository;
import com.objwww.pr.control.infrastructure.persistence.PostgresWorkItemRepository;
import com.objwww.pr.control.support.TestTarballs;
import com.objwww.pr.shared.AttemptStatus;
import com.objwww.pr.shared.Digest;
import com.objwww.pr.shared.Digests;
import org.springframework.aop.Pointcut;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.aop.support.DefaultPointcutAdvisor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * ST-23~30 / EX-21/22（M2 方案 §11 L3/L4，§4.2 崩溃窗口）的公共线束：
 * 真 PG 仓储（control_app 角色）+ 本地 CAS + 手工装配 ReviewStepExecutor / WorkItemWorker
 * （等价 ReviewFlowConfig 的 docker profile 接线；仿 publisher 侧 ItHarness.newWorker）。
 *
 * <p>事务语义与生产一致：{@link ReviewOrchestrator#completeStep}（T2）与
 * {@link CheckpointWriter#store}（checkpoint 短事务）均经 TransactionInterceptor CGLIB
 * 代理，@Transactional 真实生效——整笔要么全落要么全回滚，崩溃窗口断言才有意义。
 *
 * <p>形态约定：
 * <ul>
 *   <li>种子数据走 adminJdbc 直 SQL（T0/T1 不是本族用例的被测面），同一 subject 可追加
 *       多个 revision/run（ST-30 双路径对比需要同 aggregate_key）；</li>
 *   <li>驱动一律确定性：{@code claim + execute + complete} 手工三段，或
 *       {@link WorkItemWorker#runOnce()} 单轮；不起后台循环线程；</li>
 *   <li>"崩溃"= 注入桩抛 {@link SimulatedCrash} 或直接丢弃执行结果不再收尾（进程死亡
 *       没有对应用层可见的善后），随后以新 workerId 重建 Worker 模拟进程重启。</li>
 * </ul>
 */
final class StCheckpointHarness {

    static final String POLICY = "m2-policy-v1";
    static final String TOOLSET = "m2-toolset-v1";
    static final String PROMPT_V1 = "m2-prompt-v1";
    static final String PROMPT_V2 = "m2-prompt-v2";
    static final long INSTALLATION_ID = 77L;
    static final long REPOSITORY_ID = 12345L;
    static final String REPO_FULL_NAME = "org/repo";

    /** 模型固定产出：一条 div-zero finding（existing_code 触发行号重定位到 1，与单测样本同源） */
    static final String MODEL_OUTPUT = """
            [{"file":"a/Foo.java","line":50,"existing_code":"int x = 0/1;","rule":"div-zero","severity":"MAJOR","message":"除零"}]
            """;
    static final String FILE_CONTENT = "int x = 0/1;\n";
    static final String DIFF_TEXT = "diff --git a/Foo.java\n";

    /** 进程被杀：与真异常同构（RuntimeException），Worker 归类为 Unexpected/retryable */
    static final class SimulatedCrash extends RuntimeException {
        SimulatedCrash(String point) {
            super("模拟 control 进程被杀: " + point);
        }
    }

    /** 一簇种子行的标识（run 维度一个 REVIEW step + 一个 READY work_item） */
    record Seed(UUID subjectId, UUID revisionId, UUID runId, UUID stepId, UUID workItemId,
                Digest snapshotDigest, Digest diffDigest) {
    }

    /** 手工领取的结果：LEASED WorkItem + Step + 已落库的 STARTED attempt（等价 Worker 开工段） */
    record Claimed(WorkItem item, RunStep step, StepAttempt attempt) {
        StepExecutionContext context() {
            return new StepExecutionContext(item, step, attempt.getId());
        }

        StepCompletion completion(StepOutcome outcome) {
            return new StepCompletion(item.getId(), step.getId(), attempt.getId(),
                    item.getLeaseOwner(), item.getLeaseEpoch(), outcome);
        }
    }

    final JdbcClient jdbc = PostgresITBase.controlJdbc;
    final ObjectMapper om = new ObjectMapper();
    final LocalCasArtifactStore cas;
    final PostgresArtifactRepository artifactRepo;
    final PostgresStepCheckpointRepository checkpointRepo;
    final PostgresPRSubjectRepository subjectRepo;
    final PostgresPRRevisionRepository revisionRepo;
    final PostgresReviewRunRepository runRepo;
    final PostgresRunStepRepository stepRepo;
    final PostgresWorkItemRepository workItemRepo;
    final PostgresStepAttemptRepository attemptRepo;
    final PostgresReviewFindingRepository findingRepo;
    final ExecutionLedger ledger;
    private final OutboxWriter outboxWriter;
    private final ReviewOrchestrator orchestratorProxy;
    private final SafeTarExtractor extractor = new SafeTarExtractor();

    StCheckpointHarness(Path casDir) {
        cas = new LocalCasArtifactStore(casDir);
        artifactRepo = new PostgresArtifactRepository(jdbc);
        checkpointRepo = new PostgresStepCheckpointRepository(jdbc);
        subjectRepo = new PostgresPRSubjectRepository(jdbc);
        revisionRepo = new PostgresPRRevisionRepository(jdbc);
        runRepo = new PostgresReviewRunRepository(jdbc);
        stepRepo = new PostgresRunStepRepository(jdbc);
        workItemRepo = new PostgresWorkItemRepository(jdbc);
        attemptRepo = new PostgresStepAttemptRepository(jdbc);
        findingRepo = new PostgresReviewFindingRepository(jdbc);
        ledger = new ExecutionLedger(new PostgresExecutionEventRepository(jdbc, om));
        outboxWriter = new OutboxWriter(new PostgresOutboxCommandRepository(jdbc),
                new PostgresSequenceAllocator(jdbc), cas, artifactRepo);
        orchestratorProxy = transactionalProxy(new ReviewOrchestrator(subjectRepo, revisionRepo,
                runRepo, stepRepo, workItemRepo, attemptRepo, findingRepo,
                new RevisionService(), ledger, outboxWriter, om));
    }

    // ------------------------------------------------------------------ 装配

    /** @Transactional 代理：与生产 docker profile 的 Spring AOP 语义一致（CGLIB，类代理） */
    @SuppressWarnings("unchecked")
    static <T> T transactionalProxy(T target) {
        TransactionInterceptor interceptor = new TransactionInterceptor(
                new DataSourceTransactionManager(PostgresITBase.controlDataSource()),
                new AnnotationTransactionAttributeSource());
        ProxyFactory factory = new ProxyFactory(target);
        factory.addAdvisor(new DefaultPointcutAdvisor(Pointcut.TRUE, interceptor));
        return (T) factory.getProxy();
    }

    /** 默认 checkpoint 短事务写器（@Transactional 代理后） */
    CheckpointWriter newCheckpointWriter() {
        return transactionalProxy(new CheckpointWriter(artifactRepo, checkpointRepo, ledger));
    }

    /** mock 路由目录：按请求模型名反查 mock 契约身份（checkpoint 落库身份与 resume 反查一致） */
    static final ModelRouteCatalog MOCK_ROUTE_CATALOG = requestedModel ->
            java.util.Optional.of(new ModelRouteIdentity("mock-provider", requestedModel, "v1"));

    /** 以指定 CAS/CheckpointWriter/模型网关装配 REVIEW 执行器（注入桩在此接线） */
    ReviewStepExecutor newReviewExecutor(ArtifactStore store, CheckpointWriter writer,
                                         ModelGatewayPort modelGateway) {
        var resume = new CheckpointResumeService(checkpointRepo, artifactRepo, store, ledger, om);
        var agentLoop = new ReviewAgentLoop(modelGateway,
                new FindingMapper(), new PolicyEngine(new ToolRegistry()));
        return new ReviewStepExecutor(runRepo, revisionRepo, store, artifactRepo, extractor,
                agentLoop, ReviewBudget.DEFAULT, om, resume, writer, ledger, MOCK_ROUTE_CATALOG);
    }

    /** 默认形态执行器（真 CAS + 真 checkpoint 写器 + 指定模型网关） */
    ReviewStepExecutor newReviewExecutor(ModelGatewayPort modelGateway) {
        return newReviewExecutor(cas, newCheckpointWriter(), modelGateway);
    }

    /** ST-28 用：记录最近一次执行结果的执行器（接线同默认形态） */
    StCheckpointRecordingExecutor newRecordingExecutor(ModelGatewayPort modelGateway) {
        var resume = new CheckpointResumeService(checkpointRepo, artifactRepo, cas, ledger, om);
        var agentLoop = new ReviewAgentLoop(modelGateway,
                new FindingMapper(), new PolicyEngine(new ToolRegistry()));
        return new StCheckpointRecordingExecutor(runRepo, revisionRepo, cas, artifactRepo,
                extractor, agentLoop, ReviewBudget.DEFAULT, om, resume, newCheckpointWriter(),
                ledger, MOCK_ROUTE_CATALOG);
    }

    /** 新建 Worker（心跳 60s 不干扰确定性时序；租约上限 600s；runOnce 单轮驱动） */
    WorkItemWorker newWorker(String workerId, StepExecutor executor) {
        return new WorkItemWorker(workItemRepo, stepRepo, attemptRepo, List.of(executor),
                orchestratorProxy, workerId, 600, 60_000, 0, 0, 50);
    }

    WorkItemWorker newWorker(String workerId, ModelGatewayPort modelGateway) {
        return newWorker(workerId, newReviewExecutor(modelGateway));
    }

    ReviewOrchestrator orchestrator() {
        return orchestratorProxy;
    }

    // ------------------------------------------------------------------ 种子与驱动

    /** 首个种子：subject + revision + Run + REVIEW Step + READY WorkItem；CAS 装好输入 */
    Seed seedFirstRun(int prNumber, String headSha, String promptVersion) {
        UUID subjectId = UUID.randomUUID();
        PostgresITBase.adminJdbc.sql("""
                INSERT INTO pr_subject(id,github_installation_id,github_repository_id,repository_full_name,
                    pr_number,state,draft,merged,current_policy_version,publication_epoch,
                    next_outbox_sequence,last_resolved_sequence,version,created_at,updated_at)
                VALUES (:id,:inst,:repo,:name,:pr,'OPEN',false,false,:policy,1,1,0,0,now(),now())
                """).param("id", subjectId).param("inst", INSTALLATION_ID)
                .param("repo", REPOSITORY_ID).param("name", REPO_FULL_NAME)
                .param("pr", prNumber).param("policy", POLICY).update();
        return seedRunOnSubject(subjectId, prNumber, headSha, promptVersion);
    }

    /** 在同一 subject 上追加一个 revision/run/step/work_item（ST-30 双路径同 aggregate_key） */
    Seed seedRunOnSubject(UUID subjectId, int prNumber, String headSha, String promptVersion) {
        byte[] tarball = TestTarballs.tarGz(out ->
                TestTarballs.file(out, TestTarballs.GH_PREFIX + "a/Foo.java", FILE_CONTENT));
        byte[] diff = DIFF_TEXT.getBytes(StandardCharsets.UTF_8);
        Digest snapshotDigest = new Digest(Digests.sha256Hex(tarball));
        Digest diffDigest = new Digest(Digests.sha256Hex(diff));
        cas.putIfAbsent(snapshotDigest, tarball);
        cas.putIfAbsent(diffDigest, diff);

        UUID revisionId = UUID.randomUUID();
        UUID runId = UUID.randomUUID();
        UUID stepId = UUID.randomUUID();
        UUID workItemId = UUID.randomUUID();
        PostgresITBase.adminJdbc.sql("""
                INSERT INTO pr_revision(id,pr_subject_id,head_sha,base_ref,base_sha,diff_digest,
                    source_snapshot_digest,revision_fingerprint,observed_at,created_at)
                VALUES (:r,:s,:head,'main','base',:dd,:sd,:fp,now(),now())
                """).param("r", revisionId).param("s", subjectId).param("head", headSha)
                .param("dd", diffDigest.value()).param("sd", snapshotDigest.value())
                .param("fp", Digest.sha256Of("fp-" + headSha).value()).update();
        PostgresITBase.adminJdbc.sql("UPDATE pr_subject SET current_revision_id=:r WHERE id=:s")
                .param("r", revisionId).param("s", subjectId).update();
        PostgresITBase.adminJdbc.sql("""
                INSERT INTO review_run(id,pr_revision_id,run_key,trigger_key,run_mode,policy_version,
                    prompt_version,toolset_version,state,publisher_disabled,version,created_at,updated_at)
                VALUES (:id,:r,:key,'st-checkpoint','NORMAL',:policy,:prompt,:tools,'CREATED',
                    false,0,now(),now())
                """).param("id", runId).param("r", revisionId)
                .param("key", Digest.sha256Of("run-" + runId).value())
                .param("policy", POLICY).param("prompt", promptVersion)
                .param("tools", TOOLSET).update();
        PostgresITBase.adminJdbc.sql("""
                INSERT INTO run_step(id,review_run_id,step_key,operation_id,step_type,state,ordinal,
                    input_artifact_digest,max_attempts,timeout_seconds,version,created_at,updated_at)
                VALUES (:id,:run,'review',:op,'REVIEW','READY',1,:input,3,600,0,now(),now())
                """).param("id", stepId).param("run", runId).param("op", UUID.randomUUID())
                .param("input", diffDigest.value()).update();
        PostgresITBase.adminJdbc.sql("""
                INSERT INTO work_item(id,review_run_id,step_id,work_type,state,priority,available_at,
                    lease_owner,lease_until,lease_epoch,attempt_count,max_attempts,created_at,updated_at)
                VALUES (:id,:run,:step,'REVIEW','READY',0,now(),NULL,NULL,0,0,3,now(),now())
                """).param("id", workItemId).param("run", runId).param("step", stepId).update();
        return new Seed(subjectId, revisionId, runId, stepId, workItemId, snapshotDigest, diffDigest);
    }

    /** 手工领取（等价 Worker 的领取+开工段）：LEASED 租约 + STARTED attempt 落库 */
    Claimed claim(String workerId) {
        WorkItem item = workItemRepo.claimNext(workerId, 600).orElseThrow();
        RunStep step = stepRepo.findById(item.getStepId()).orElseThrow();
        StepAttempt attempt = new StepAttempt(UUID.randomUUID(), step.getId(), item.getId(),
                item.getAttemptCount(), item.getLeaseEpoch(), workerId, AttemptStatus.STARTED,
                null, null, step.getInputArtifactDigest(), null, null, null, null,
                Instant.now(), null);
        attemptRepo.save(attempt);
        return new Claimed(item, step, attempt);
    }

    /** T2 收尾（代理事务内整笔提交/回滚） */
    T2Outcome complete(Claimed claimed, StepOutcome outcome) {
        return orchestratorProxy.completeStep(claimed.completion(outcome));
    }

    // ------------------------------------------------------------------ 测试动作（admin 拨时钟/拨状态）

    /** 模拟时间推移：租约到期（recovery scan 将接管） */
    void forceLeaseExpired(UUID workItemId) {
        PostgresITBase.adminJdbc.sql("UPDATE work_item SET lease_until = now() - interval '1 second'"
                        + " WHERE id = :id")
                .param("id", workItemId).update();
    }

    /** RETRY_WAIT 到期回流：available_at 拨回过去，下一轮可被领取 */
    void forceClaimable(UUID workItemId) {
        PostgresITBase.adminJdbc.sql("UPDATE work_item SET available_at = now() - interval '1 second'"
                        + " WHERE id = :id")
                .param("id", workItemId).update();
    }

    /** EX-22 注入面：bump run 的 prompt_version（等价 prompt 常量 bump 后旧 run 续跑看到的契约漂移） */
    void bumpPromptVersion(UUID runId, String newPromptVersion) {
        PostgresITBase.adminJdbc.sql("UPDATE review_run SET prompt_version = :p WHERE id = :id")
                .param("p", newPromptVersion).param("id", runId).update();
    }

    // ------------------------------------------------------------------ 取证查询（admin 视角）

    long eventCount(UUID runId, String eventType) {
        return PostgresITBase.adminJdbc.sql(
                        "SELECT count(*) FROM execution_event WHERE review_run_id = :r AND event_type = :t")
                .param("r", runId).param("t", eventType).query(Long.class).single();
    }

    /** 最近一次 CHECKPOINT_DISCARDED 的精确 reason（EX-21/22、ST-25 取证） */
    String lastDiscardReason(UUID runId) {
        return PostgresITBase.adminJdbc.sql("""
                SELECT payload->>'reason' FROM execution_event
                 WHERE review_run_id = :r AND event_type = 'CHECKPOINT_DISCARDED'
                 ORDER BY position DESC LIMIT 1
                """).param("r", runId).query(String.class).single();
    }

    String workItemState(UUID workItemId) {
        return PostgresITBase.adminJdbc.sql("SELECT state FROM work_item WHERE id = :id")
                .param("id", workItemId).query(String.class).single();
    }

    long workItemEpoch(UUID workItemId) {
        return PostgresITBase.adminJdbc.sql("SELECT lease_epoch FROM work_item WHERE id = :id")
                .param("id", workItemId).query(Long.class).single();
    }

    String stepState(UUID stepId) {
        return PostgresITBase.adminJdbc.sql("SELECT state FROM run_step WHERE id = :id")
                .param("id", stepId).query(String.class).single();
    }

    String runState(UUID runId) {
        return PostgresITBase.adminJdbc.sql("SELECT state FROM review_run WHERE id = :id")
                .param("id", runId).query(String.class).single();
    }

    /** 模型调用计数用网关（requests() 留痕） */
    static MockModelGateway modelReturningOutput() {
        MockModelGateway gateway = new MockModelGateway();
        gateway.enqueueContent(MODEL_OUTPUT);
        return gateway;
    }
}
