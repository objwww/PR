package com.objwww.pr.publisher.domain.service;

import com.objwww.pr.publisher.domain.handler.CreateCheckHandler;
import com.objwww.pr.publisher.domain.handler.PublicationHandler;
import com.objwww.pr.publisher.domain.handler.PublishReviewHandler;
import com.objwww.pr.publisher.domain.handler.ReconcileVerdict;
import com.objwww.pr.publisher.domain.handler.UpdateCheckHandler;
import com.objwww.pr.publisher.domain.model.ClaimedCommand;
import com.objwww.pr.publisher.fakes.FakePayloadReader;
import com.objwww.pr.publisher.fakes.FakePublicationStore;
import com.objwww.pr.publisher.fakes.StubGitHubWriteAdapter;
import com.objwww.pr.publisher.fakes.TestFixtures;
import com.objwww.pr.shared.CommandType;
import com.objwww.pr.shared.ExecutionEventType;
import com.objwww.pr.shared.GitHubOperation;
import com.objwww.pr.shared.OutboxState;
import com.objwww.pr.shared.PublicationResourceType;
import com.objwww.pr.shared.TypedResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * T3 主链路 + reconcile 探测（fake 端口内存测；PG 原子性属 T17 Testcontainers）。
 */
class FencedPublicationExecutorTest {

    private FakePublicationStore store;
    private FakePayloadReader payloadReader;
    private StubGitHubWriteAdapter github;
    private FencedPublicationExecutor executor;
    private final List<PublicationHandler> handlers = List.of(
            new CreateCheckHandler(), new UpdateCheckHandler(), new PublishReviewHandler());

    @BeforeEach
    void setUp() {
        store = new FakePublicationStore();
        payloadReader = new FakePayloadReader();
        github = new StubGitHubWriteAdapter();
        executor = new FencedPublicationExecutor(github, store, payloadReader, handlers,
                Duration.ofSeconds(60), 3, TestFixtures.INSTALLATION_ID);
    }

    private ClaimedCommand pendingCheck() {
        ClaimedCommand cmd = TestFixtures.command(CommandType.CREATE_CHECK, 1, 1,
                OutboxState.PENDING, 0, 3);
        store.put(cmd);
        payloadReader.put(cmd.payloadHash(), TestFixtures.checkPayload(cmd));
        return cmd;
    }

    // ---------- 主闭环 ----------

    @Test
    void happyPathConfirmsAndAdvancesCursor() {
        ClaimedCommand cmd = pendingCheck();
        github.respondWrite(TypedResponse.ofObject(201, Map.of("id", 12345, "html_url", "http://x/1")));

        PublishOutcome outcome = executor.execute(cmd);

        assertEquals(PublishOutcome.CONFIRMED, outcome);
        assertEquals(OutboxState.CONFIRMED, store.stateOf(cmd).state());
        assertEquals("12345", store.confirmedRemoteIds.get(cmd.operationId().value()));
        assertEquals(PublicationResourceType.CHECK_RUN, store.resources.get(cmd.operationId().value()));
        assertEquals(1, store.cursor.lastResolvedSequence()); // 游标同事务推进（评审修正 #5）
        // 请求装配：external_id = operation_id（§6.3）
        assertEquals(GitHubOperation.CREATE_CHECK_RUN, github.writeRequests.get(0).operation());
        assertEquals(cmd.operationId().toString(),
                github.writeRequests.get(0).parameters().get("external_id"));
        // PUBLICATION_CONFIRMED 落账
        assertTrue(store.events.stream().anyMatch(
                e -> e.eventType() == ExecutionEventType.PUBLICATION_CONFIRMED));
    }

    @Test
    void transportLossGoesReconcilingNeverBlindRetry() {
        // EX-03/§4.3：响应丢失 → RECONCILING + PUBLICATION_OUTCOME_UNKNOWN，禁盲目重发
        ClaimedCommand cmd = pendingCheck();
        github.failTransport(true);

        PublishOutcome outcome = executor.execute(cmd);

        assertEquals(PublishOutcome.RECONCILING, outcome);
        assertEquals(OutboxState.RECONCILING, store.stateOf(cmd).state());
        assertTrue(store.events.stream().anyMatch(
                e -> e.eventType() == ExecutionEventType.PUBLICATION_OUTCOME_UNKNOWN));
    }

    @Test
    void serverErrorRetriesWithBackoffThenManualAtBudget() {
        // EX-01：5xx → RETRY_WAIT + attempt+1；达上限 → MANUAL 且游标不动
        ClaimedCommand cmd = pendingCheck();
        github.respondWrite(TypedResponse.ofStatus(500));

        assertEquals(PublishOutcome.RETRY_WAIT, executor.execute(cmd));
        assertEquals(OutboxState.RETRY_WAIT, store.stateOf(cmd).state());
        assertEquals(1, store.stateOf(cmd).attemptCount());
        assertEquals(0, store.cursor.lastResolvedSequence());

        ClaimedCommand retry = TestFixtures.withState(
                TestFixtures.withAttempts(cmd, 2), OutboxState.PENDING); // 已到 budget 边缘
        store.put(retry);
        github.respondWrite(TypedResponse.ofStatus(503));
        assertEquals(PublishOutcome.MANUAL, executor.execute(retry));
        assertEquals(OutboxState.MANUAL, store.stateOf(cmd).state());
        assertEquals(0, store.cursor.lastResolvedSequence()); // MANUAL 不推进（保序 > 可用性）
    }

    @Test
    void staleHead422Supersedes() {
        // EX-02：head/commit 不匹配类 422 = 确定性否定 → SUPERSEDED(STALE_HEAD)
        ClaimedCommand cmd = TestFixtures.command(CommandType.PUBLISH_REVIEW, 1, 1,
                OutboxState.PENDING, 0, 3);
        store.put(cmd);
        payloadReader.put(cmd.payloadHash(), TestFixtures.reviewPayload(cmd));
        github.respondWrite(TypedResponse.ofObject(422,
                Map.of("message", "The commit_id is not the head of the pull request")));

        assertEquals(PublishOutcome.SUPERSEDED, executor.execute(cmd));
        assertEquals(OutboxState.SUPERSEDED, store.stateOf(cmd).state());
        assertEquals("STALE_HEAD", store.errorCodes.get(cmd.operationId().value()));
        assertEquals(1, store.cursor.lastResolvedSequence());
    }

    @Test
    void parameter422FailsTerminal() {
        // EX-02 另一面：参数错误类 422 → FAILED_TERMINAL
        ClaimedCommand cmd = pendingCheck();
        github.respondWrite(TypedResponse.ofObject(422, Map.of("message", "Invalid name field")));

        assertEquals(PublishOutcome.FAILED_TERMINAL, executor.execute(cmd));
        assertEquals(OutboxState.FAILED_TERMINAL, store.stateOf(cmd).state());
    }

    @Test
    void authFailureFailsTerminalWithSafetyEvent() {
        // 401/403 → FAILED_TERMINAL + SAFETY_REJECTED 告警
        ClaimedCommand cmd = pendingCheck();
        github.respondWrite(TypedResponse.ofStatus(403));

        assertEquals(PublishOutcome.FAILED_TERMINAL, executor.execute(cmd));
        assertTrue(store.events.stream().anyMatch(
                e -> e.eventType() == ExecutionEventType.SAFETY_REJECTED));
    }

    @Test
    void installationMismatchFailsTerminalWithSafetyEvent() {
        // SEC 加固：payload installation_id ≠ 部署配置 → FAILED_TERMINAL + SAFETY_REJECTED，零触网
        ClaimedCommand cmd = pendingCheck();
        Map<String, Object> payload = TestFixtures.checkPayload(cmd);
        payload.put("installation_id", TestFixtures.INSTALLATION_ID + 1);
        payloadReader.put(cmd.payloadHash(), payload);

        assertEquals(PublishOutcome.FAILED_TERMINAL, executor.execute(cmd));
        assertTrue(github.writeRequests.isEmpty()); // 零触网
        assertEquals(OutboxState.FAILED_TERMINAL, store.stateOf(cmd).state());
        assertEquals("INSTALLATION_MISMATCH", store.errorCodes.get(cmd.operationId().value()));
        assertTrue(store.events.stream().anyMatch(
                e -> e.eventType() == ExecutionEventType.SAFETY_REJECTED));
    }

    // ---------- T3-A 拦截路径（绝不触网） ----------

    @Test
    void schemaRejectNeverTouchesGitHub() {
        // EX-09：非白名单 → T3-A 内 FAILED_TERMINAL，adapter 零调用
        ClaimedCommand cmd = pendingCheck();
        Map<String, Object> payload = TestFixtures.checkPayload(cmd);
        payload.put("name", "not-whitelisted");
        payloadReader.put(cmd.payloadHash(), payload);

        assertEquals(PublishOutcome.FAILED_TERMINAL, executor.execute(cmd));
        assertTrue(github.writeRequests.isEmpty());
        assertEquals(OutboxState.FAILED_TERMINAL, store.stateOf(cmd).state());
        assertTrue(store.events.stream().anyMatch(
                e -> e.eventType() == ExecutionEventType.SAFETY_REJECTED));
    }

    @Test
    void payloadUnavailableFailsClosed() {
        // E5：CAS 读不到 payload → fail-closed FAILED_TERMINAL，不触网
        ClaimedCommand cmd = TestFixtures.command(CommandType.CREATE_CHECK, 1, 1,
                OutboxState.PENDING, 0, 3);
        store.put(cmd); // payloadReader 为空

        assertEquals(PublishOutcome.FAILED_TERMINAL, executor.execute(cmd));
        assertTrue(github.writeRequests.isEmpty());
        assertEquals("PAYLOAD_UNAVAILABLE", store.errorCodes.get(cmd.operationId().value()));
    }

    @Test
    void sequenceGapDefersWithEvent() {
        // E2：跳号 → 记 SEQUENCE_GAP_DETECTED，不执行
        ClaimedCommand cmd = TestFixtures.command(CommandType.CREATE_CHECK, 3, 1,
                OutboxState.PENDING, 0, 3);
        store.put(cmd);
        payloadReader.put(cmd.payloadHash(), TestFixtures.checkPayload(cmd));

        assertEquals(PublishOutcome.DEFERRED, executor.execute(cmd));
        assertEquals(OutboxState.PENDING, store.stateOf(cmd).state());
        assertTrue(github.writeRequests.isEmpty());
        assertTrue(store.events.stream().anyMatch(
                e -> e.eventType() == ExecutionEventType.SEQUENCE_GAP_DETECTED));
    }

    @Test
    void staleEpochSupersededByFence() {
        // I6：旧 epoch 命令永远到不了 GitHub 写
        ClaimedCommand cmd = pendingCheck();
        store.cursor = new com.objwww.pr.publisher.domain.model.SubjectCursor(2, 0);

        assertEquals(PublishOutcome.SUPERSEDED, executor.execute(cmd));
        assertEquals(OutboxState.SUPERSEDED, store.stateOf(cmd).state());
        assertTrue(github.writeRequests.isEmpty());
    }

    @Test
    void staleLeaseAbortsSilently() {
        // B-2：僵尸 worker，租约已失效 → 放弃不推进
        ClaimedCommand cmd = pendingCheck();
        store.staleLeaseOnWrite = true;

        assertEquals(PublishOutcome.DEFERRED, executor.execute(cmd));
        assertEquals(OutboxState.PENDING, store.stateOf(cmd).state());
        assertTrue(github.writeRequests.isEmpty());
    }

    // ---------- reconcile 探测 ----------

    @Test
    void reconcileFindsCheckByExternalId() {
        // §4.3/ST-03：按 external_id 找到 → FOUND（不重复创建）
        ClaimedCommand cmd = pendingCheck();
        github.respondRead(TypedResponse.ofObject(200, Map.of("check_runs", List.of(
                Map.of("id", 777, "external_id", "other"),
                Map.of("id", 778, "external_id", cmd.operationId().toString(),
                        "html_url", "http://x/778")))));

        ReconcileVerdict verdict = executor.reconcile(cmd);

        assertEquals(ReconcileVerdict.Kind.FOUND, verdict.kind());
        assertEquals("778", verdict.remoteId());
        assertEquals(GitHubOperation.LIST_CHECKS_FOR_SHA, github.readRequests.get(0).operation());
    }

    @Test
    void reconcileExhaustedWindowIsNotFound() {
        // 短页 = 窗口内穷尽 → 确认不存在
        ClaimedCommand cmd = pendingCheck();
        github.respondRead(TypedResponse.ofObject(200, Map.of("check_runs",
                List.of(Map.of("id", 1, "external_id", "other")))));

        assertEquals(ReconcileVerdict.Kind.NOT_FOUND, executor.reconcile(cmd).kind());
    }

    @Test
    void reconcilePaginatesAndStopsAtWindowCap() {
        // EX-04：满页未命中继续翻；翻满 probeMaxPages 仍无 → UNKNOWN（不无限翻页）
        ClaimedCommand cmd = pendingCheck();
        List<Map<String, Object>> fullPage = new java.util.ArrayList<>();
        for (int i = 0; i < 100; i++) {
            fullPage.add(Map.of("id", i, "external_id", "other-" + i));
        }
        for (int page = 0; page < 3; page++) {
            github.respondRead(TypedResponse.ofObject(200, Map.of("check_runs", fullPage)));
        }

        assertEquals(ReconcileVerdict.Kind.UNKNOWN, executor.reconcile(cmd).kind());
        assertEquals(3, github.readRequests.size());
        assertEquals(2, github.readRequests.get(1).parameters().get("page")); // 翻页经 withPage
    }

    @Test
    void reconcileUpdateCheck404IsManualPolicy() {
        // §6.3：UPDATE_CHECK 探测 404 → M0 策略 MANUAL，不自动重建
        ClaimedCommand cmd = TestFixtures.command(CommandType.UPDATE_CHECK, 1, 1,
                OutboxState.RECONCILING, 0, 3);
        store.put(cmd);
        payloadReader.put(cmd.payloadHash(), TestFixtures.updatePayload(cmd));
        github.respondRead(TypedResponse.ofStatus(404));

        assertEquals(ReconcileVerdict.Kind.MANUAL_POLICY, executor.reconcile(cmd).kind());
        assertEquals(GitHubOperation.GET_CHECK_RUN, github.readRequests.get(0).operation());
        assertEquals("998877", github.readRequests.get(0).parameters().get("check_run_id"));
    }

    @Test
    void reconcileReviewByMarker() {
        ClaimedCommand cmd = TestFixtures.command(CommandType.PUBLISH_REVIEW, 1, 1,
                OutboxState.RECONCILING, 0, 3);
        store.put(cmd);
        payloadReader.put(cmd.payloadHash(), TestFixtures.reviewPayload(cmd));
        github.respondRead(TypedResponse.ofArray(200, List.of(
                Map.of("id", 55, "body", "looks fine"),
                Map.of("id", 56, "body", "AI Code Review\n..." +
                        PublishReviewHandler.markerOf(cmd.operationId())))));

        ReconcileVerdict verdict = executor.reconcile(cmd);
        assertEquals(ReconcileVerdict.Kind.FOUND, verdict.kind());
        assertEquals("56", verdict.remoteId());
    }
}
