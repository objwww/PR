package com.objwww.pr.control.it;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.objwww.pr.control.application.InboxProcessor;
import com.objwww.pr.control.application.IntakeService;
import com.objwww.pr.control.application.OutboxWriter;
import com.objwww.pr.control.application.PrEventAuthoritativeReader;
import com.objwww.pr.control.application.PrStateReconciler;
import com.objwww.pr.control.application.ReviewOrchestrator;
import com.objwww.pr.control.application.SnapshotService;
import com.objwww.pr.control.domain.model.InboxState;
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
import com.objwww.pr.shared.Digest;
import com.objwww.pr.shared.Digests;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * ST-21 真实并发 IT（G1 评审指定五条关键用例之一，方案 §11 L3 表 + E2E-20 L6 表 + §4.3
 * 收敛点；Testcontainers PG 16，本机无 docker 自动跳过，留 195 真跑）：
 * webhook 与 PrStateReconciler <b>并发发现同一新 SHA</b> → 恰好一个 Revision / 恰好一个
 * active Run / 无重复 Outbox / 无未捕获异常逃逸。
 *
 * <p>并发构造：每轮直 SQL 种一行 OPEN 且到期（next_pr_reconcile_at 吃 V3 默认值 now()）
 * 的 pr_subject（无 revision 无 Run——两线程都处于"首次发现 head-new"的最坏竞态起点）；
 * 一线程驱动 InboxProcessor 处理该 head 的 synchronize inbox 事件（webhook 路径：
 * 权威读 → FullReview → IntakeService.dispatch → T1），另一线程驱动 PrStateReconciler.runOnce
 * （对账路径：探针 → 同一判定树 → (head,base) 漂移 → 合成 intake → 同一 dispatch）；
 * CyclicBarrier 对齐起跑，共 20 轮（每轮独立 PR 号/head/delivery，互不串场）。
 *
 * <p>IT 边界声明（与 InboxProcessorIT 同一诚实边界）：无 Spring 代理时 @Transactional
 * 退化为逐语句 auto-commit——T1 的"pr_subject 行锁下 check-then-insert"在本 IT 里
 * <b>锁窗口被刻意放大到最坏形态</b>（每条语句独立提交，行锁即放），收敛真正靠的是
 * DB 层两道真实生效的兜底：pr_subject / pr_revision 的唯一约束（upsert/指纹复用由
 * DuplicateKeyException → IntakeService 回读重试自愈）与 V3 部分唯一索引
 * uq_review_run_active_gen（并发洞穿的最后防线，run_key 含 trigger_key 拦不住双源）。
 * 输家允许走幂等完成/约束冲突自愈（webhook 侧 RETRY_WAIT、对账侧 error 退避），
 * 每轮收尾清退避各补一轮，验证经 IdempotentDone 收敛点落终态——这正是 E2E-20
 * "一个幂等返回，无 500、无重复"在 IT 层的对应物。
 */
class St21ConcurrentIntakeIT extends PostgresITBase {

    private static final String POLICY = "m1-policy-v1";
    private static final Instant EVENT_TIME = Instant.parse("2025-06-01T12:00:00Z");
    private static final int ROUNDS = 20;
    private static final int BASE_PR = 100;

    /** 权威读 stub：两线程并发读同一应答；字段 volatile 保证跨线程可见 */
    private static final class StubMetadataPort implements GitHubPrMetadataPort {
        volatile FetchResult next = new FetchResult.Unavailable("not_stubbed");
        volatile SanityResult sanity = SanityResult.READABLE;

        @Override
        public FetchResult fetchPullRequest(long installationId, String repoFullName, int prNumber) {
            return next;
        }

        @Override
        public SanityResult checkRepoReadable(long installationId, String repoFullName) {
            return sanity;
        }

        /** 远端事实：open 非 draft，head 为本轮新 SHA（两条路径的权威读共用同一事实） */
        void remoteOpen(String headSha) {
            next = new FetchResult.Found("open", false, false, headSha, "main", "basesha456",
                    EVENT_TIME);
        }
    }

    private PostgresWebhookInboxRepository inbox;
    private InboxProcessor processor;
    private PrStateReconciler reconciler;
    private StubMetadataPort metadataPort;

    @TempDir
    Path casDir;

    @BeforeEach
    void setUp() {
        inbox = new PostgresWebhookInboxRepository(controlJdbc);
        ObjectMapper om = new ObjectMapper();
        ArtifactStore artifactStore = new LocalCasArtifactStore(casDir);
        PostgresArtifactRepository artifacts = new PostgresArtifactRepository(controlJdbc);
        PostgresPRSubjectRepository subjects = new PostgresPRSubjectRepository(controlJdbc);
        PostgresPRRevisionRepository revisions = new PostgresPRRevisionRepository(controlJdbc);
        PostgresReviewRunRepository runs = new PostgresReviewRunRepository(controlJdbc);
        ExecutionLedger ledger = new ExecutionLedger(new PostgresExecutionEventRepository(controlJdbc, om));

        ReviewOrchestrator orchestrator = new ReviewOrchestrator(
                subjects, revisions, runs,
                new PostgresRunStepRepository(controlJdbc),
                new PostgresWorkItemRepository(controlJdbc),
                new PostgresStepAttemptRepository(controlJdbc),
                new PostgresReviewFindingRepository(controlJdbc),
                new RevisionService(), ledger,
                new OutboxWriter(new PostgresOutboxCommandRepository(controlJdbc),
                        new PostgresSequenceAllocator(controlJdbc), artifactStore, artifacts),
                om);

        // T0 触网 mock 掉（IT 不验网络）：digest 由 head sha 派生，run_key/revision 语义真实；
        // Mockito 应答式桩并发调用安全
        SnapshotService snapshotService = mock(SnapshotService.class);
        when(snapshotService.prepare(anyLong(), anyString(), anyString(), anyString()))
                .thenAnswer(inv -> new SnapshotService.SnapshotOutcome(
                        Digest.sha256Of("snap-" + inv.getArgument(3)),
                        Digest.sha256Of("diff-" + inv.getArgument(2) + "-" + inv.getArgument(3)),
                        3, 100));

        IntakeService intakeService = new IntakeService(snapshotService, orchestrator,
                artifactStore, artifacts, POLICY, "m1-prompt-v1", "m1-toolset-v1");
        metadataPort = new StubMetadataPort();
        PrEventAuthoritativeReader reader = new PrEventAuthoritativeReader(
                subjects, revisions, runs, metadataPort, POLICY);
        // webhook 路径 worker：退避 30s、上限 5 次（与 InboxProcessorIT 同参数）
        processor = new InboxProcessor(inbox, intakeService, reader, orchestrator, POLICY,
                "st21-worker", Duration.ofMinutes(10), 10, Duration.ofSeconds(30), 5, 0, 0);
        // 对账路径 worker：预算 20/轮、正常周期 30min、退避基数 60s、阈值 3（与 PrStateReconcilerIT 同参数）
        reconciler = new PrStateReconciler(subjects, revisions, runs, reader, orchestrator,
                intakeService, ledger, POLICY,
                20, Duration.ofMinutes(30), Duration.ofSeconds(60), 3, 0, 0);
    }

    // ------------------------------------------------------------------ ST-21

    /** ST-21 主体：20 轮双源并发抢建同一新 SHA 的 Revision/Run，逐轮断言收敛不变量 */
    @Test
    void st21_concurrentDiscoveryOfSameSha_convergesToExactlyOneRevisionOneRun() throws Exception {
        int webhookSelfHealRounds = 0;    // webhook 侧走 RETRY_WAIT 自愈的轮数（交错相关，只统计）
        int reconcilerSelfHealRounds = 0; // 对账侧走 error 退避自愈的轮数

        for (int round = 0; round < ROUNDS; round++) {
            int prNumber = BASE_PR + round;
            String headSha = "head-st21-" + round;
            String deliveryId = "st21-d" + round;
            seedDueSubject(prNumber);
            metadataPort.remoteOpen(headSha);
            insertWebhookInbox(deliveryId, prNumber, headSha);

            CyclicBarrier barrier = new CyclicBarrier(2);
            ConcurrentLinkedQueue<Throwable> escaped = new ConcurrentLinkedQueue<>();
            AtomicInteger webhookProcessed = new AtomicInteger(-1);
            AtomicInteger reconcilerProcessed = new AtomicInteger(-1);

            // 线程①：webhook 路径（inbox 领取 → 权威读 → FullReview → dispatch → T1）
            Thread webhookThread = Thread.ofVirtual().name("st21-webhook-" + round).start(() -> {
                try {
                    barrier.await();
                    webhookProcessed.set(processor.runOnce());
                } catch (Throwable t) { // 未捕获异常逃逸 = E2E-20 的"500 式"失败，收集后硬断言
                    escaped.add(t);
                }
            });
            // 线程②：对账路径（公平扫描 → 探针判定 → 漂移 → 合成 intake → 同一 dispatch）
            Thread reconcilerThread = Thread.ofVirtual().name("st21-reconciler-" + round).start(() -> {
                try {
                    barrier.await();
                    reconcilerProcessed.set(reconciler.runOnce());
                } catch (Throwable t) {
                    escaped.add(t);
                }
            });

            joinOrFail(webhookThread);
            joinOrFail(reconcilerThread);

            assertThat(escaped).as("第 %d 轮：不允许异常逃逸出 runOnce（允许幂等/约束冲突自愈，不许 500 式未捕获）", round)
                    .isEmpty();
            assertThat(webhookProcessed).as("第 %d 轮：webhook 线程应领取并处理 1 条 inbox 事件", round)
                    .hasValue(1);
            assertThat(reconcilerProcessed).as("第 %d 轮：reconciler 线程应对账 1 个到期投影", round)
                    .hasValue(1);

            // 自愈路径统计（不作硬断言：是否撞车取决于交错时序）
            if (stateOf(deliveryId) == InboxState.RETRY_WAIT) {
                webhookSelfHealRounds++;
            }
            if (subjectErrorCount(prNumber) > 0) {
                reconcilerSelfHealRounds++;
            }

            // 自愈收敛：清退避后各补一轮——输家经权威读命中 IdempotentDone 收敛点落终态，
            // 同时兼证"第二轮幂等无重复"（ST-14 第二轮的并发版）
            adminJdbc.sql("UPDATE webhook_inbox SET next_retry_at = now() WHERE state = 'RETRY_WAIT'")
                    .update();
            processor.runOnce();
            forceDue(prNumber);
            reconciler.runOnce();

            assertConverged(round, prNumber, headSha, deliveryId);
        }

        // 全局复核①：active Run 在 (revision, policy, prompt, toolset) 维度无重复
        // ——即 uq_review_run_active_gen 部分唯一索引谓词的直查（20 轮累计）
        Long dupActiveGen = adminJdbc.sql("""
                SELECT count(*) FROM (
                    SELECT 1 FROM review_run
                     WHERE state NOT IN ('COMPLETED','COMPLETED_WITH_WARNINGS','FAILED','CANCELLED','SUPERSEDED')
                     GROUP BY pr_revision_id, policy_version, prompt_version, toolset_version
                    HAVING count(*) > 1) t
                """).query(Long.class).single();
        assertThat(dupActiveGen).as("全局：任何 (revision, 策略代) 上至多一个 active Run").isZero();

        // 全局复核②：无重复 Outbox——T2 未跑应为零行；同 aggregate_key 的 sequence 无重复（直查）
        assertThat(count("outbox_command")).as("T1 不产 Outbox（Outbox 在 T2 才产生）").isZero();
        Long dupSequence = adminJdbc.sql("""
                SELECT count(*) FROM (
                    SELECT 1 FROM outbox_command
                     GROUP BY aggregate_key, aggregate_sequence
                    HAVING count(*) > 1) t
                """).query(Long.class).single();
        assertThat(dupSequence).as("全局：outbox_command 同 aggregate_key 的 sequence 无重复").isZero();

        // 全局复核③：20 轮总量守恒——恰好 20 个 Revision、恰好 20 个 active Run
        assertThat(count("pr_revision")).isEqualTo(ROUNDS);
        Long totalActive = adminJdbc.sql("""
                SELECT count(*) FROM review_run
                 WHERE state NOT IN ('COMPLETED','COMPLETED_WITH_WARNINGS','FAILED','CANCELLED','SUPERSEDED')
                """).query(Long.class).single();
        assertThat(totalActive).isEqualTo(ROUNDS);

        System.out.printf("ST-21 汇总：%d 轮；webhook 侧约束冲突自愈 %d 轮，对账侧 error 退避自愈 %d 轮%n",
                ROUNDS, webhookSelfHealRounds, reconcilerSelfHealRounds);
    }

    // ------------------------------------------------------------------ 构造与断言助手

    /** 种子投影：OPEN 非 draft、无 revision 无 Run、到期可对账（next_pr_reconcile_at 吃 V3 默认 now()） */
    private void seedDueSubject(int prNumber) {
        controlJdbc.sql("""
                        INSERT INTO pr_subject (
                            id, github_installation_id, github_repository_id, repository_full_name,
                            pr_number, state, draft, merged, current_policy_version,
                            created_at, updated_at)
                        VALUES (:id, 987, 12345, 'org/repo', :pr, 'OPEN', false, false,
                                'm1-policy-v1', now(), now())
                        """)
                .param("id", UUID.randomUUID())
                .param("pr", prNumber)
                .update();
    }

    /** webhook 入口：synchronize 事件落 inbox（payload 的 head 只是快照，权威读会以远端值覆盖） */
    private void insertWebhookInbox(String deliveryId, int prNumber, String headSha) {
        byte[] body = """
                {"action":"synchronize","number":%d,
                 "pull_request":{"state":"open","draft":false,"merged":false,
                   "updated_at":"2025-06-01T12:00:00Z",
                   "head":{"sha":"%s","ref":"feature"},"base":{"sha":"basesha456","ref":"main"}},
                 "repository":{"id":12345,"full_name":"org/repo"},
                 "installation":{"id":987}}
                """.formatted(prNumber, headSha).getBytes(StandardCharsets.UTF_8);
        assertThat(inbox.insertNew(deliveryId, "pull_request", "synchronize", 987L, 12345L,
                body, new String(body, StandardCharsets.UTF_8), Digests.sha256Hex(body))).isTrue();
    }

    /** admin 把本轮投影的下一跳拨回过去（模拟对账周期到点；拨的是 DB 时钟侧的值，I17） */
    private void forceDue(int prNumber) {
        adminJdbc.sql("UPDATE pr_subject SET next_pr_reconcile_at = now() - interval '1 minute'"
                        + " WHERE pr_number = :pr")
                .param("pr", prNumber)
                .update();
    }

    private InboxState stateOf(String deliveryId) {
        return inbox.findByDeliveryId(deliveryId).orElseThrow().getState();
    }

    private long subjectErrorCount(int prNumber) {
        return adminJdbc.sql("SELECT pr_reconcile_error_count FROM pr_subject WHERE pr_number = :pr")
                .param("pr", prNumber).query(Long.class).single();
    }

    /** 每轮收敛断言：恰好一 Revision、恰好一 active Run、inbox 终态、对账零残留错误 */
    private void assertConverged(int round, int prNumber, String headSha, String deliveryId) {
        // ① 恰好一个 Revision（指纹唯一约束兜底并发双插）
        Long revisions = adminJdbc.sql("SELECT count(*) FROM pr_revision WHERE head_sha = :head")
                .param("head", headSha).query(Long.class).single();
        assertThat(revisions).as("第 %d 轮：pr_revision 恰好 1 行", round).isEqualTo(1);

        // ② 该 revision 上恰好一个 active Run（uq_review_run_active_gen 兜底并发洞穿）
        Long active = adminJdbc.sql("""
                SELECT count(*) FROM review_run r
                 JOIN pr_revision v ON r.pr_revision_id = v.id
                 WHERE v.head_sha = :head
                   AND r.state NOT IN ('COMPLETED','COMPLETED_WITH_WARNINGS','FAILED','CANCELLED','SUPERSEDED')
                """).param("head", headSha).query(Long.class).single();
        assertThat(active).as("第 %d 轮：同 (revision, 策略代) 恰好 1 行 active Run", round).isEqualTo(1);

        // ③ 总 Run 行数 ∈ {1,2}：赢家一行；输家要么被索引拦下零行，要么先插入后被对方
        //    换届 SUPERSEDED（留一行终态，不构成第二个 active）
        Long totalRuns = adminJdbc.sql("""
                SELECT count(*) FROM review_run r
                 JOIN pr_revision v ON r.pr_revision_id = v.id
                 WHERE v.head_sha = :head
                """).param("head", headSha).query(Long.class).single();
        assertThat(totalRuns).as("第 %d 轮：review_run 总行数 1~2（输家行只能是 SUPERSEDED 终态）", round)
                .isBetween(1L, 2L);

        // ④ 输家自愈后 inbox 落 PROCESSED 终态（IdempotentDone 收敛点），不允许残留 RETRY_WAIT
        assertThat(stateOf(deliveryId)).as("第 %d 轮：inbox 事件收敛到 PROCESSED", round)
                .isEqualTo(InboxState.PROCESSED);

        // ⑤ 对账侧失败计数清零（若本轮对账是输家，补跑一轮后必须自愈归零）
        assertThat(subjectErrorCount(prNumber)).as("第 %d 轮：pr_reconcile_error_count 自愈归零", round)
                .isZero();
    }

    private static void joinOrFail(Thread t) throws InterruptedException {
        t.join(Duration.ofSeconds(30));
        assertThat(t.isAlive()).as("线程 %s 未在 30s 内结束（疑似死锁/阻塞）", t.getName()).isFalse();
    }
}
