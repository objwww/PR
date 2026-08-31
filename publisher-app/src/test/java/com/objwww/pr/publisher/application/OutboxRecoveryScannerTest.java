package com.objwww.pr.publisher.application;

import com.objwww.pr.publisher.domain.handler.CreateCheckHandler;
import com.objwww.pr.publisher.domain.handler.PublicationHandler;
import com.objwww.pr.publisher.domain.handler.PublishReviewHandler;
import com.objwww.pr.publisher.domain.handler.UpdateCheckHandler;
import com.objwww.pr.publisher.domain.model.ClaimedCommand;
import com.objwww.pr.publisher.domain.service.FencedPublicationExecutor;
import com.objwww.pr.publisher.fakes.FakePayloadReader;
import com.objwww.pr.publisher.fakes.FakePublicationStore;
import com.objwww.pr.publisher.fakes.StubGitHubWriteAdapter;
import com.objwww.pr.publisher.fakes.TestFixtures;
import com.objwww.pr.shared.CommandType;
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
 * OutboxRecoveryScanner 三条扫描路径的决策逻辑（fake 端口内存测）。
 */
class OutboxRecoveryScannerTest {

    private FakePublicationStore store;
    private FakePayloadReader payloadReader;
    private StubGitHubWriteAdapter github;
    private OutboxRecoveryScanner scanner;
    private final List<PublicationHandler> handlers = List.of(
            new CreateCheckHandler(), new UpdateCheckHandler(), new PublishReviewHandler());

    @BeforeEach
    void setUp() {
        store = new FakePublicationStore();
        payloadReader = new FakePayloadReader();
        github = new StubGitHubWriteAdapter();
        FencedPublicationExecutor executor = new FencedPublicationExecutor(
                github, store, payloadReader, handlers, Duration.ofSeconds(60), 3,
                TestFixtures.INSTALLATION_ID);
        scanner = new OutboxRecoveryScanner(store, executor, handlers,
                Duration.ofSeconds(120), 2, 50, 60_000, 60_000);
    }

    private ClaimedCommand reconcilingCheck() {
        ClaimedCommand cmd = TestFixtures.command(CommandType.CREATE_CHECK, 1, 1,
                OutboxState.RECONCILING, 0, 3);
        store.put(cmd);
        payloadReader.put(cmd.payloadHash(), TestFixtures.checkPayload(cmd));
        return cmd;
    }

    @Test
    void path1ExpiredInFlightGoesReconciling() {
        // §4.3：崩溃窗口——禁盲目重发，先转对账
        ClaimedCommand cmd = TestFixtures.command(CommandType.CREATE_CHECK, 1, 1,
                OutboxState.IN_FLIGHT, 0, 3);
        store.put(cmd);
        store.expiredInFlight.add(cmd);

        scanner.runOnce();

        assertEquals(OutboxState.RECONCILING, store.stateOf(cmd).state());
        assertTrue(github.writeRequests.isEmpty()); // 没有重发
    }

    @Test
    void path2FoundConfirmsWithoutRecreate() {
        // ST-03：远端已创建 → CONFIRMED + remote_id + 游标推进，零重复写
        ClaimedCommand cmd = reconcilingCheck();
        store.dueReconciling.add(cmd);
        github.respondRead(TypedResponse.ofObject(200, Map.of("check_runs", List.of(
                Map.of("id", 555, "external_id", cmd.operationId().toString())))));

        scanner.runOnce();

        assertEquals(OutboxState.CONFIRMED, store.stateOf(cmd).state());
        assertEquals("555", store.confirmedRemoteIds.get(cmd.operationId().value()));
        assertEquals(PublicationResourceType.CHECK_RUN, store.resources.get(cmd.operationId().value()));
        assertEquals(1, store.cursor.lastResolvedSequence());
        assertTrue(github.writeRequests.isEmpty());
    }

    @Test
    void path2NotFoundGoesRetryWait() {
        // ST-04：窗口内穷尽确认不存在 → RETRY_WAIT 退避重发
        ClaimedCommand cmd = reconcilingCheck();
        store.dueReconciling.add(cmd);
        github.respondRead(TypedResponse.ofObject(200, Map.of("check_runs", List.of())));

        scanner.runOnce();

        assertEquals(OutboxState.RETRY_WAIT, store.stateOf(cmd).state());
    }

    @Test
    void path2UnknownCountsAndCircuitBreaksAtBudget() {
        // EX-04：查不到也不能确认 → 计数；超预算 → MANUAL 熔断
        ClaimedCommand cmd = reconcilingCheck();
        store.dueReconciling.add(cmd);
        github.failTransport(true); // 探测也失败 → UNKNOWN

        scanner.runOnce(); // not_found=1，预算 2，不熔断
        assertEquals(OutboxState.RECONCILING, store.stateOf(cmd).state());
        assertEquals(1, store.stateOf(cmd).reconcileNotFoundCount());

        scanner.runOnce(); // not_found=2，仍不超
        assertEquals(OutboxState.RECONCILING, store.stateOf(cmd).state());

        scanner.runOnce(); // not_found=3 > 2 → MANUAL
        assertEquals(OutboxState.MANUAL, store.stateOf(cmd).state());
        assertEquals("RECONCILE_BUDGET_EXCEEDED", store.errorCodes.get(cmd.operationId().value()));
    }

    @Test
    void path2ManualPolicyGoesManual() {
        // UPDATE_CHECK 探测 404 → 策略性 MANUAL
        ClaimedCommand cmd = TestFixtures.command(CommandType.UPDATE_CHECK, 1, 1,
                OutboxState.RECONCILING, 0, 3);
        store.put(cmd);
        payloadReader.put(cmd.payloadHash(), TestFixtures.updatePayload(cmd));
        store.dueReconciling.add(cmd);
        github.respondRead(TypedResponse.ofStatus(404));

        scanner.runOnce();

        assertEquals(OutboxState.MANUAL, store.stateOf(cmd).state());
        assertEquals("REMOTE_NOT_FOUND", store.errorCodes.get(cmd.operationId().value()));
    }

    @Test
    void path3StaleEpochSupersededWithCursorAdvance() {
        // v2.1 修订三兜底：epoch 落后的 PENDING → SUPERSEDED + 游标推进
        ClaimedCommand cmd = TestFixtures.command(CommandType.CREATE_CHECK, 1, 1,
                OutboxState.PENDING, 0, 3);
        store.put(cmd);
        store.staleEpoch.add(cmd);

        scanner.runOnce();

        assertEquals(OutboxState.SUPERSEDED, store.stateOf(cmd).state());
        assertEquals("STALE_EPOCH", store.errorCodes.get(cmd.operationId().value()));
        assertEquals(1, store.cursor.lastResolvedSequence());
    }

    @Test
    void idleRoundDoesNothing() {
        assertEquals(0, scanner.runOnce());
    }
}
