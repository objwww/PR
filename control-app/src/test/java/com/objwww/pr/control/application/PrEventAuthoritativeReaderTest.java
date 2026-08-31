package com.objwww.pr.control.application;

import com.objwww.pr.control.domain.model.PrSubjectState;
import com.objwww.pr.control.domain.port.GitHubPrMetadataPort;
import com.objwww.pr.control.domain.port.GitHubPrMetadataPort.FetchResult;
import com.objwww.pr.control.domain.port.GitHubPrMetadataPort.SanityResult;
import com.objwww.pr.control.interfaces.webhook.PullRequestEvent;
import com.objwww.pr.control.support.OrchestratorFixture;
import com.objwww.pr.shared.Digest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * UT-14 + UT-16（方案 §11 L1）：权威读路由判定树全组合。
 *
 * <p>UT-14（§4.4 决策表）：六 action × 权威读结果 → 决策类型全组合；
 * draft 细分（迁移事件/在途 Run → T-draft，否则廉价预检）。
 * <p>UT-16：收敛点（远端 (head,base)==投影且同策略代 active Run → 幂等完成）/
 * head 不同 → 全量 / 404 两态（sanity 通过→Close，失败→Retry）/ 403 / 429 带
 * retryAfter / 5xx；LWW 快筛拦截零 API（计数 fake 断言零调用，ST-11 单测侧）。
 */
class PrEventAuthoritativeReaderTest {

    private static final String POLICY = "m1-policy-v1";
    private static final Instant T1 = Instant.parse("2025-06-01T12:00:00Z");
    private static final Instant T2 = Instant.parse("2025-06-01T12:00:01Z");

    /** 计数 fake port：可编程的权威读应答 + 调用计数（ST-11 零 API 断言用） */
    private static final class FakeMetadataPort implements GitHubPrMetadataPort {
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
    private FakeMetadataPort port;
    private PrEventAuthoritativeReader reader;

    @BeforeEach
    void setUp() {
        fx = new OrchestratorFixture();
        port = new FakeMetadataPort();
        reader = new PrEventAuthoritativeReader(fx.subjects, fx.revisions, fx.runs, port, POLICY);
    }

    private static FetchResult.Found found(String state, boolean draft, boolean merged,
                                           String headSha, String baseSha) {
        return new FetchResult.Found(state, draft, merged, headSha, "main", baseSha, T2);
    }

    private static PullRequestEvent event(String action, String headSha, Instant updatedAt) {
        return new PullRequestEvent("d-1", action, 987L, 12345L, "org/repo", 7,
                "open", false, false, headSha, "main", "basesha456", updatedAt);
    }

    /** 已收敛场景：T1 建过 (head1, base1) 的 Run（policy=POLICY，active） */
    private void givenConvergedSubject() {
        fx.orchestrator.runIntake(new IntakeCommand(987L, 12345L, "org/repo", 7,
                PrSubjectState.OPEN, false, false, "head1", "main", "base1", null,
                Digest.sha256Of("diff-head1"), Digest.sha256Of("snap-head1"),
                POLICY, "m1-prompt-v1", "m1-toolset-v1", "seed-1", null));
    }

    // ------------------------------------------------------------------ LWW 快筛（UT-12 链路侧 / ST-11）

    @Test
    void staleEventIsIgnoredWithZeroApiCall() {
        givenConvergedSubject();
        fx.subjects.advanceWatermarkIfNewer(
                fx.subjects.findByRepositoryAndPrNumber(12345L, 7).orElseThrow().getId(), T2, T2);
        port.nextFetch = found("open", false, false, "head1", "base1");

        // 事件 updated_at=T1 < 水印 T2 → IgnoredStale；权威读零调用（ST-11 核心：省钱的那层）
        PrRouteDecision decision = reader.decide(event("synchronize", "head-old", T1));

        assertThat(decision).isInstanceOf(PrRouteDecision.IgnoredStale.class);
        assertThat(port.fetchCalls).isZero();
        assertThat(port.sanityCalls).isZero();
    }

    @Test
    void equalWatermarkPassesScreenAndCallsApi() {
        givenConvergedSubject();
        fx.subjects.advanceWatermarkIfNewer(
                fx.subjects.findByRepositoryAndPrNumber(12345L, 7).orElseThrow().getId(), T2, T2);
        port.nextFetch = found("open", false, false, "head1", "base1");

        // 等于水印 → 放行（同秒不误杀）；经权威读收敛为幂等完成
        PrRouteDecision decision = reader.decide(event("synchronize", "head1", T2));

        assertThat(port.fetchCalls).isEqualTo(1);
        assertThat(decision).isInstanceOf(PrRouteDecision.IdempotentDone.class);
    }

    @Test
    void missingEventTimestampGoesStraightToAuthoritativeRead() {
        // EX-18：缺 updated_at 不做任何投影判断，直接权威读
        PrRouteDecision decision = reader.decide(event("opened", "head1", null));

        assertThat(port.fetchCalls).isEqualTo(1);
        assertThat(decision).isInstanceOf(PrRouteDecision.FullReview.class);
    }

    // ------------------------------------------------------------------ UT-16 收敛点与全量

    @Test
    void convergedRemoteYieldsIdempotentDone() {
        // UT-16/ST-21 收敛点：远端 (head,base)==投影 current revision 且同策略代 active Run
        givenConvergedSubject();
        port.nextFetch = found("open", false, false, "head1", "base1");

        PrRouteDecision decision = reader.decide(event("synchronize", "head1", T2));

        assertThat(decision).isInstanceOf(PrRouteDecision.IdempotentDone.class);
    }

    @Test
    void differentHeadYieldsFullReview() {
        givenConvergedSubject();
        port.nextFetch = found("open", false, false, "head2", "base1");

        assertThat(reader.decide(event("synchronize", "head2", T2)))
                .isInstanceOf(PrRouteDecision.FullReview.class);
    }

    @Test
    void differentBaseWithSameHeadYieldsFullReview() {
        // E2E-10：比对的是 (head, base) 二元组——base 变 head 不变也必须全量（新 Revision）
        givenConvergedSubject();
        port.nextFetch = found("open", false, false, "head1", "base2");

        PrRouteDecision decision = reader.decide(event("synchronize", "head1", T2));

        assertThat(decision).isInstanceOf(PrRouteDecision.FullReview.class);
        assertThat(((PrRouteDecision.FullReview) decision).remote().baseSha()).isEqualTo("base2");
    }

    @Test
    void convergedRevisionButDifferentPolicyGenerationYieldsFullReview() {
        // E2E-09 前提：revision 收敛但应用策略代已换（部署配置）→ 不幂等，走全量
        // （T1 内部复用 Revision + epoch+1）
        givenConvergedSubject();
        reader = new PrEventAuthoritativeReader(fx.subjects, fx.revisions, fx.runs, port, "m1-policy-v2");
        port.nextFetch = found("open", false, false, "head1", "base1");

        assertThat(reader.decide(event("synchronize", "head1", T2)))
                .isInstanceOf(PrRouteDecision.FullReview.class);
    }

    @Test
    void unknownSubjectWithOpenNonDraftRemoteYieldsFullReview() {
        port.nextFetch = found("open", false, false, "head1", "base1");

        assertThat(reader.decide(event("opened", "head1", T2)))
                .isInstanceOf(PrRouteDecision.FullReview.class);
    }

    // ------------------------------------------------------------------ UT-14：draft 决策表

    @Test
    void openDraftFromSynchronizeWithoutActiveRunYieldsDraftPrecheck() {
        // §4.4 第一行：opened/synchronize/reopened/ready_for_review + 远端 draft → 廉价预检
        port.nextFetch = found("open", true, false, "head1", "base1");

        assertThat(reader.decide(event("opened", "head1", T2)))
                .isInstanceOf(PrRouteDecision.DraftPrecheck.class);
        assertThat(reader.decide(event("synchronize", "head1", T2)))
                .isInstanceOf(PrRouteDecision.DraftPrecheck.class);
        assertThat(reader.decide(event("reopened", "head1", T2)))
                .isInstanceOf(PrRouteDecision.DraftPrecheck.class);
        assertThat(reader.decide(event("ready_for_review", "head1", T2)))
                .isInstanceOf(PrRouteDecision.DraftPrecheck.class);
    }

    @Test
    void convertedToDraftEventYieldsTDraft() {
        // §4.4：converted_to_draft 确认 draft=true → T-draft（epoch+1），即使无在途 Run
        port.nextFetch = found("open", true, false, "head1", "base1");

        assertThat(reader.decide(event("converted_to_draft", "head1", T2)))
                .isInstanceOf(PrRouteDecision.ConvertToDraft.class);
    }

    @Test
    void draftRemoteWithActiveRunStillOnFlightYieldsTDraft() {
        // I15 防御补齐：converted_to_draft 的 webhook 丢了，后续 synchronize 才发现 draft——
        // 在途 Run 的旧 epoch 命令必须被 fence，升级为 T-draft 而非廉价预检
        givenConvergedSubject();
        port.nextFetch = found("open", true, false, "head1", "base1");

        assertThat(reader.decide(event("synchronize", "head1", T2)))
                .isInstanceOf(PrRouteDecision.ConvertToDraft.class);
    }

    @Test
    void readyForReviewWithNonDraftRemoteYieldsFullReview() {
        // §4.4：ready_for_review 按权威读结果走（draft=false → 全量）
        port.nextFetch = found("open", false, false, "head1", "base1");

        assertThat(reader.decide(event("ready_for_review", "head1", T2)))
                .isInstanceOf(PrRouteDecision.FullReview.class);
    }

    @Test
    void closedOrMergedRemoteYieldsCloseRegardlessOfAction() {
        // 远端 closed/merged → T-close；以远端为准，哪怕事件是 synchronize（图 3-2 原则）
        port.nextFetch = found("closed", false, true, "head1", "base1");
        assertThat(reader.decide(event("closed", "head1", T2)))
                .isInstanceOf(PrRouteDecision.Close.class);
        assertThat(reader.decide(event("synchronize", "head1", T2)))
                .isInstanceOf(PrRouteDecision.Close.class);

        port.nextFetch = found("closed", false, false, "head1", "base1");
        assertThat(reader.decide(event("closed", "head1", T2)))
                .isInstanceOf(PrRouteDecision.Close.class);
    }

    @Test
    void reopenedWithUnchangedCodeYieldsReopen() {
        // ST-20：reopened 即使代码未变也强制换届（换届是状态语义，不是 diff 语义）——
        // closed 后投影 CLOSED，走 T-reopen（epoch+1 + 新 Run），不是普通 FullReview（INC-26）
        givenConvergedSubject();
        fx.orchestrator.closeGeneration(new ProjectionSyncCommand(987L, 12345L, "org/repo", 7,
                PrSubjectState.CLOSED, false, false, POLICY, T1));
        port.nextFetch = found("open", false, false, "head1", "base1");

        assertThat(reader.decide(event("reopened", "head1", T2)))
                .isInstanceOf(PrRouteDecision.Reopen.class);
    }

    // ------------------------------------------------------------------ 404 两态（EX-17/E2E-18 精神）

    @Test
    void notFoundWithReadableRepoYieldsClose() {
        // sanity 读通过（repo 可读 = token/权限/仓库皆正常）→ PR 真没了，按关处理
        port.nextFetch = new FetchResult.NotFound();
        port.nextSanity = SanityResult.READABLE;

        PrRouteDecision decision = reader.decide(event("closed", "head1", T2));

        assertThat(port.sanityCalls).isEqualTo(1);
        assertThat(decision).isInstanceOf(PrRouteDecision.Close.class);
        assertThat(((PrRouteDecision.Close) decision).remote()).isNull();
    }

    @Test
    void notFoundWithUnreadableRepoYieldsRetry() {
        // sanity 失败 = 权限/可用性异常 → RETRY，绝不冒充"PR 不存在"（EX-17 精神）
        port.nextFetch = new FetchResult.NotFound();
        port.nextSanity = SanityResult.UNREADABLE;

        PrRouteDecision decision = reader.decide(event("closed", "head1", T2));

        assertThat(decision).isInstanceOf(PrRouteDecision.Retry.class);
        assertThat(((PrRouteDecision.Retry) decision).reason()).isEqualTo("not_found_sanity_failed");
    }

    // ------------------------------------------------------------------ 403/429/5xx（EX-16）

    @Test
    void forbiddenYieldsRetryWithoutBackoff() {
        port.nextFetch = new FetchResult.Forbidden();

        PrRouteDecision decision = reader.decide(event("opened", "head1", T2));

        assertThat(decision).isEqualTo(new PrRouteDecision.Retry("forbidden", null));
    }

    @Test
    void rateLimitedYieldsRetryCarryingRetryAfter() {
        // EX-16：429 的 Retry-After 必须透传到 RETRY_WAIT 调度
        port.nextFetch = new FetchResult.RateLimited(Duration.ofSeconds(120));

        PrRouteDecision decision = reader.decide(event("opened", "head1", T2));

        assertThat(decision).isEqualTo(new PrRouteDecision.Retry("rate_limited", Duration.ofSeconds(120)));
    }

    @Test
    void unavailableYieldsRetry() {
        port.nextFetch = new FetchResult.Unavailable("http_502");

        PrRouteDecision decision = reader.decide(event("opened", "head1", T2));

        assertThat(decision).isInstanceOf(PrRouteDecision.Retry.class);
        assertThat(((PrRouteDecision.Retry) decision).reason()).contains("http_502");
        assertThat(((PrRouteDecision.Retry) decision).retryAfter()).isNull();
    }
}
