package com.objwww.pr.control.application;

import com.objwww.pr.control.domain.model.PRSubject;
import com.objwww.pr.control.domain.model.PrSubjectState;
import com.objwww.pr.control.domain.model.ReviewRun;
import com.objwww.pr.control.domain.port.GitHubPrMetadataPort;
import com.objwww.pr.control.domain.port.GitHubPrMetadataPort.FetchResult;
import com.objwww.pr.control.domain.port.GitHubPrMetadataPort.SanityResult;
import com.objwww.pr.control.support.OrchestratorFixture;
import com.objwww.pr.shared.Digest;
import com.objwww.pr.shared.ExecutionEventType;
import com.objwww.pr.shared.RunState;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * PrStateReconciler 单测（M1-T07，方案 §4.5；内存假实现 + 可编程权威读 stub）：
 *
 * <ul>
 *   <li>UT-16 reconciler 部分：trigger_key = reconciler:pr-state:{repo}#{pr}:{headSha} 确定性；</li>
 *   <li>ST-14 单测侧：head 漂移一轮补建 Run（合成 intake 走全量 T0/T1），第二轮幂等无重复；</li>
 *   <li>退避序列（§4.5）：error_count 1/2/3 → 退避 base×1/2/4；</li>
 *   <li>ReconcilerDegraded 阈值（措辞修正 #3/EX-12）：连 3 次 → 账本事件挂 active Run；
 *       无 Run 可挂 → 日志代账不炸；</li>
 *   <li>EX-16：429 的 retryAfter 与退避取大 + 下一轮全局暂停（零 API）；</li>
 *   <li>404 两态（EX-17 精神）：sanity 失败 → 不动投影只计数；sanity 通过 → T-close；</li>
 *   <li>公平扫描排序（E2E-14 可纯单测部分）：OPEN+到点、按 next 升序、LIMIT 截断。</li>
 * </ul>
 */
class PrStateReconcilerTest {

    private static final String POLICY = "m1-policy-v1";
    private static final Duration INTERVAL = Duration.ofMinutes(30);
    private static final Duration BACKOFF_BASE = Duration.ofSeconds(60);
    private static final Instant T1 = Instant.parse("2025-06-01T12:00:00Z");

    /** 可编程权威读 stub（与 PrEventAuthoritativeReaderTest 同形态 + 调用计数） */
    private static final class StubMetadataPort implements GitHubPrMetadataPort {
        FetchResult nextFetch = found("open", false, false, "head1", "base1");
        SanityResult nextSanity = SanityResult.READABLE;
        int fetchCalls;
        int sanityCalls;

        @Override
        public FetchResult fetchPullRequest(long installationId, String repoFullName, int prNumber) {
            fetchCalls++;
            return nextFetch;
        }

        @Override
        public SanityResult checkRepoReadable(long installationId, String repoFullName) {
            sanityCalls++;
            return nextSanity;
        }
    }

    private OrchestratorFixture fx;
    private StubMetadataPort port;
    private PrStateReconciler reconciler;

    @BeforeEach
    void setUp() {
        fx = new OrchestratorFixture();
        port = new StubMetadataPort();
        SnapshotService snapshotService = mock(SnapshotService.class);
        // digest 由 (base,head) 派生：base 变化也产生新 diff digest（E2E-10 新 Revision 的前提）
        when(snapshotService.prepare(anyLong(), anyString(), anyString(), anyString()))
                .thenAnswer(inv -> new SnapshotService.SnapshotOutcome(
                        Digest.sha256Of("snap-" + inv.getArgument(3)),
                        Digest.sha256Of("diff-" + inv.getArgument(2) + "-" + inv.getArgument(3)),
                        3, 100));
        IntakeService intake = new IntakeService(snapshotService, fx.orchestrator, fx.cas, fx.artifacts,
                POLICY, "m1-prompt-v1", "m1-toolset-v1");
        PrEventAuthoritativeReader reader = new PrEventAuthoritativeReader(
                fx.subjects, fx.revisions, fx.runs, port, POLICY);
        reconciler = new PrStateReconciler(fx.subjects, fx.revisions, fx.runs, reader,
                fx.orchestrator, intake, fx.ledger, POLICY,
                20, INTERVAL, BACKOFF_BASE, 3, 0, 0);
    }

    private static FetchResult.Found found(String state, boolean draft, boolean merged,
                                           String headSha, String baseSha) {
        return new FetchResult.Found(state, draft, merged, headSha, "main", baseSha, T1);
    }

    /** 已收敛场景：head1/base1 的 Run 已建（policy=POLICY，active），subject OPEN */
    private void givenConvergedSubject() {
        fx.orchestrator.runIntake(new IntakeCommand(987L, 12345L, "org/repo", 7,
                PrSubjectState.OPEN, false, false, "head1", "main", "base1", null,
                Digest.sha256Of("diff-base1-head1"), Digest.sha256Of("snap-head1"),
                POLICY, "m1-prompt-v1", "m1-toolset-v1", "seed-delivery-1", null));
    }

    private PRSubject subject() {
        return fx.subjects.findByRepositoryAndPrNumber(12345L, 7).orElseThrow();
    }

    /** 把下一跳拨回过去（模拟对账周期到点），保留失败计数 */
    private void forceDue(UUID id) {
        PRSubject s = fx.subjects.findById(id).orElseThrow();
        fx.subjects.save(new PRSubject(s.getId(), s.getGithubInstallationId(), s.getGithubRepositoryId(),
                s.getRepositoryFullName(), s.getPrNumber(), s.getState(), s.isDraft(), s.isMerged(),
                s.getCurrentRevisionId(), s.getCurrentPolicyVersion(),
                s.getPublicationEpoch(), s.getNextOutboxSequence(), s.getLastResolvedSequence(),
                s.getLastEventUpdatedAt(),
                Instant.now().minusSeconds(3600), s.getPrReconcileErrorCount(),
                s.getVersion(), s.getCreatedAt(), s.getUpdatedAt()));
    }

    // ------------------------------------------------------------------ UT-16：trigger_key 确定性

    @Test
    void syntheticTriggerKeyIsDeterministic() {
        // UT-16 reconciler 部分：同 (repo, pr, headSha) 恒同 key；格式即契约
        assertThat(PrStateReconciler.syntheticTriggerKey("org/repo", 7, "head-x"))
                .isEqualTo("reconciler:pr-state:org/repo#7:head-x");
        assertThat(PrStateReconciler.syntheticTriggerKey("org/repo", 7, "head-x"))
                .isEqualTo(PrStateReconciler.syntheticTriggerKey("org/repo", 7, "head-x"));
        assertThat(PrStateReconciler.syntheticTriggerKey("org/repo", 7, "head-y"))
                .isNotEqualTo(PrStateReconciler.syntheticTriggerKey("org/repo", 7, "head-x"));
        assertThat(PrStateReconciler.syntheticTriggerKey("org/repo", 8, "head-x"))
                .isNotEqualTo(PrStateReconciler.syntheticTriggerKey("org/repo", 7, "head-x"));
    }

    // ------------------------------------------------------------------ ST-14 单测侧：漂移补建 + 二轮幂等

    @Test
    void headDriftSynthesizesIntakeAndSecondRoundIsIdempotent() {
        givenConvergedSubject();
        assertThat(subject().getPrReconcileErrorCount()).isZero();
        port.nextFetch = found("open", false, false, "head2", "base1"); // webhook 丢了，远端已 head2

        assertThat(reconciler.runOnce()).isEqualTo(1);

        // 一轮补建：新 Run 的 trigger_key 是确定性合成值；旧 Run SUPERSEDED（T1 换届语义）
        List<ReviewRun> active = fx.runs.findActiveByPrSubjectId(subject().getId());
        assertThat(active).hasSize(1);
        assertThat(active.get(0).getTriggerKey())
                .isEqualTo("reconciler:pr-state:org/repo#7:head2");
        assertThat(fx.runs.findLatestByPrSubjectId(subject().getId())).isPresent();
        // 成功：error_count=0，下一跳 ≈ now+30min
        PRSubject after = subject();
        assertThat(after.getPrReconcileErrorCount()).isZero();
        assertThat(after.getNextPrReconcileAt())
                .isBetween(Instant.now().plusSeconds(29L * 60), Instant.now().plusSeconds(31L * 60));

        // 第二轮（周期到点）：远端仍是 head2 → 幂等收敛，无重复 Run
        forceDue(after.getId());
        assertThat(reconciler.runOnce()).isEqualTo(1);
        assertThat(fx.runs.findActiveByPrSubjectId(after.getId())).hasSize(1);
        assertThat(fx.events.all().stream()
                .filter(e -> e.eventType() == ExecutionEventType.RUN_CREATED)).hasSize(2); // 仍是首轮那两个
    }

    @Test
    void baseDriftWithSameHeadSynthesizesIntake() {
        // E2E-10：base 变 head 不变也必须补建（二元组比对）
        givenConvergedSubject();
        port.nextFetch = found("open", false, false, "head1", "base2");

        assertThat(reconciler.runOnce()).isEqualTo(1);

        List<ReviewRun> active = fx.runs.findActiveByPrSubjectId(subject().getId());
        assertThat(active).hasSize(1);
        assertThat(fx.revisions.findById(active.get(0).getPrRevisionId()).orElseThrow().getBaseSha())
                .isEqualTo("base2");
    }

    @Test
    void noDriftJustReschedules() {
        // 无漂移：零新 Run，只 markReconciled
        givenConvergedSubject();
        port.nextFetch = found("open", false, false, "head1", "base1");

        assertThat(reconciler.runOnce()).isEqualTo(1);

        assertThat(fx.runs.findActiveByPrSubjectId(subject().getId())).hasSize(1); // 还是原来那个
        assertThat(fx.events.all().stream()
                .filter(e -> e.eventType() == ExecutionEventType.RUN_CREATED)).hasSize(1);
        assertThat(subject().getPrReconcileErrorCount()).isZero();
    }

    // ------------------------------------------------------------------ 状态漂移：T-close / draft

    @Test
    void closedRemoteClosesGenerationWithEpochBump() {
        givenConvergedSubject();
        port.nextFetch = found("closed", false, true, "head1", "base1");

        assertThat(reconciler.runOnce()).isEqualTo(1);

        PRSubject s = subject();
        assertThat(s.getState()).isEqualTo(PrSubjectState.CLOSED);
        assertThat(s.isMerged()).isTrue();
        assertThat(s.getPublicationEpoch()).isEqualTo(2); // I15：closed 必递增
        assertThat(fx.runs.findActiveByPrSubjectId(s.getId())).isEmpty(); // 在途 Run SUPERSEDED
    }

    @Test
    void draftRemoteWithoutActiveRunIsCheapPrecheckOnly() {
        // draft 期从未建 Run 的投影（applyDraftPrecheck 建的行）；远端仍 draft → 只刷投影
        fx.orchestrator.applyDraftPrecheck(new ProjectionSyncCommand(987L, 12345L, "org/repo", 7,
                PrSubjectState.OPEN, true, false, POLICY, T1));
        port.nextFetch = found("open", true, false, "head-d", "base1");

        assertThat(reconciler.runOnce()).isEqualTo(1);

        assertThat(fx.runs.findLatestByPrSubjectId(subject().getId())).isEmpty(); // I11：零 Run
        assertThat(subject().isDraft()).isTrue();
        assertThat(subject().getPrReconcileErrorCount()).isZero();
    }

    @Test
    void draftRemoteWithActiveRunStillOnFlightConvertsToDraft() {
        // converted_to_draft 的 webhook 丢了：远端已 draft 但在途 Run 仍在 → T-draft 换届
        givenConvergedSubject();
        port.nextFetch = found("open", true, false, "head1", "base1");

        assertThat(reconciler.runOnce()).isEqualTo(1);

        PRSubject s = subject();
        assertThat(s.isDraft()).isTrue();
        assertThat(s.getPublicationEpoch()).isEqualTo(2);
        assertThat(fx.runs.findActiveByPrSubjectId(s.getId())).isEmpty();
    }

    // ------------------------------------------------------------------ 退避序列 + ReconcilerDegraded 阈值（EX-12）

    @Test
    void consecutiveFailuresBackoffExponentiallyAndAlertAtThreshold() {
        givenConvergedSubject();
        port.nextFetch = new FetchResult.Unavailable("http_500");

        // 第 1 次失败：error_count=1，退避 base×1=60s，无告警
        assertThat(reconciler.runOnce()).isEqualTo(1);
        PRSubject s1 = subject();
        assertThat(s1.getPrReconcileErrorCount()).isEqualTo(1);
        assertThat(s1.getNextPrReconcileAt())
                .isBetween(Instant.now().plusSeconds(55), Instant.now().plusSeconds(65));
        assertThat(degradedEvents()).isZero();

        // 第 2 次：error_count=2，退避 base×2=120s，无告警
        forceDue(s1.getId());
        reconciler.runOnce();
        PRSubject s2 = subject();
        assertThat(s2.getPrReconcileErrorCount()).isEqualTo(2);
        assertThat(s2.getNextPrReconcileAt())
                .isBetween(Instant.now().plusSeconds(115), Instant.now().plusSeconds(125));
        assertThat(degradedEvents()).isZero();

        // 第 3 次：error_count=3 ≥ 阈值 → ReconcilerDegraded 落账，挂在 active Run 上
        forceDue(s2.getId());
        reconciler.runOnce();
        assertThat(subject().getPrReconcileErrorCount()).isEqualTo(3);
        assertThat(degradedEvents()).isEqualTo(1);
        var event = fx.events.all().stream()
                .filter(e -> e.eventType() == ExecutionEventType.RECONCILER_DEGRADED).findFirst().orElseThrow();
        ReviewRun activeRun = fx.runs.findActiveByPrSubjectId(subject().getId()).get(0);
        assertThat(event.reviewRunId()).isEqualTo(activeRun.getId());
        assertThat(event.prRevisionId()).isEqualTo(activeRun.getPrRevisionId());
        assertThat(event.payload().get("reconciler")).isEqualTo("pr-state");
        assertThat(event.payload().get("error_count")).isEqualTo(3);

        // 投影未被失败污染：state 仍 OPEN、Run 仍 active（EX-12：不动作）
        assertThat(subject().getState()).isEqualTo(PrSubjectState.OPEN);
    }

    @Test
    void degradedWithoutAnyRunFallsBackToStructuredLog() {
        // 该 PR 从未有过 Run（纯 draft 期）：execution_event 无合法挂载点 → 日志代账，不炸不记
        fx.orchestrator.applyDraftPrecheck(new ProjectionSyncCommand(987L, 12345L, "org/repo", 7,
                PrSubjectState.OPEN, true, false, POLICY, T1));
        port.nextFetch = new FetchResult.Unavailable("http_500");

        for (int i = 0; i < 3; i++) {
            forceDue(subject().getId());
            reconciler.runOnce();
        }

        assertThat(subject().getPrReconcileErrorCount()).isEqualTo(3);
        assertThat(degradedEvents()).isZero(); // 无 Run 可挂，账本不落（已知偏差，报告明示）
    }

    private long degradedEvents() {
        return fx.events.all().stream()
                .filter(e -> e.eventType() == ExecutionEventType.RECONCILER_DEGRADED).count();
    }

    // ------------------------------------------------------------------ EX-16：429 retryAfter

    @Test
    void rateLimitedRespectsRetryAfterAndPausesNextRoundGlobally() {
        givenConvergedSubject();
        port.nextFetch = new FetchResult.RateLimited(Duration.ofMinutes(10));

        assertThat(reconciler.runOnce()).isEqualTo(1);

        // error_count=1；下一跳取 max(退避 60s, retryAfter 600s) = 600s
        PRSubject s = subject();
        assertThat(s.getPrReconcileErrorCount()).isEqualTo(1);
        assertThat(s.getNextPrReconcileAt())
                .isAfter(Instant.now().plusSeconds(590));

        // 全局暂停：第二轮零处理零 API（EX-16：无重试风暴）
        int callsBefore = port.fetchCalls;
        assertThat(reconciler.runOnce()).isZero();
        assertThat(port.fetchCalls).isEqualTo(callsBefore);
    }

    // ------------------------------------------------------------------ 404 两态（EX-17 精神）

    @Test
    void notFoundWithUnreadableRepoCountsErrorWithoutTouchingProjection() {
        givenConvergedSubject();
        port.nextFetch = new FetchResult.NotFound();
        port.nextSanity = SanityResult.UNREADABLE;

        assertThat(reconciler.runOnce()).isEqualTo(1);

        // 权限异常不冒充事实：投影不动（仍 OPEN、Run 仍 active），只 error 计数 + 退避
        PRSubject s = subject();
        assertThat(s.getState()).isEqualTo(PrSubjectState.OPEN);
        assertThat(s.getPrReconcileErrorCount()).isEqualTo(1);
        assertThat(fx.runs.findActiveByPrSubjectId(s.getId())).hasSize(1);
    }

    @Test
    void notFoundWithReadableRepoClosesGeneration() {
        givenConvergedSubject();
        port.nextFetch = new FetchResult.NotFound();
        port.nextSanity = SanityResult.READABLE;

        assertThat(reconciler.runOnce()).isEqualTo(1);

        // sanity 通过 = PR 真没了 → 按关处理（T-close）
        PRSubject s = subject();
        assertThat(s.getState()).isEqualTo(PrSubjectState.CLOSED);
        assertThat(s.getPublicationEpoch()).isEqualTo(2);
        assertThat(fx.runs.findActiveByPrSubjectId(s.getId())).isEmpty();
    }

    // ------------------------------------------------------------------ 公平扫描排序（E2E-14 纯单测部分）

    @Test
    void fairScanOrdersOldestFirstAndHonoursLimit() {
        Instant now = Instant.now();
        saveSubject(1001L, 1, PrSubjectState.OPEN, now.minusSeconds(3600), 0);  // 最久未查
        saveSubject(1001L, 2, PrSubjectState.OPEN, now.minusSeconds(60), 0);
        saveSubject(1001L, 3, PrSubjectState.OPEN, now.minusSeconds(1800), 0);
        saveSubject(1001L, 4, PrSubjectState.OPEN, now.plusSeconds(3600), 0);   // 未到点
        saveSubject(1001L, 5, PrSubjectState.CLOSED, now.minusSeconds(7200), 0); // CLOSED 不扫

        List<PRSubject> due = fx.subjects.findDueForReconcile(2);

        // 最久未查的先查，LIMIT 截断；未到点与 CLOSED 被排除
        assertThat(due).extracting(PRSubject::getPrNumber).containsExactly(1, 3);
        assertThat(fx.subjects.findDueForReconcile(20))
                .extracting(PRSubject::getPrNumber).containsExactly(1, 3, 2);
    }

    private void saveSubject(long repoId, int prNumber, PrSubjectState state,
                             Instant nextReconcileAt, int errorCount) {
        Instant now = Instant.now();
        fx.subjects.save(new PRSubject(UUID.randomUUID(), 987L, repoId, "org/repo", prNumber,
                state, false, false, null, POLICY, 0, 1, 0, null,
                nextReconcileAt, errorCount, 0, now, now));
    }
}
