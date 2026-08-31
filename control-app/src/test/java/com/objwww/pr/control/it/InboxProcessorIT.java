package com.objwww.pr.control.it;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.objwww.pr.control.application.InboxProcessor;
import com.objwww.pr.control.application.IntakeService;
import com.objwww.pr.control.application.OutboxWriter;
import com.objwww.pr.control.application.PrEventAuthoritativeReader;
import com.objwww.pr.control.application.ReviewOrchestrator;
import com.objwww.pr.control.application.SnapshotService;
import com.objwww.pr.control.domain.model.InboxState;
import com.objwww.pr.control.domain.model.WebhookInbox;
import com.objwww.pr.control.domain.port.ArtifactStore;
import com.objwww.pr.control.domain.port.GitHubPrMetadataPort;
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
import com.objwww.pr.control.interfaces.webhook.GitHubSignatureVerifier;
import com.objwww.pr.control.interfaces.webhook.PullRequestEvent;
import com.objwww.pr.control.interfaces.webhook.WebhookController;
import com.objwww.pr.shared.Digest;
import com.objwww.pr.shared.Digests;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * M1-T03/T04 组件与业务闭环 IT（Testcontainers PG 16，本机无 docker 自动跳过，195 真跑）：
 * ST-09 重投一行一 Run / ST-16 非处理事件 IGNORED 留痕 / CT-16 耗尽死信+重投不唤醒+显式复活 /
 * CT-18 raw/jsonb 分离字节不变 / ST-17 三个崩溃窗口恰好一 Run / E2E-22 畸形 JSON 死信留审计。
 *
 * <p>接线说明：inbox/仓储/编排全走真 PG（control_app 角色，真实授权兜底）；SnapshotService
 * 是唯一 mock（T0 要触网，IT 不验网络）；ReviewOrchestrator 的 @Transactional 在无 Spring
 * 代理的 IT 里退化为逐语句 auto-commit——窗口断言（恰好一 Run）不受影响：幂等兜底靠的是
 * run_key 唯一约束本身，不是事务边界。崩溃用语义模拟（领取后不回写 + admin 拨租约过期），
 * 不真杀进程。
 */
class InboxProcessorIT extends PostgresITBase {

    private static final String SECRET = "it-webhook-secret";
    private static final String URL = "/webhooks/github";
    private static final String WORKER = "it-worker";
    private static final Duration TTL = Duration.ofMinutes(10);

    private final GitHubSignatureVerifier verifier = new GitHubSignatureVerifier(SECRET);

    private PostgresWebhookInboxRepository inbox;
    private IntakeService intakeService;
    private InboxProcessor processor;
    private StubMetadataPort metadataPort;
    private ReviewOrchestrator orchestrator;
    private PrEventAuthoritativeReader reader;
    private MockMvc mvc;

    @TempDir
    Path casDir;

    /** 权威读 stub（M1-T05）：应答由测试逐场设置；fetchCalls 供 ST-11 零 API 断言 */
    static final class StubMetadataPort implements GitHubPrMetadataPort {
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

        /** 常规应答：open 非 draft，head/base/updatedAt 与测试 payload 对齐 */
        void remoteOpen(String headSha) {
            next = new FetchResult.Found("open", false, false, headSha, "main", "basesha456",
                    java.time.Instant.parse("2025-06-01T12:00:00Z"));
        }
    }

    @BeforeEach
    void setUp() {
        inbox = new PostgresWebhookInboxRepository(controlJdbc);
        ObjectMapper objectMapper = new ObjectMapper();
        ArtifactStore artifactStore = new LocalCasArtifactStore(casDir);
        PostgresArtifactRepository artifacts = new PostgresArtifactRepository(controlJdbc);

        orchestrator = new ReviewOrchestrator(
                new PostgresPRSubjectRepository(controlJdbc),
                new PostgresPRRevisionRepository(controlJdbc),
                new PostgresReviewRunRepository(controlJdbc),
                new PostgresRunStepRepository(controlJdbc),
                new PostgresWorkItemRepository(controlJdbc),
                new PostgresStepAttemptRepository(controlJdbc),
                new PostgresReviewFindingRepository(controlJdbc),
                new RevisionService(),
                new ExecutionLedger(new PostgresExecutionEventRepository(controlJdbc, objectMapper)),
                new OutboxWriter(new PostgresOutboxCommandRepository(controlJdbc),
                        new PostgresSequenceAllocator(controlJdbc), artifactStore, artifacts),
                objectMapper);

        // T0 触网 mock 掉：digest 由 head sha 派生，保证 run_key/revision 语义真实
        SnapshotService snapshotService = mock(SnapshotService.class);
        when(snapshotService.prepare(anyLong(), anyString(), anyString(), anyString()))
                .thenAnswer(inv -> new SnapshotService.SnapshotOutcome(
                        Digest.sha256Of("snap-" + inv.getArgument(3)),
                        Digest.sha256Of("diff-" + inv.getArgument(3)), 3, 100));

        intakeService = new IntakeService(snapshotService, orchestrator, artifactStore, artifacts,
                "m1-policy-v1", "m1-prompt-v1", "m1-toolset-v1");
        metadataPort = new StubMetadataPort();
        reader = new PrEventAuthoritativeReader(
                new PostgresPRSubjectRepository(controlJdbc),
                new PostgresPRRevisionRepository(controlJdbc),
                new PostgresReviewRunRepository(controlJdbc),
                metadataPort, "m1-policy-v1");
        processor = new InboxProcessor(inbox, intakeService, reader, orchestrator, "m1-policy-v1",
                WORKER, TTL, 10, Duration.ofSeconds(30), 5, 0, 0);
        mvc = MockMvcBuilders.standaloneSetup(new WebhookController(SECRET, inbox)).build();
    }

    private static String prPayload(String action, String headSha) {
        // M1-T05：payload 携带 pull_request.updated_at（LWW 快筛输入）
        return """
                {"action":"%s","number":7,
                 "pull_request":{"state":"open","draft":false,"merged":false,
                   "updated_at":"2025-06-01T12:00:00Z",
                   "head":{"sha":"%s","ref":"feature"},"base":{"sha":"basesha456","ref":"main"}},
                 "repository":{"id":12345,"full_name":"org/repo"},
                 "installation":{"id":987}}
                """.formatted(action, headSha);
    }

    private void postGithub(byte[] body, String eventType, String deliveryId) throws Exception {
        mvc.perform(post(URL).content(body)
                .header("X-Hub-Signature-256", verifier.sign(body))
                .header("X-GitHub-Event", eventType)
                .header("X-GitHub-Delivery", deliveryId));
    }

    /** 模拟崩溃后租约过期（admin 拨时间；语义 = 进程被杀，行停 PROCESSING） */
    private void expireLease(String deliveryId) {
        adminJdbc.sql("""
                UPDATE webhook_inbox SET lease_until = now() - interval '1 second'
                 WHERE delivery_id = :id
                """).param("id", deliveryId).update();
    }

    private InboxState stateOf(String deliveryId) {
        return inbox.findByDeliveryId(deliveryId).orElseThrow().getState();
    }

    // ------------------------------------------------------------------ ST-09

    /**
     * ST-09：同 delivery 重复 POST → inbox 一行、Run 一个。
     * 重投应答按原行状态如实回放（RedeliveryDecision）：
     * 在途（RECEIVED/PROCESSING/RETRY_WAIT）→ 202 processing；终态（PROCESSED/IGNORED）→ 200 duplicate。
     * （INC-27：初版在处理前重投却断言 200 duplicate——在途重投的正确应答是 202 processing）
     */
    @Test
    void st09_redeliveryYieldsOneInboxRowAndOneRun() throws Exception {
        byte[] body = prPayload("opened", "headsha-st09").getBytes(StandardCharsets.UTF_8);

        mvc.perform(post(URL).content(body)
                        .header("X-Hub-Signature-256", verifier.sign(body))
                        .header("X-GitHub-Event", "pull_request")
                        .header("X-GitHub-Delivery", "st09-d1"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("accepted"));
        // 处理前的在途重投：不重复派发，如实应答"处理中"
        mvc.perform(post(URL).content(body)
                        .header("X-Hub-Signature-256", verifier.sign(body))
                        .header("X-GitHub-Event", "pull_request")
                        .header("X-GitHub-Delivery", "st09-d1"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("processing"));

        metadataPort.remoteOpen("headsha-st09"); // 权威读应答：open 非 draft，head 与 payload 一致
        assertThat(processor.runOnce()).isEqualTo(1);

        // 处理完成后的重投：回放当初的处理结论
        mvc.perform(post(URL).content(body)
                        .header("X-Hub-Signature-256", verifier.sign(body))
                        .header("X-GitHub-Event", "pull_request")
                        .header("X-GitHub-Delivery", "st09-d1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("duplicate"));

        assertThat(count("webhook_inbox")).isEqualTo(1);
        assertThat(count("review_run")).isEqualTo(1);
        assertThat(stateOf("st09-d1")).isEqualTo(InboxState.PROCESSED);
        assertThat(count("outbox_command")).isZero(); // T2 未跑，零发布意图
    }

    // ------------------------------------------------------------------ ST-16

    /** ST-16：push / 六外 action → 202 受理 + IGNORED 留痕（INC-16 关闭），零 Run */
    @Test
    void st16_nonHandledEventsAreAcceptedThenIgnoredWithAuditTrail() throws Exception {
        postGithub("{\"action\":\"opened\"}".getBytes(StandardCharsets.UTF_8), "push", "st16-push");
        postGithub(prPayload("labeled", "h1").getBytes(StandardCharsets.UTF_8), "pull_request", "st16-labeled");

        assertThat(processor.runOnce()).isEqualTo(2);

        assertThat(stateOf("st16-push")).isEqualTo(InboxState.IGNORED);
        assertThat(stateOf("st16-labeled")).isEqualTo(InboxState.IGNORED);
        assertThat(count("review_run")).isZero();
    }

    // ------------------------------------------------------------------ CT-16

    /** CT-16：重试耗尽 → DEAD_LETTER；普通重投不唤醒；显式管理 UPDATE 复活后重领 */
    @Test
    void ct16_exhaustionDeadLetterNotWokenByRedeliveryButRevivableByOperator() throws Exception {
        // 必败的 intake（配置上限 2：两次失败即耗尽）
        IntakeService failingIntake = mock(IntakeService.class);
        doThrow(new RuntimeException("boom")).when(failingIntake).dispatch(any(), any());
        InboxProcessor failingProcessor = new InboxProcessor(inbox, failingIntake, reader, orchestrator,
                "m1-policy-v1", WORKER, TTL, 10, Duration.ofSeconds(30), 2, 0, 0);
        metadataPort.remoteOpen("headsha-ct16");

        byte[] body = prPayload("opened", "headsha-ct16").getBytes(StandardCharsets.UTF_8);
        assertThat(inbox.insertNew("ct16-d1", "pull_request", "opened", 987L, 12345L,
                body, new String(body, StandardCharsets.UTF_8), Digests.sha256Hex(body))).isTrue();

        // 第 1 次失败 → RETRY_WAIT(1)；拨到点 → 第 2 次失败 → DEAD_LETTER(2)
        failingProcessor.runOnce();
        assertThat(stateOf("ct16-d1")).isEqualTo(InboxState.RETRY_WAIT);
        adminJdbc.sql("UPDATE webhook_inbox SET next_retry_at = now() - interval '1 second'"
                + " WHERE delivery_id = 'ct16-d1'").update();
        failingProcessor.runOnce();
        WebhookInbox dead = inbox.findByDeliveryId("ct16-d1").orElseThrow();
        assertThat(dead.getState()).isEqualTo(InboxState.DEAD_LETTER);
        assertThat(dead.getAttemptCount()).isEqualTo(2);
        assertThat(dead.getLastError()).contains("dispatch_exhausted");

        // I16：同 delivery 重投 → 200 dead_letter，不唤醒
        mvc.perform(post(URL).content(body)
                        .header("X-Hub-Signature-256", verifier.sign(body))
                        .header("X-GitHub-Event", "pull_request")
                        .header("X-GitHub-Delivery", "ct16-d1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("dead_letter"));
        assertThat(stateOf("ct16-d1")).isEqualTo(InboxState.DEAD_LETTER);

        // 显式管理复活（运维 SQL 直改，唯一复活路径）→ 新 lease epoch 重领重试
        adminJdbc.sql("""
                UPDATE webhook_inbox SET state = 'RETRY_WAIT', attempt_count = 0, next_retry_at = now()
                 WHERE delivery_id = 'ct16-d1'
                """).update();
        failingProcessor.runOnce();
        WebhookInbox revived = inbox.findByDeliveryId("ct16-d1").orElseThrow();
        assertThat(revived.getState()).isEqualTo(InboxState.RETRY_WAIT);
        assertThat(revived.getAttemptCount()).isEqualTo(1);
        assertThat(revived.getLeaseEpoch()).isEqualTo(3); // 首领 1 + 重领 2 + 复活后重领 3
    }

    // ------------------------------------------------------------------ CT-18

    /** CT-18：jsonb 规范化后 payload_raw 字节不变；HMAC 对 raw 复核通过 */
    @Test
    void ct18_rawBytesSurviveJsonbNormalizationAndHmacRechecksAgainstRaw() throws Exception {
        // 键序 + 多余空白：jsonb 必然规范化（排序/去空白），raw 必须逐字节保真
        byte[] body = "{  \"z\": 1,   \"a\": 2 }".getBytes(StandardCharsets.UTF_8);
        postGithub(body, "pull_request", "ct18-d1");

        // raw 逐字节保真（审计与 HMAC 复核的唯一权威）
        assertThat(inbox.payloadRaw("ct18-d1")).isEqualTo(body);
        // jsonb 已被规范化（≠ 原文），仅供查询/路由
        String jsonbText = adminJdbc.sql(
                        "SELECT payload_json::text FROM webhook_inbox WHERE delivery_id = 'ct18-d1'")
                .query(String.class).single();
        assertThat(jsonbText).isNotEqualTo(new String(body, StandardCharsets.UTF_8));
        // HMAC 永远可对 raw 复核通过
        assertThat(verifier.verify(inbox.payloadRaw("ct18-d1"), verifier.sign(body))).isTrue();
    }

    // ------------------------------------------------------------------ E2E-22

    /** E2E-22：合法签名 + 畸形 JSON → 202 受理；Processor 判 DEAD_LETTER(malformed)；不建 Run */
    @Test
    void e2e22_malformedJsonWithValidSignatureIsAcceptedThenDeadLettered() throws Exception {
        byte[] body = "这不是 JSON".getBytes(StandardCharsets.UTF_8);

        mvc.perform(post(URL).content(body)
                        .header("X-Hub-Signature-256", verifier.sign(body))
                        .header("X-GitHub-Event", "pull_request")
                        .header("X-GitHub-Delivery", "e22-d1"))
                .andExpect(status().isAccepted());
        // 留审计：raw 落库、payload_json 为 NULL
        assertThat(inbox.payloadRaw("e22-d1")).isEqualTo(body);
        Long jsonRows = adminJdbc.sql("SELECT count(*) FROM webhook_inbox"
                + " WHERE delivery_id = 'e22-d1' AND payload_json IS NULL").query(Long.class).single();
        assertThat(jsonRows).isEqualTo(1);

        processor.runOnce();
        WebhookInbox row = inbox.findByDeliveryId("e22-d1").orElseThrow();
        assertThat(row.getState()).isEqualTo(InboxState.DEAD_LETTER);
        assertThat(row.getLastError()).contains("malformed_json");
        assertThat(count("review_run")).isZero();
    }

    // ------------------------------------------------------------------ ST-17 三个崩溃窗口

    /** ST-17 窗口①：领取后、T1 前 kill（行停 PROCESSING）→ 租约回收重放，恰好一 Run */
    @Test
    void st17_windowA_killBeforeT1_reclaimAndReplayYieldsExactlyOneRun() {
        byte[] body = prPayload("opened", "headsha-wa").getBytes(StandardCharsets.UTF_8);
        assertThat(inbox.insertNew("wa-d1", "pull_request", "opened", 987L, 12345L,
                body, new String(body, StandardCharsets.UTF_8), Digests.sha256Hex(body))).isTrue();

        // 崩溃模拟：crashed-worker 领取（PROCESSING，epoch 1）后死掉，dispatch 从未执行
        assertThat(inbox.claim(1, "crashed-worker", TTL)).hasSize(1);
        assertThat(count("review_run")).isZero();
        expireLease("wa-d1");

        // 回收重放：T1 首建 → 恰好一 Run → PROCESSED
        metadataPort.remoteOpen("headsha-wa");
        assertThat(processor.runOnce()).isEqualTo(1);
        assertThat(count("review_run")).isEqualTo(1);
        assertThat(stateOf("wa-d1")).isEqualTo(InboxState.PROCESSED);
    }

    /** ST-17 窗口②：T1 已提交、inbox 未回写 kill → 回收重放靠 run_key 幂等，恰好一 Run */
    @Test
    void st17_windowB_killAfterT1CommitBeforeWriteback_replayIsIdempotent() {
        byte[] body = prPayload("opened", "headsha-wb").getBytes(StandardCharsets.UTF_8);
        assertThat(inbox.insertNew("wb-d1", "pull_request", "opened", 987L, 12345L,
                body, new String(body, StandardCharsets.UTF_8), Digests.sha256Hex(body))).isTrue();

        // 崩溃模拟：领取 → dispatch 直接驱动（T1 提交，Run 已建）→ kill，inbox 永不回写
        WebhookInbox claimed = inbox.claim(1, "crashed-worker", TTL).get(0);
        PullRequestEvent event = new PullRequestEvent("wb-d1", "opened", 987L, 12345L, "org/repo",
                7, "open", false, false, "headsha-wb", "main", "basesha456",
                java.time.Instant.parse("2025-06-01T12:00:00Z"));
        intakeService.dispatch(event, body);
        assertThat(count("review_run")).isEqualTo(1);
        assertThat(stateOf("wb-d1")).isEqualTo(InboxState.PROCESSING); // 未回写
        expireLease("wb-d1");

        // 回收重放：T05 起权威读先命中 IdempotentDone 收敛点（ST-21：远端==投影且同策略代
        // active Run）→ 连 T1 都不进，直接 PROCESSED；仍恰好一 Run
        metadataPort.remoteOpen("headsha-wb");
        assertThat(processor.runOnce()).isEqualTo(1);
        assertThat(count("review_run")).isEqualTo(1);
        assertThat(stateOf("wb-d1")).isEqualTo(InboxState.PROCESSED);
        assertThat(claimed.getLeaseEpoch()).isEqualTo(1); // 旧主的 epoch 已被重领 +1 栅栏
    }

    /** ST-17 窗口③：inbox 回写 PROCESSED 后 kill → 终态零重放；重投 duplicate，仍一 Run */
    @Test
    void st17_windowC_killAfterInboxCompleted_terminalStateMeansZeroReplay() throws Exception {
        byte[] body = prPayload("opened", "headsha-wc").getBytes(StandardCharsets.UTF_8);
        assertThat(inbox.insertNew("wc-d1", "pull_request", "opened", 987L, 12345L,
                body, new String(body, StandardCharsets.UTF_8), Digests.sha256Hex(body))).isTrue();

        metadataPort.remoteOpen("headsha-wc");
        assertThat(processor.runOnce()).isEqualTo(1);
        assertThat(stateOf("wc-d1")).isEqualTo(InboxState.PROCESSED);
        // 此刻进程死掉：行已终态，无任何待回写动作

        // 重投 → duplicate；再跑 worker → 领取为零；Run 不增
        mvc.perform(post(URL).content(body)
                        .header("X-Hub-Signature-256", verifier.sign(body))
                        .header("X-GitHub-Event", "pull_request")
                        .header("X-GitHub-Delivery", "wc-d1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("duplicate"));
        assertThat(processor.runOnce()).isZero();
        assertThat(count("review_run")).isEqualTo(1);
    }
}
