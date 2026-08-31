package com.objwww.pr.control.it;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.objwww.pr.control.application.IntakeService;
import com.objwww.pr.control.application.OutboxWriter;
import com.objwww.pr.control.application.PrEventAuthoritativeReader;
import com.objwww.pr.control.application.PrStateReconciler;
import com.objwww.pr.control.application.ReviewOrchestrator;
import com.objwww.pr.control.application.SnapshotService;
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
import com.objwww.pr.control.infrastructure.persistence.PostgresWorkItemRepository;
import com.objwww.pr.control.interfaces.webhook.PullRequestEvent;
import com.objwww.pr.shared.Digest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * PrStateReconciler 业务闭环 IT（M1-T07，Testcontainers PG 16，本机无 docker 自动跳过，
 * 留 195 真跑；接线与 AuthoritativeRoutingIT 同形态：仓储/编排全真 PG control_app 角色，
 * T0 触网 mock，权威读可编程 stub）：
 *
 * <ul>
 *   <li>ST-14：webhook 丢失（stub 直接改 head，无 inbox 事件）→ reconciler 一轮补建 Run
 *       （trigger_key 确定性合成值）；第二轮幂等无重复（ST-21 收敛点的对账侧体现）；</li>
 *   <li>E2E-14（降为 L2/L3 IT，v1.2 记录表）：100 个待对账 PR、LIMIT 20 → 五轮全覆盖，
 *       不死循环前 20 行；</li>
 *   <li>EX-12：权威读 5xx → 不动投影 + error_count 递增；连 3 次 → ReconcilerDegraded
 *       账本事件（措辞修正 #3）；</li>
 *   <li>EX-16：429 + Retry-After → 下一跳不早于 retryAfter，且下一轮全局暂停零 API。</li>
 * </ul>
 */
class PrStateReconcilerIT extends PostgresITBase {

    private static final String POLICY = "m1-policy-v1";
    private static final Instant T1 = Instant.parse("2025-06-01T12:00:00Z");

    /** 权威读 stub：逐场可编程应答 + 调用计数（EX-16 零 API 断言用） */
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

        void remote(String state, boolean draft, boolean merged, String headSha, String baseSha) {
            next = new FetchResult.Found(state, draft, merged, headSha, "main", baseSha, T1);
        }
    }

    private PRSubjectRepository subjectRepo;
    private IntakeService intakeService;
    private StubMetadataPort metadataPort;
    private PrStateReconciler reconciler;

    @TempDir
    Path casDir;

    @BeforeEach
    void setUp() {
        ObjectMapper om = new ObjectMapper();
        ArtifactStore artifactStore = new LocalCasArtifactStore(casDir);
        PostgresArtifactRepository artifacts = new PostgresArtifactRepository(controlJdbc);
        subjectRepo = new PostgresPRSubjectRepository(controlJdbc);
        PostgresPRRevisionRepository revisions = new PostgresPRRevisionRepository(controlJdbc);
        PostgresReviewRunRepository runs = new PostgresReviewRunRepository(controlJdbc);
        ExecutionLedger ledger = new ExecutionLedger(new PostgresExecutionEventRepository(controlJdbc, om));

        ReviewOrchestrator orchestrator = new ReviewOrchestrator(
                subjectRepo, revisions, runs,
                new PostgresRunStepRepository(controlJdbc),
                new PostgresWorkItemRepository(controlJdbc),
                new PostgresStepAttemptRepository(controlJdbc),
                new PostgresReviewFindingRepository(controlJdbc),
                new RevisionService(), ledger,
                new OutboxWriter(new PostgresOutboxCommandRepository(controlJdbc),
                        new PostgresSequenceAllocator(controlJdbc), artifactStore, artifacts),
                om);

        SnapshotService snapshotService = mock(SnapshotService.class);
        when(snapshotService.prepare(anyLong(), anyString(), anyString(), anyString()))
                .thenAnswer(inv -> new SnapshotService.SnapshotOutcome(
                        Digest.sha256Of("snap-" + inv.getArgument(3)),
                        Digest.sha256Of("diff-" + inv.getArgument(2) + "-" + inv.getArgument(3)),
                        3, 100));

        intakeService = new IntakeService(snapshotService, orchestrator, artifactStore, artifacts,
                POLICY, "m1-prompt-v1", "m1-toolset-v1");
        metadataPort = new StubMetadataPort();
        PrEventAuthoritativeReader reader = new PrEventAuthoritativeReader(
                subjectRepo, revisions, runs, metadataPort, POLICY);
        // API 预算 20/轮（E2E-14 用）；正常周期 30min；退避基数 60s；阈值 3
        reconciler = new PrStateReconciler(subjectRepo, revisions, runs, reader, orchestrator,
                intakeService, ledger, POLICY,
                20, Duration.ofMinutes(30), Duration.ofSeconds(60), 3, 0, 0);
    }

    /** 已收敛场景（webhook 路径的等价物）：head-a 的 Run 已建，subject OPEN 且到期可查 */
    private void givenConvergedSubject(String headSha) {
        PullRequestEvent seed = new PullRequestEvent("seed-d1", "opened", 987L, 12345L, "org/repo", 7,
                "open", false, false, headSha, "main", "basesha456", T1);
        intakeService.dispatch(seed, "{\"seed\":true}".getBytes(StandardCharsets.UTF_8));
    }

    private PRSubject subject() {
        return subjectRepo.findByRepositoryAndPrNumber(12345L, 7).orElseThrow();
    }

    /** admin 把下一跳拨回过去（模拟对账周期到点；I17：拨的是 DB 时钟侧的值） */
    private void forceDue() {
        adminJdbc.sql("UPDATE pr_subject SET next_pr_reconcile_at = now() - interval '1 minute'")
                .update();
    }

    // ------------------------------------------------------------------ ST-14

    /** ST-14：webhook 丢失由 reconciler 补救——stub 直接改 head → 一轮补建 Run；第二轮幂等无重复 */
    @Test
    void st14_lostWebhookHealedByReconcilerAndSecondRoundIdempotent() {
        givenConvergedSubject("head-a");
        assertThat(count("review_run")).isEqualTo(1);

        // webhook 丢失：没有任何 inbox 事件，远端 head 已变为 head-b
        metadataPort.remote("open", false, false, "head-b", "basesha456");
        assertThat(reconciler.runOnce()).isEqualTo(1);

        // 补建：新 Run 的 trigger_key 是确定性合成值（UT-16）；旧 Run 被 T1 换届 SUPERSEDED
        assertThat(count("review_run")).isEqualTo(2);
        String activeTrigger = adminJdbc.sql("""
                SELECT trigger_key FROM review_run
                 WHERE state NOT IN ('COMPLETED','COMPLETED_WITH_WARNINGS','FAILED','CANCELLED','SUPERSEDED')
                """).query(String.class).single();
        assertThat(activeTrigger).isEqualTo("reconciler:pr-state:org/repo#7:head-b");
        // 成功回写：error_count=0，下一跳排到约 30min 后（本轮不会被重复扫到）
        PRSubject s = subject();
        assertThat(s.getPrReconcileErrorCount()).isZero();
        assertThat(s.getNextPrReconcileAt()).isAfter(Instant.now().plusSeconds(20L * 60));

        // 第二轮（周期到点）：远端仍是 head-b → 幂等收敛，无重复 Run
        forceDue();
        assertThat(reconciler.runOnce()).isEqualTo(1);
        assertThat(count("review_run")).isEqualTo(2);
        assertThat(count("outbox_command")).isZero();
    }

    // ------------------------------------------------------------------ E2E-14（降为 IT，v1.2 记录表）

    /** E2E-14：100 个待对账 PR、API 预算 LIMIT 20 → 五轮全覆盖，公平扫描不饿死尾部 */
    @Test
    void e2e14_fairScanCoversAllRowsAcrossRoundsWithoutStarvation() {
        // 100 行 OPEN 投影（next_pr_reconcile_at 吃 V3 默认值 now()，全部到期）
        for (int pr = 1; pr <= 100; pr++) {
            controlJdbc.sql("""
                            INSERT INTO pr_subject (
                                id, github_installation_id, github_repository_id, repository_full_name,
                                pr_number, state, draft, merged, current_policy_version,
                                created_at, updated_at)
                            VALUES (:id, 987, 12345, 'org/repo', :pr, 'OPEN', false, false,
                                    'm1-policy-v1', now(), now())
                            """)
                    .param("id", UUID.randomUUID())
                    .param("pr", pr)
                    .update();
        }
        metadataPort.remote("open", false, false, "head-e14", "basesha456");

        // 五轮 × 预算 20 = 全覆盖；每轮处理的行下一跳被排到 30min 后，不会重复占用预算
        for (int round = 1; round <= 5; round++) {
            assertThat(reconciler.runOnce()).isEqualTo(20);
        }
        assertThat(metadataPort.fetchCalls).isEqualTo(100);
        assertThat(count("review_run")).isEqualTo(100);
        Long covered = adminJdbc.sql(
                "SELECT count(*) FROM pr_subject WHERE next_pr_reconcile_at > now()")
                .query(Long.class).single();
        assertThat(covered).isEqualTo(100);
        // 第六轮：全部已排期到未来，零待办——证明没有死循环前 20 行
        assertThat(reconciler.runOnce()).isZero();
        assertThat(metadataPort.fetchCalls).isEqualTo(100);
    }

    // ------------------------------------------------------------------ EX-12

    /** EX-12：权威读 5xx → 不动投影 + error_count 递增；连 3 次 → ReconcilerDegraded 账本事件 */
    @Test
    void ex12_persistent5xxCountsErrorsAndAlertsAtThreshold() {
        givenConvergedSubject("head-x");
        UUID seededRunId = adminJdbc.sql("SELECT id FROM review_run").query(UUID.class).single();
        metadataPort.next = new FetchResult.Unavailable("http_500");

        for (int round = 1; round <= 3; round++) {
            forceDue();
            assertThat(reconciler.runOnce()).isEqualTo(1);
            assertThat(subject().getPrReconcileErrorCount()).isEqualTo(round);
        }

        // 不动作：投影仍 OPEN、Run 仍 active、无新 Run（5xx 不冒充事实）
        assertThat(subject().getState().name()).isEqualTo("OPEN");
        assertThat(count("review_run")).isEqualTo(1);
        // 措辞修正 #3：第 3 次失败必须告警——事件挂在该 PR 的 active Run 上
        Long degraded = adminJdbc.sql(
                "SELECT count(*) FROM execution_event WHERE event_type = 'RECONCILER_DEGRADED'")
                .query(Long.class).single();
        assertThat(degraded).isEqualTo(1);
        UUID attachRun = adminJdbc.sql(
                "SELECT review_run_id FROM execution_event WHERE event_type = 'RECONCILER_DEGRADED'")
                .query(UUID.class).single();
        assertThat(attachRun).isEqualTo(seededRunId);
    }

    // ------------------------------------------------------------------ EX-16

    /** EX-16：429 + Retry-After → 下一跳尊重 retryAfter；下一轮全局暂停、零 API（无重试风暴） */
    @Test
    void ex16_rateLimitPausesReconcilerUntilRetryAfter() {
        givenConvergedSubject("head-r");
        metadataPort.next = new FetchResult.RateLimited(Duration.ofMinutes(10));

        assertThat(reconciler.runOnce()).isEqualTo(1);

        // error_count=1；下一跳 = max(指数退避 60s, retryAfter 600s)，不早于 retryAfter
        PRSubject s = subject();
        assertThat(s.getPrReconcileErrorCount()).isEqualTo(1);
        assertThat(s.getNextPrReconcileAt()).isAfter(Instant.now().plusSeconds(590));
        assertThat(s.getState().name()).isEqualTo("OPEN"); // 不误判 closed

        // 全局暂停：紧接的第二轮零处理、零 API 调用
        int callsBefore = metadataPort.fetchCalls;
        assertThat(reconciler.runOnce()).isZero();
        assertThat(metadataPort.fetchCalls).isEqualTo(callsBefore);
    }
}
