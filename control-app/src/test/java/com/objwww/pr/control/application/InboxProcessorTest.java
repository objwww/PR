package com.objwww.pr.control.application;

import com.objwww.pr.control.domain.model.InboxState;
import com.objwww.pr.control.domain.model.WebhookInbox;
import com.objwww.pr.control.domain.port.GitHubPrMetadataPort.FetchResult;
import com.objwww.pr.control.domain.repository.WebhookInboxRepository;
import com.objwww.pr.control.interfaces.webhook.PullRequestEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * InboxProcessor 单测（mock 仓储 + mock IntakeService + mock Reader/Orchestrator）：
 * 入口路由（malformed/ignored/六 action）、T05/T06 决策分支逐一落终态、
 * 退避计算、RETRY_WAIT/DEAD_LETTER 分判、429 retryAfter 尊重（EX-16）、
 * 租约失配晚到不生效（I14）。
 */
class InboxProcessorTest {

    private static final String WORKER = "test-worker";
    private static final Duration TTL = Duration.ofMinutes(10);
    private static final Duration BASE = Duration.ofSeconds(30);
    private static final String POLICY = "m1-policy-v1";

    private WebhookInboxRepository inbox;
    private IntakeService intakeService;
    private PrEventAuthoritativeReader reader;
    private ReviewOrchestrator orchestrator;
    private InboxProcessor processor;

    @BeforeEach
    void setUp() {
        inbox = mock(WebhookInboxRepository.class);
        intakeService = mock(IntakeService.class);
        reader = mock(PrEventAuthoritativeReader.class);
        orchestrator = mock(ReviewOrchestrator.class);
        processor = new InboxProcessor(inbox, intakeService, reader, orchestrator, POLICY,
                WORKER, TTL, 10, BASE, 5, 0, 0);
    }

    private static String prPayload(String action) {
        return """
                {"action":"%s","number":7,
                 "pull_request":{"state":"open","draft":false,"merged":false,
                   "updated_at":"2025-06-01T12:00:00Z",
                   "head":{"sha":"headsha123","ref":"feature"},"base":{"sha":"basesha456","ref":"main"}},
                 "repository":{"id":12345,"full_name":"org/repo"},
                 "installation":{"id":987}}
                """.formatted(action);
    }

    private static FetchResult.Found remoteFound(boolean draft, boolean merged) {
        return new FetchResult.Found(merged ? "closed" : "open", draft, merged,
                "headsha-remote", "main", "basesha-remote", Instant.parse("2025-06-01T12:00:00Z"));
    }

    /** 领取快照：claim 之后的状态（PROCESSING + owner + epoch=1） */
    private static WebhookInbox claimed(String deliveryId, String githubEvent, int attemptCount, int maxAttempts) {
        return new WebhookInbox(deliveryId, githubEvent, null, null, null,
                "d".repeat(64), InboxState.PROCESSING, WORKER, Instant.now().plus(TTL), 1,
                attemptCount, maxAttempts, null, null, Instant.now(), Instant.now(), null);
    }

    private void givenClaimed(WebhookInbox row, byte[] raw) {
        when(inbox.claim(anyInt(), eq(WORKER), eq(TTL))).thenReturn(List.of(row));
        when(inbox.payloadRaw(row.getDeliveryId())).thenReturn(raw);
    }

    // ------------------------------------------------------------------ 入口路由（T03/T04 语义不变）

    @Test
    void malformedJsonGoesStraightToDeadLetterWithoutRetry() {
        // E2E-22：畸形 JSON（落库时 payload_json=NULL 的行）→ DEAD_LETTER(malformed)，不派不重试
        WebhookInbox row = claimed("m-1", "pull_request", 0, 5);
        givenClaimed(row, "{not-json".getBytes(StandardCharsets.UTF_8));

        assertThat(processor.runOnce()).isEqualTo(1);

        verify(inbox).completeDeadLetter(eq("m-1"), eq(WORKER), eq(1L), contains("malformed_json"));
        verify(inbox, never()).completeRetryWait(anyString(), anyString(), anyLong(), any(), anyString());
        verifyNoInteractions(reader, intakeService, orchestrator);
    }

    @Test
    void nonPullRequestEventIsIgnoredWithAuditTrail() {
        // ST-16：非处理事件 → IGNORED 留痕（INC-16 关闭），不派发
        WebhookInbox row = claimed("ig-1", "push", 0, 5);
        givenClaimed(row, "{\"action\":\"opened\"}".getBytes(StandardCharsets.UTF_8));

        processor.runOnce();

        verify(inbox).completeIgnored("ig-1", WORKER, 1L);
        verifyNoInteractions(reader, intakeService, orchestrator);
    }

    @Test
    void pullRequestActionOutsideTheSixIsIgnored() {
        // ST-16：pull_request 但 action 不在六 action（如 labeled/edited）→ IGNORED
        WebhookInbox row = claimed("ig-2", "pull_request", 0, 5);
        givenClaimed(row, prPayload("labeled").getBytes(StandardCharsets.UTF_8));

        processor.runOnce();

        verify(inbox).completeIgnored("ig-2", WORKER, 1L);
        verifyNoInteractions(reader, intakeService, orchestrator);
    }

    @Test
    void validJsonWithMissingRequiredFieldsGoesToDeadLetter() {
        // 合法 JSON 但缺必需字段：载荷不可变，重试永不成功 → 死信（E2E-22 同裁决）
        WebhookInbox row = claimed("m-2", "pull_request", 0, 5);
        givenClaimed(row, "{\"action\":\"opened\"}".getBytes(StandardCharsets.UTF_8));

        processor.runOnce();

        verify(inbox).completeDeadLetter(eq("m-2"), eq(WORKER), eq(1L), contains("malformed_payload"));
        verifyNoInteractions(reader, intakeService, orchestrator);
    }

    // ------------------------------------------------------------------ T05/T06 决策分支

    @Test
    void ignoredStaleDecisionCompletesIgnoredWithoutDispatch() {
        // ST-11（单测侧）：LWW 快筛拦截 → IGNORED，零 dispatch 零编排
        WebhookInbox row = claimed("st-1", "pull_request", 0, 5);
        givenClaimed(row, prPayload("opened").getBytes(StandardCharsets.UTF_8));
        when(reader.decide(any())).thenReturn(new PrRouteDecision.IgnoredStale());

        processor.runOnce();

        verify(inbox).completeIgnored("st-1", WORKER, 1L);
        verify(intakeService, never()).dispatch(any(), any());
        verifyNoInteractions(orchestrator);
    }

    @Test
    void idempotentDoneDecisionCompletesProcessedWithoutDispatch() {
        // ST-21 收敛点：幂等完成 → PROCESSED，零动作
        WebhookInbox row = claimed("idem-1", "pull_request", 0, 5);
        givenClaimed(row, prPayload("synchronize").getBytes(StandardCharsets.UTF_8));
        when(reader.decide(any())).thenReturn(new PrRouteDecision.IdempotentDone(UUID.randomUUID()));

        processor.runOnce();

        verify(inbox).completeProcessed("idem-1", WORKER, 1L);
        verify(intakeService, never()).dispatch(any(), any());
        verifyNoInteractions(orchestrator);
    }

    @Test
    void fullReviewDecisionDispatchesWithRemoteValues() {
        // 全量：以远端值为准重建事件（图 3-2）→ dispatch → PROCESSED
        WebhookInbox row = claimed("fr-1", "pull_request", 0, 5);
        byte[] raw = prPayload("opened").getBytes(StandardCharsets.UTF_8);
        givenClaimed(row, raw);
        when(reader.decide(any())).thenReturn(new PrRouteDecision.FullReview(remoteFound(false, false)));

        processor.runOnce();

        verify(intakeService).dispatch(argThat((PullRequestEvent e) ->
                e.deliveryId().equals("fr-1")
                        && e.headSha().equals("headsha-remote")   // 远端值，不是 payload 里的快照
                        && e.baseSha().equals("basesha-remote")
                        && e.updatedAt().equals(Instant.parse("2025-06-01T12:00:00Z"))), eq(raw));
        verify(inbox).completeProcessed("fr-1", WORKER, 1L);
    }

    @Test
    void reopenDecisionDrivesTReopenThenDispatch() {
        // T-reopen（I15/ST-20，INC-26）：先 reopenGeneration 换届，再以远端值 dispatch 全量
        WebhookInbox row = claimed("tr-1", "pull_request", 0, 5);
        byte[] raw = prPayload("reopened").getBytes(StandardCharsets.UTF_8);
        givenClaimed(row, raw);
        when(reader.decide(any())).thenReturn(new PrRouteDecision.Reopen(remoteFound(false, false)));

        processor.runOnce();

        verify(orchestrator).reopenGeneration(argThat((ProjectionSyncCommand c) ->
                c.prNumber() == 7 && !c.draft() && !c.merged()
                        && c.prState().name().equals("OPEN")));
        verify(intakeService).dispatch(argThat((PullRequestEvent e) ->
                e.deliveryId().equals("tr-1") && e.headSha().equals("headsha-remote")), eq(raw));
        verify(inbox).completeProcessed("tr-1", WORKER, 1L);
    }

    @Test
    void draftPrecheckDecisionOnlyRefreshesProjection() {
        // I11/ST-12（单测侧）：draft 廉价预检 → applyDraftPrecheck，零 dispatch（零 T0/Run/Outbox）
        WebhookInbox row = claimed("dp-1", "pull_request", 0, 5);
        givenClaimed(row, prPayload("synchronize").getBytes(StandardCharsets.UTF_8));
        when(reader.decide(any())).thenReturn(new PrRouteDecision.DraftPrecheck(remoteFound(true, false)));

        processor.runOnce();

        verify(orchestrator).applyDraftPrecheck(argThat((ProjectionSyncCommand c) ->
                c.prNumber() == 7 && c.draft() && !c.merged()
                        && c.prState().name().equals("OPEN")
                        && c.policyVersion().equals(POLICY)
                        && c.eventUpdatedAt().equals(Instant.parse("2025-06-01T12:00:00Z"))));
        verify(inbox).completeProcessed("dp-1", WORKER, 1L);
        verify(intakeService, never()).dispatch(any(), any());
    }

    @Test
    void convertToDraftDecisionDrivesTDraft() {
        // T-draft（I15）：converted_to_draft 确认 draft → convertToDraftGeneration
        WebhookInbox row = claimed("td-1", "pull_request", 0, 5);
        givenClaimed(row, prPayload("converted_to_draft").getBytes(StandardCharsets.UTF_8));
        when(reader.decide(any())).thenReturn(new PrRouteDecision.ConvertToDraft(remoteFound(true, false)));

        processor.runOnce();

        verify(orchestrator).convertToDraftGeneration(any());
        verify(inbox).completeProcessed("td-1", WORKER, 1L);
        verify(intakeService, never()).dispatch(any(), any());
    }

    @Test
    void closeDecisionDrivesTClose() {
        // T-close（I15）：远端 merged → closeGeneration
        WebhookInbox row = claimed("tc-1", "pull_request", 0, 5);
        givenClaimed(row, prPayload("closed").getBytes(StandardCharsets.UTF_8));
        when(reader.decide(any())).thenReturn(new PrRouteDecision.Close(remoteFound(false, true)));

        processor.runOnce();

        verify(orchestrator).closeGeneration(argThat((ProjectionSyncCommand c) ->
                c.prState().name().equals("CLOSED") && c.merged()));
        verify(inbox).completeProcessed("tc-1", WORKER, 1L);
        verify(intakeService, never()).dispatch(any(), any());
    }

    @Test
    void closeDecisionWithNullRemoteFallsBackToEventPayload() {
        // 404 + sanity 通过路径：remote=null → 投影用事件载荷兜底（EX-18 精神）
        WebhookInbox row = claimed("tc-2", "pull_request", 0, 5);
        givenClaimed(row, prPayload("closed").getBytes(StandardCharsets.UTF_8));
        when(reader.decide(any())).thenReturn(new PrRouteDecision.Close(null));

        processor.runOnce();

        verify(orchestrator).closeGeneration(argThat((ProjectionSyncCommand c) ->
                c.prNumber() == 7 && c.eventUpdatedAt().equals(Instant.parse("2025-06-01T12:00:00Z"))));
        verify(inbox).completeProcessed("tc-2", WORKER, 1L);
    }

    // ------------------------------------------------------------------ Retry 决策（EX-16）

    @Test
    void retryDecisionRespectsRetryAfterOverExponentialBackoff() {
        // EX-16：429 带 Retry-After=300s > 指数退避 30s → 按 300s 调度
        WebhookInbox row = claimed("rl-1", "pull_request", 0, 5);
        givenClaimed(row, prPayload("opened").getBytes(StandardCharsets.UTF_8));
        when(reader.decide(any()))
                .thenReturn(new PrRouteDecision.Retry("rate_limited", Duration.ofSeconds(300)));

        processor.runOnce();

        verify(inbox).completeRetryWait(eq("rl-1"), eq(WORKER), eq(1L),
                eq(Duration.ofSeconds(300)), contains("authoritative_read_retry"));
        verify(inbox, never()).completeDeadLetter(anyString(), anyString(), anyLong(), anyString());
        verify(intakeService, never()).dispatch(any(), any());
    }

    @Test
    void retryDecisionWithoutRetryAfterUsesExponentialBackoff() {
        // 5xx 无 Retry-After → 指数退避 base*2^0
        WebhookInbox row = claimed("rl-2", "pull_request", 0, 5);
        givenClaimed(row, prPayload("opened").getBytes(StandardCharsets.UTF_8));
        when(reader.decide(any())).thenReturn(new PrRouteDecision.Retry("unavailable:http_500", null));

        processor.runOnce();

        verify(inbox).completeRetryWait(eq("rl-2"), eq(WORKER), eq(1L),
                eq(Duration.ofSeconds(30)), contains("authoritative_read_retry"));
    }

    @Test
    void retryDecisionExhaustionGoesToDeadLetter() {
        // 权威读持续失败耗尽（attempt 4/5 → 本次第 5 次）→ DEAD_LETTER
        WebhookInbox row = claimed("rl-3", "pull_request", 4, 5);
        givenClaimed(row, prPayload("opened").getBytes(StandardCharsets.UTF_8));
        when(reader.decide(any())).thenReturn(new PrRouteDecision.Retry("forbidden", null));

        processor.runOnce();

        verify(inbox).completeDeadLetter(eq("rl-3"), eq(WORKER), eq(1L),
                contains("authoritative_read_exhausted"));
        verify(inbox, never()).completeRetryWait(anyString(), anyString(), anyLong(), any(), anyString());
    }

    // ------------------------------------------------------------------ dispatch 失败路径（EX-11/CT-16，语义不变）

    @Test
    void dispatchFailureGoesToRetryWaitWithExponentialBackoff() {
        WebhookInbox row = claimed("f-1", "pull_request", 0, 5);
        givenClaimed(row, prPayload("opened").getBytes(StandardCharsets.UTF_8));
        when(reader.decide(any())).thenReturn(new PrRouteDecision.FullReview(remoteFound(false, false)));
        doThrow(new RuntimeException("boom")).when(intakeService).dispatch(any(), any());

        processor.runOnce();

        // 第 1 次失败：attempt→1，退避 base*2^0 = 30s
        verify(inbox).completeRetryWait(eq("f-1"), eq(WORKER), eq(1L),
                eq(Duration.ofSeconds(30)), contains("dispatch_failed"));
        verify(inbox, never()).completeDeadLetter(anyString(), anyString(), anyLong(), anyString());
    }

    @Test
    void exhaustionGoesToDeadLetter() {
        // 行内 attempt_count=4（上限 5）：本次失败即第 5 次 → DEAD_LETTER（CT-16）
        WebhookInbox row = claimed("f-2", "pull_request", 4, 5);
        givenClaimed(row, prPayload("opened").getBytes(StandardCharsets.UTF_8));
        when(reader.decide(any())).thenReturn(new PrRouteDecision.FullReview(remoteFound(false, false)));
        doThrow(new RuntimeException("boom")).when(intakeService).dispatch(any(), any());

        processor.runOnce();

        verify(inbox).completeDeadLetter(eq("f-2"), eq(WORKER), eq(1L), contains("dispatch_exhausted"));
        verify(inbox, never()).completeRetryWait(anyString(), anyString(), anyLong(), any(), anyString());
    }

    @Test
    void configuredMaxAttemptsCapsRowDefault() {
        // 配置 maxAttempts=2 严于行默认 5：取小生效（行内 CHECK 是硬边界）
        processor = new InboxProcessor(inbox, intakeService, reader, orchestrator, POLICY,
                WORKER, TTL, 10, BASE, 2, 0, 0);
        WebhookInbox row = claimed("f-3", "pull_request", 1, 5);
        givenClaimed(row, prPayload("opened").getBytes(StandardCharsets.UTF_8));
        when(reader.decide(any())).thenReturn(new PrRouteDecision.FullReview(remoteFound(false, false)));
        doThrow(new RuntimeException("boom")).when(intakeService).dispatch(any(), any());

        processor.runOnce();

        verify(inbox).completeDeadLetter(eq("f-3"), eq(WORKER), eq(1L), contains("dispatch_exhausted"));
    }

    @Test
    void lateWritebackWithLostLeaseIsSwallowed() {
        // I14：回写返回 0（租约已被接管）只记日志，不抛错、不重试
        WebhookInbox row = claimed("l-1", "pull_request", 0, 5);
        givenClaimed(row, prPayload("opened").getBytes(StandardCharsets.UTF_8));
        when(reader.decide(any())).thenReturn(new PrRouteDecision.IdempotentDone(UUID.randomUUID()));
        when(inbox.completeProcessed("l-1", WORKER, 1L)).thenReturn(0);

        assertThat(processor.runOnce()).isEqualTo(1); // 不抛异常
        verify(inbox).completeProcessed("l-1", WORKER, 1L);
    }

    // ------------------------------------------------------------------ 退避计算（纯函数）

    @Test
    void backoffDoublesPerFailedAttempt() {
        assertThat(InboxProcessor.backoffForAttempt(1, BASE)).isEqualTo(Duration.ofSeconds(30));
        assertThat(InboxProcessor.backoffForAttempt(2, BASE)).isEqualTo(Duration.ofSeconds(60));
        assertThat(InboxProcessor.backoffForAttempt(3, BASE)).isEqualTo(Duration.ofSeconds(120));
        assertThat(InboxProcessor.backoffForAttempt(5, BASE)).isEqualTo(Duration.ofSeconds(480));
    }

    @Test
    void backoffRejectsNonPositiveAttemptAndCapsExponent() {
        assertThatThrownBy(() -> InboxProcessor.backoffForAttempt(0, BASE))
                .isInstanceOf(IllegalArgumentException.class);
        // 指数封顶 2^16：不溢出
        assertThat(InboxProcessor.backoffForAttempt(100, BASE))
                .isEqualTo(BASE.multipliedBy(1L << 16));
    }
}
