package com.objwww.pr.control.it;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.objwww.pr.control.application.InboxProcessor;
import com.objwww.pr.control.application.IntakeService;
import com.objwww.pr.control.application.OutboxWriter;
import com.objwww.pr.control.application.PrEventAuthoritativeReader;
import com.objwww.pr.control.application.PublicationRequest;
import com.objwww.pr.control.application.ReviewOrchestrator;
import com.objwww.pr.control.application.SnapshotService;
import com.objwww.pr.control.domain.model.InboxState;
import com.objwww.pr.control.domain.model.PRSubject;
import com.objwww.pr.control.domain.port.ArtifactStore;
import com.objwww.pr.control.domain.port.GitHubPrMetadataPort;
import com.objwww.pr.control.domain.port.GitHubPrMetadataPort.FetchResult;
import com.objwww.pr.control.domain.port.GitHubPrMetadataPort.SanityResult;
import com.objwww.pr.control.domain.repository.PRSubjectRepository;
import com.objwww.pr.control.domain.service.ExecutionLedger;
import com.objwww.pr.control.domain.service.RevisionService;
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
import com.objwww.pr.control.infrastructure.persistence.PostgresWebhookInboxRepository;
import com.objwww.pr.control.infrastructure.persistence.PostgresWorkItemRepository;
import com.objwww.pr.shared.CommandType;
import com.objwww.pr.shared.Digest;
import com.objwww.pr.shared.Digests;
import com.objwww.pr.shared.OperationId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * M1-T05/T06 权威读路由业务闭环 IT（Testcontainers PG 16，本机无 docker 自动跳过，195 真跑）：
 * ST-11 陈旧事件零 API / ST-12 draft 全程零 Run 零 Outbox / ST-13 closed→CLOSED+epoch+1 /
 * ST-18 同 updated_at 不同 head 权威读裁决 / ST-19 控制侧（T-close 产出 sweep 输入条件）/
 * ST-20 reopened 新 epoch 新 Run / E2E-09 同 head 不同 policy：Revision 复用+epoch+1 /
 * E2E-10 base 变 head 不变→新 Revision / CT-14 并发水印 max。
 *
 * <p>接线同 InboxProcessorIT：仓储/编排全走真 PG（control_app 角色）；SnapshotService mock
 * （T0 触网）；权威读走可编程 stub port（fetchCalls 计数供 ST-11 零 API 断言）。
 * 模型零调用的断言方式：本 IT 不装配 WorkItemWorker，Run 建了也不会执行 REVIEW step——
 * ST-12 的"模型零调用"由"零 Run"蕴含（无 Run 即无 WorkItem 即无模型调用路径）。
 */
class AuthoritativeRoutingIT extends PostgresITBase {

    private static final String WORKER = "it-routing-worker";
    private static final Duration TTL = Duration.ofMinutes(10);
    private static final String POLICY_V1 = "m1-policy-v1";
    private static final Instant T1 = Instant.parse("2025-06-01T12:00:01Z");
    private static final Instant T2 = Instant.parse("2025-06-01T12:00:02Z");
    private static final Instant T3 = Instant.parse("2025-06-01T12:00:03Z");
    private static final Instant T4 = Instant.parse("2025-06-01T12:00:04Z");
    private static final Instant T5 = Instant.parse("2025-06-01T12:00:05Z");

    /** 权威读 stub：逐场可编程应答 + 调用计数 */
    private static final class StubMetadataPort implements GitHubPrMetadataPort {
        FetchResult next = new FetchResult.Unavailable("not_stubbed");
        SanityResult sanity = SanityResult.READABLE;
        int fetchCalls;

        @Override
        public FetchResult fetchPullRequest(long installationId, String repoFullName, int prNumber) {
            fetchCalls++;
            return next;
        }

        @Override
        public SanityResult checkRepoReadable(long installationId, String repoFullName) {
            return sanity;
        }

        void remote(String state, boolean draft, boolean merged, String headSha, String baseSha,
                    Instant updatedAt) {
            next = new FetchResult.Found(state, draft, merged, headSha, "main", baseSha, updatedAt);
        }
    }

    private PostgresWebhookInboxRepository inbox;
    private PRSubjectRepository subjectRepo;
    private ReviewOrchestrator orchestrator;
    private OutboxWriter outboxWriter;
    private SnapshotService snapshotService;
    private StubMetadataPort metadataPort;
    private ArtifactStore artifactStore;
    private PostgresArtifactRepository artifacts;
    private InboxProcessor processor;

    @TempDir
    Path casDir;

    @BeforeEach
    void setUp() {
        inbox = new PostgresWebhookInboxRepository(controlJdbc);
        ObjectMapper om = new ObjectMapper();
        artifactStore = new LocalCasArtifactStore(casDir);
        artifacts = new PostgresArtifactRepository(controlJdbc);
        subjectRepo = new PostgresPRSubjectRepository(controlJdbc);

        orchestrator = new ReviewOrchestrator(
                subjectRepo,
                new PostgresPRRevisionRepository(controlJdbc),
                new PostgresReviewRunRepository(controlJdbc),
                new PostgresRunStepRepository(controlJdbc),
                new PostgresWorkItemRepository(controlJdbc),
                new PostgresStepAttemptRepository(controlJdbc),
                new PostgresReviewFindingRepository(controlJdbc),
                new RevisionService(),
                new ExecutionLedger(new PostgresExecutionEventRepository(controlJdbc, om)),
                outboxWriter = new OutboxWriter(new PostgresOutboxCommandRepository(controlJdbc),
                        new PostgresSequenceAllocator(controlJdbc), artifactStore, artifacts),
                om);

        snapshotService = mock(SnapshotService.class);
        // digest 由 (base,head) 派生：base 变化也产生新 diff digest（E2E-10 新 Revision 的前提）
        when(snapshotService.prepare(anyLong(), anyString(), anyString(), anyString()))
                .thenAnswer(inv -> new SnapshotService.SnapshotOutcome(
                        Digest.sha256Of("snap-" + inv.getArgument(3)),
                        Digest.sha256Of("diff-" + inv.getArgument(2) + "-" + inv.getArgument(3)),
                        3, 100));

        metadataPort = new StubMetadataPort();
        processor = newProcessor(POLICY_V1);
    }

    /** 按策略代装配一条完整路由链（E2E-09 用 v2 再装一套；policy 是部署配置） */
    private InboxProcessor newProcessor(String policyVersion) {
        IntakeService intake = new IntakeService(snapshotService, orchestrator, artifactStore, artifacts,
                policyVersion, "m1-prompt-v1", "m1-toolset-v1");
        PrEventAuthoritativeReader reader = new PrEventAuthoritativeReader(
                new PostgresPRSubjectRepository(controlJdbc),
                new PostgresPRRevisionRepository(controlJdbc),
                new PostgresReviewRunRepository(controlJdbc),
                metadataPort, policyVersion);
        return new InboxProcessor(inbox, intake, reader, orchestrator, policyVersion,
                WORKER, TTL, 10, Duration.ofSeconds(30), 5, 0, 0);
    }

    private static byte[] prPayload(String action, String headSha, Instant updatedAt) {
        return ("""
                {"action":"%s","number":7,
                 "pull_request":{"state":"open","draft":false,"merged":false,
                   "updated_at":"%s",
                   "head":{"sha":"%s","ref":"feature"},"base":{"sha":"basesha456","ref":"main"}},
                 "repository":{"id":12345,"full_name":"org/repo"},
                 "installation":{"id":987}}
                """).formatted(action, updatedAt.toString(), headSha).getBytes(StandardCharsets.UTF_8);
    }

    private void insertInbox(String deliveryId, String action, String headSha, Instant updatedAt) {
        byte[] body = prPayload(action, headSha, updatedAt);
        assertThat(inbox.insertNew(deliveryId, "pull_request", action, 987L, 12345L,
                body, new String(body, StandardCharsets.UTF_8), Digests.sha256Hex(body))).isTrue();
    }

    private InboxState stateOf(String deliveryId) {
        return inbox.findByDeliveryId(deliveryId).orElseThrow().getState();
    }

    private PRSubject subject() {
        return subjectRepo.findByRepositoryAndPrNumber(12345L, 7).orElseThrow();
    }

    // ------------------------------------------------------------------ ST-11

    /** ST-11：事件 updated_at < 水印 → 快筛 IGNORED，零 API 调用，投影不回退 */
    @Test
    void st11_staleEventIgnoredWithZeroApiCallAndNoProjectionRegression() {
        metadataPort.remote("open", false, false, "head-a", "basesha456", T2);
        insertInbox("st11-d1", "opened", "head-a", T2);
        assertThat(processor.runOnce()).isEqualTo(1);
        assertThat(stateOf("st11-d1")).isEqualTo(InboxState.PROCESSED);
        assertThat(subject().getLastEventUpdatedAt()).isEqualTo(T2); // 水印已推进
        int callsAfterFirst = metadataPort.fetchCalls;

        // 乱序旧事件（updated_at=T1 < 水印 T2，payload 里的 head 也是旧的）
        insertInbox("st11-d2", "synchronize", "head-old", T1);
        assertThat(processor.runOnce()).isEqualTo(1);

        assertThat(stateOf("st11-d2")).isEqualTo(InboxState.IGNORED);
        assertThat(metadataPort.fetchCalls).isEqualTo(callsAfterFirst); // 零 API（省钱的那层）
        // 投影不回退：current revision 仍是 head-a 的，水印仍是 T2
        UUID revisionId = subject().getCurrentRevisionId();
        assertThat(new PostgresPRRevisionRepository(controlJdbc).findById(revisionId).orElseThrow()
                .getHeadSha()).isEqualTo("head-a");
        assertThat(subject().getLastEventUpdatedAt()).isEqualTo(T2);
    }

    // ------------------------------------------------------------------ ST-12

    /** ST-12：draft opened + 3 次 sync → 零 Run/零 Outbox；ready_for_review → 全量评审 */
    @Test
    void st12_draftLoopCostsOneGetPerPushThenReadyRunsFullReview() {
        // draft 期间 4 个事件：每次成本 = 一次权威读 GET，零 T0/Run/Outbox/模型。
        // 逐条插入逐条处理（INC-27：批量 claim 的 UPDATE...RETURNING 顺序不定，
        // 乱序时旧事件被水印守卫 IGNORED——那是 LWW 防线的设计行为，不是本用例要测的
        // "每次 draft push 一次 GET"语义，故本用例必须按序喂事件）
        metadataPort.remote("open", true, false, "head-d1", "basesha456", T1);
        insertInbox("st12-d1", "opened", "head-d1", T1);
        assertThat(processor.runOnce()).isEqualTo(1);
        metadataPort.remote("open", true, false, "head-d2", "basesha456", T2);
        insertInbox("st12-d2", "synchronize", "head-d2", T2);
        assertThat(processor.runOnce()).isEqualTo(1);
        metadataPort.remote("open", true, false, "head-d3", "basesha456", T3);
        insertInbox("st12-d3", "synchronize", "head-d3", T3);
        assertThat(processor.runOnce()).isEqualTo(1);
        metadataPort.remote("open", true, false, "head-d4", "basesha456", T4);
        insertInbox("st12-d4", "synchronize", "head-d4", T4);
        assertThat(processor.runOnce()).isEqualTo(1);

        // I11：零 Run、零 Outbox、零 Revision；投影 draft=true，水印推进到 T4
        assertThat(count("review_run")).isZero();
        assertThat(count("outbox_command")).isZero();
        assertThat(count("pr_revision")).isZero();
        assertThat(subject().isDraft()).isTrue();
        assertThat(subject().getPublicationEpoch()).isZero(); // 预检不换届
        assertThat(subject().getLastEventUpdatedAt()).isEqualTo(T4);
        assertThat(metadataPort.fetchCalls).isEqualTo(4);

        // ready_for_review → 权威读 draft=false → 全量 T0/T1
        metadataPort.remote("open", false, false, "head-d4", "basesha456", T5);
        insertInbox("st12-d5", "ready_for_review", "head-d4", T5);
        assertThat(processor.runOnce()).isEqualTo(1);

        assertThat(count("review_run")).isEqualTo(1);
        assertThat(count("pr_revision")).isEqualTo(1);
        assertThat(subject().isDraft()).isFalse();
        assertThat(subject().getPublicationEpoch()).isEqualTo(1);
        assertThat(stateOf("st12-d5")).isEqualTo(InboxState.PROCESSED);
    }

    // ------------------------------------------------------------------ ST-13 / ST-20

    /** ST-13：closed → 投影 CLOSED + epoch+1 + 在途 Run SUPERSEDED（同事务） */
    @Test
    void st13_closedBumpsEpochAndSupersedesActiveRun() {
        metadataPort.remote("open", false, false, "head-c1", "basesha456", T1);
        insertInbox("st13-d1", "opened", "head-c1", T1);
        assertThat(processor.runOnce()).isEqualTo(1);
        assertThat(subject().getPublicationEpoch()).isEqualTo(1);

        metadataPort.remote("closed", false, true, "head-c1", "basesha456", T2);
        insertInbox("st13-d2", "closed", "head-c1", T2);
        assertThat(processor.runOnce()).isEqualTo(1);

        PRSubject s = subject();
        assertThat(s.getState().name()).isEqualTo("CLOSED");
        assertThat(s.isMerged()).isTrue();
        assertThat(s.getPublicationEpoch()).isEqualTo(2); // I15：closed 必递增
        assertThat(s.getLastEventUpdatedAt()).isEqualTo(T2);
        assertThat(stateOf("st13-d2")).isEqualTo(InboxState.PROCESSED);
        // 在途 Run 已 SUPERSEDED（无 active）
        Long active = adminJdbc.sql("SELECT count(*) FROM review_run"
                + " WHERE state NOT IN ('COMPLETED','COMPLETED_WITH_WARNINGS','FAILED','CANCELLED','SUPERSEDED')")
                .query(Long.class).single();
        assertThat(active).isZero();
    }

    /** ST-20：reopened 即使代码未变（同 head）也走完整 T1：新 epoch 新 Run，Revision 复用 */
    @Test
    void st20_reopenedWithUnchangedCodeGetsNewEpochAndNewRun() {
        metadataPort.remote("open", false, false, "head-r1", "basesha456", T1);
        insertInbox("st20-d1", "opened", "head-r1", T1);
        processor.runOnce();
        metadataPort.remote("closed", false, false, "head-r1", "basesha456", T2);
        insertInbox("st20-d2", "closed", "head-r1", T2);
        processor.runOnce();
        assertThat(subject().getPublicationEpoch()).isEqualTo(2);

        // reopened：head 未变——换届是状态语义不是 diff 语义（§4.4）
        metadataPort.remote("open", false, false, "head-r1", "basesha456", T3);
        insertInbox("st20-d3", "reopened", "head-r1", T3);
        assertThat(processor.runOnce()).isEqualTo(1);

        PRSubject s = subject();
        assertThat(s.getState().name()).isEqualTo("OPEN");
        assertThat(s.getPublicationEpoch()).isEqualTo(3); // 新 epoch
        assertThat(count("review_run")).isEqualTo(2);      // 新 Run
        assertThat(count("pr_revision")).isEqualTo(1);     // 同 (head,base,diff) → Revision 复用
        assertThat(stateOf("st20-d3")).isEqualTo(InboxState.PROCESSED);
    }

    // ------------------------------------------------------------------ ST-18

    /** ST-18：同 updated_at、不同 head SHA——快筛放行（同值不误杀），权威读只为远端当前 SHA 建 Run */
    @Test
    void st18_sameTimestampDifferentHeadResolvedByAuthoritativeRead() {
        // 第一个 synchronize：远端 head-b
        metadataPort.remote("open", false, false, "head-b", "basesha456", T2);
        insertInbox("st18-d1", "synchronize", "head-b", T2);
        assertThat(processor.runOnce()).isEqualTo(1);
        assertThat(count("review_run")).isEqualTo(1);

        // 同秒乱序：updated_at 同值 T2，payload 说 head-c——快筛看不出来的情况（图 3-2）
        metadataPort.remote("open", false, false, "head-c", "basesha456", T2);
        insertInbox("st18-d2", "synchronize", "head-c", T2);
        assertThat(processor.runOnce()).isEqualTo(1); // 等于水印 → 放行，权威读裁决

        // 远端当前 head-c 建了新 Run，head-b 的被 SUPERSEDED
        List<String> activeHeads = adminJdbc.sql("""
                SELECT r.head_sha FROM pr_revision r
                JOIN review_run rr ON rr.pr_revision_id = r.id
                WHERE rr.state NOT IN ('COMPLETED','COMPLETED_WITH_WARNINGS','FAILED','CANCELLED','SUPERSEDED')
                """).query((rs, n) -> rs.getString(1)).list();
        assertThat(activeHeads).containsExactly("head-c");
        assertThat(count("review_run")).isEqualTo(2);
    }

    // ------------------------------------------------------------------ ST-19（控制侧半段）

    /**
     * ST-19 控制侧：closed 后 epoch+1，使旧世代 PENDING 命令 epoch 落后——这正是 Publisher
     * sweepStaleEpoch 的发现条件（路径③ findStaleEpoch：PENDING/RETRY_WAIT 且 epoch 落后）。
     * 级联+游标推进的执行断言在 publisher 侧 ST19StaleEpochSweepIT（publisher IT 线束
     * 同 JVM 可装配双进程全栈，见该类的类注释）。
     */
    @Test
    void st19_closeLeavesOldEpochPendingCommandForPublisherSweep() {
        metadataPort.remote("open", false, false, "head-s1", "basesha456", T1);
        insertInbox("st19-d1", "opened", "head-s1", T1);
        processor.runOnce();
        PRSubject subject = subject();

        // 铸一条 epoch=1 的 PENDING 命令（T2 语义的种子：control 角色 + 同事务领 sequence）
        controlTx.executeWithoutResult(status -> outboxWriter.requestPublication(
                new PublicationRequest(OperationId.random(), subject.getId(),
                        adminJdbc.sql("SELECT id FROM review_run").query(UUID.class).single(),
                        subject.getCurrentRevisionId(), "pr:12345#7",
                        CommandType.CREATE_CHECK, POLICY_V1,
                        "{\"repo\":\"org/repo\"}".getBytes(StandardCharsets.UTF_8), List.of())));
        Long epochOfCommand = adminJdbc.sql(
                "SELECT publication_epoch FROM outbox_command").query(Long.class).single();
        assertThat(epochOfCommand).isEqualTo(1);

        metadataPort.remote("closed", false, false, "head-s1", "basesha456", T2);
        insertInbox("st19-d2", "closed", "head-s1", T2);
        processor.runOnce();

        // T-close 不级联（交给 publisher sweep）：命令仍 PENDING 但 epoch 已落后（1 < 2）
        assertThat(subject().getPublicationEpoch()).isEqualTo(2);
        Map<String, Object> row = adminJdbc.sql(
                "SELECT state, publication_epoch FROM outbox_command")
                .query((rs, n) -> Map.<String, Object>of("state", rs.getString(1),
                        "epoch", rs.getLong(2))).single();
        assertThat(row.get("state")).isEqualTo("PENDING");
        assertThat((Long) row.get("epoch")).isLessThan(2L);
    }

    // ------------------------------------------------------------------ E2E-09 / E2E-10（降为 L3 IT，v1.2 记录表）

    /**
     * E2E-09：同 head 不同 policyVersion（部署配置换代）→ Revision 复用 + epoch+1 + 旧 Run 作废。
     * 换届 fence 断言（评审对账补）：旧 epoch 的 PENDING 命令不被 Control 级联——
     * Control 不动 outbox 是既有设计（v2.1 修订三），fence 语义 = epoch 落后，
     * SUPERSEDED 级联由 Publisher sweepStaleEpoch 兜底（publisher 半段见 ST19StaleEpochSweepIT，
     * 与 T-close 换届同一扫描路径，换届源不区分）。
     */
    @Test
    void e2e09_sameHeadDifferentPolicyReusesRevisionAndBumpsEpoch() {
        metadataPort.remote("open", false, false, "head-p1", "basesha456", T1);
        insertInbox("e9-d1", "opened", "head-p1", T1);
        processor.runOnce();
        assertThat(subject().getPublicationEpoch()).isEqualTo(1);

        // 铸一条 v1 世代（epoch=1）的 PENDING 命令作为 fence 观察样本
        UUID v1RunId = adminJdbc.sql("SELECT id FROM review_run").query(UUID.class).single();
        controlTx.executeWithoutResult(status -> outboxWriter.requestPublication(
                new PublicationRequest(OperationId.random(), subject().getId(), v1RunId,
                        subject().getCurrentRevisionId(), "pr:12345#7",
                        CommandType.CREATE_CHECK, POLICY_V1,
                        "{\"repo\":\"org/repo\"}".getBytes(StandardCharsets.UTF_8), List.of())));

        // 部署换代：policy v2 的路由链；同 head 同 base 的 synchronize
        InboxProcessor processorV2 = newProcessor("m1-policy-v2");
        metadataPort.remote("open", false, false, "head-p1", "basesha456", T2);
        insertInbox("e9-d2", "synchronize", "head-p1", T2);
        assertThat(processorV2.runOnce()).isEqualTo(1);

        // v1 世代 active Run 与 v2 不同代 → 不幂等 → 全量；T1 复用 Revision 但换届
        assertThat(count("pr_revision")).isEqualTo(1); // Revision 复用（fingerprint 不含 policy）
        assertThat(count("review_run")).isEqualTo(2);
        assertThat(subject().getPublicationEpoch()).isEqualTo(2); // epoch+1
        assertThat(subject().getCurrentPolicyVersion()).isEqualTo("m1-policy-v2");
        assertThat(stateOf("e9-d2")).isEqualTo(InboxState.PROCESSED);

        // 旧 epoch PENDING 命令：Control 不级联（仍 PENDING），epoch 落后（1 < 2）
        // —— 即 Publisher sweepStaleEpoch 的发现条件，fence 在执行侧拦截
        assertThat(count("outbox_command")).isEqualTo(1);
        Map<String, Object> oldCommand = adminJdbc.sql(
                "SELECT state, publication_epoch FROM outbox_command")
                .query((rs, n) -> Map.<String, Object>of("state", rs.getString(1),
                        "epoch", rs.getLong(2))).single();
        assertThat(oldCommand.get("state")).isEqualTo("PENDING");
        assertThat((Long) oldCommand.get("epoch")).isLessThan(2L);
    }

    /**
     * E2E-10：base 变 head 不变（权威读二元组比对）→ 新 Revision + 换届。
     * 换届 fence 断言（评审对账补，单测参照 PrStateReconcilerTest::
     * baseDriftWithSameHeadSynthesizesIntake）：旧世代 PENDING 命令同样不被 Control 级联，
     * epoch 落后即 fence（publisher 半段级联断言见 CT06CascadeSupersedeIT/ST19StaleEpochSweepIT）。
     */
    @Test
    void e2e10_baseChangeWithSameHeadYieldsNewRevision() {
        metadataPort.remote("open", false, false, "head-b1", "basesha-a", T1);
        insertInbox("e10-d1", "opened", "head-b1", T1);
        processor.runOnce();
        UUID firstRevision = subject().getCurrentRevisionId();

        // 铸一条 epoch=1 的 PENDING 命令作为 fence 观察样本
        UUID v1RunId = adminJdbc.sql("SELECT id FROM review_run").query(UUID.class).single();
        controlTx.executeWithoutResult(status -> outboxWriter.requestPublication(
                new PublicationRequest(OperationId.random(), subject().getId(), v1RunId,
                        firstRevision, "pr:12345#7",
                        CommandType.CREATE_CHECK, POLICY_V1,
                        "{\"repo\":\"org/repo\"}".getBytes(StandardCharsets.UTF_8), List.of())));

        // 远端 base 变了（head 不变）：(head, base) 二元组不一致 → 全量 → 新 Revision
        metadataPort.remote("open", false, false, "head-b1", "basesha-b", T2);
        insertInbox("e10-d2", "synchronize", "head-b1", T2);
        assertThat(processor.runOnce()).isEqualTo(1);

        assertThat(count("pr_revision")).isEqualTo(2);
        assertThat(subject().getCurrentRevisionId()).isNotEqualTo(firstRevision);
        assertThat(subject().getPublicationEpoch()).isEqualTo(2);
        assertThat(count("review_run")).isEqualTo(2);

        // 旧世代 PENDING 命令：Control 不级联（仍 PENDING、仍指旧 revision），epoch 落后
        assertThat(count("outbox_command")).isEqualTo(1);
        Map<String, Object> oldCommand = adminJdbc.sql(
                "SELECT state, publication_epoch, pr_revision_id FROM outbox_command")
                .query((rs, n) -> Map.<String, Object>of("state", rs.getString(1),
                        "epoch", rs.getLong(2), "revision", rs.getObject(3, UUID.class))).single();
        assertThat(oldCommand.get("state")).isEqualTo("PENDING");
        assertThat((Long) oldCommand.get("epoch")).isLessThan(2L);
        assertThat(oldCommand.get("revision")).isEqualTo(firstRevision);
    }

    // ------------------------------------------------------------------ CT-14

    /** CT-14：并发两路水印推进（GREATEST 条件更新）→ 收敛于 max，旧值不覆新值 */
    @Test
    void ct14_concurrentWatermarkAdvanceConvergesToMax() throws Exception {
        metadataPort.remote("open", false, false, "head-w1", "basesha456", T1);
        insertInbox("ct14-d1", "opened", "head-w1", T1);
        processor.runOnce();
        UUID subjectId = subject().getId();
        assertThat(subject().getLastEventUpdatedAt()).isEqualTo(T1);

        // 两线程交错推进：A 递推偶数秒，B 递推奇数秒；最大值在 B 的最后一棒
        Instant base = Instant.parse("2025-06-01T13:00:00Z");
        int rounds = 50;
        CountDownLatch start = new CountDownLatch(1);
        PRSubjectRepository repoA = new PostgresPRSubjectRepository(controlJdbc);
        PRSubjectRepository repoB = new PostgresPRSubjectRepository(controlJdbc);
        Thread a = new Thread(() -> {
            await(start);
            for (int i = 0; i < rounds; i++) {
                repoA.advanceWatermarkIfNewer(subjectId, base.plusSeconds(2L * i), base);
            }
        });
        Thread b = new Thread(() -> {
            await(start);
            for (int i = 0; i < rounds; i++) {
                repoB.advanceWatermarkIfNewer(subjectId, base.plusSeconds(2L * i + 1), base);
            }
        });
        a.start();
        b.start();
        start.countDown();
        a.join(TimeUnit.SECONDS.toMillis(30));
        b.join(TimeUnit.SECONDS.toMillis(30));

        // 收敛于 max（base + 99s），无并发回退
        assertThat(subject().getLastEventUpdatedAt()).isEqualTo(base.plusSeconds(2L * rounds - 1));
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}
