package com.objwww.pr.control.application;

import com.objwww.pr.control.domain.ai.FaultScope;
import com.objwww.pr.control.domain.ai.ModelBudgetExceededException;
import com.objwww.pr.control.domain.ai.ModelCallContext;
import com.objwww.pr.control.domain.ai.ModelCallFailedException;
import com.objwww.pr.control.domain.ai.ModelCallFailure;
import com.objwww.pr.control.domain.ai.ModelCallLedgerEntry;
import com.objwww.pr.control.domain.ai.ModelCallLedgerRepository;
import com.objwww.pr.control.domain.ai.ModelGatewayParams;
import com.objwww.pr.control.domain.ai.ModelRequest;
import com.objwww.pr.control.domain.ai.ModelRetryDeferredException;
import com.objwww.pr.control.domain.ai.ModelRoute;
import com.objwww.pr.control.domain.ai.ModelRouteIdentity;
import com.objwww.pr.control.domain.ai.PricingService;
import com.objwww.pr.control.domain.ai.RouteCallOutcome;
import com.objwww.pr.control.domain.ai.RouteClientPort;
import com.objwww.pr.control.domain.ai.RoutedModelResult;
import com.objwww.pr.control.domain.ai.TokenUsage;
import com.objwww.pr.control.domain.service.ExecutionLedger;
import com.objwww.pr.control.support.InMemoryStores;
import com.objwww.pr.shared.ExecutionEvent;
import com.objwww.pr.shared.ExecutionEventType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * ModelGateway 附录 A 编号伪代码结构测试：脚本化 RouteClientPort + 内存账本假实现 + 记录型事件账本。
 * 小数值参数（backoffBase=1ms、perCallTimeout=5s、deadline=30s）保证无真实长等待。
 */
class ModelGatewayTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC);

    // 主备路由共享 quotaScope（异 endpointScope/credentialDomain）：
    // ENDPOINT 族可 fallback、ACCOUNT 族不可 fallback，一套路由覆盖两类场景
    private static final ModelRoute PRIMARY =
            new ModelRoute("route-p", "model-a", "ep-a", "quota-shared", "cred-a", null);
    private static final ModelRoute FALLBACK =
            new ModelRoute("route-f", "model-b", "ep-b", "quota-shared", "cred-b", null);

    private ScriptedRouteClient primaryClient;
    private ScriptedRouteClient fallbackClient;
    private FakeLedgerRepository ledger;
    private InMemoryStores.Events events;

    @BeforeEach
    void setUp() {
        primaryClient = new ScriptedRouteClient();
        fallbackClient = new ScriptedRouteClient();
        ledger = new FakeLedgerRepository();
        events = new InMemoryStores.Events();
    }

    private static ModelGatewayParams params() {
        return new ModelGatewayParams(
                1,                       // maxCallRetries（单路由物理上限 2）
                10,                      // maxPhysicalCallsPerStep
                1_000,                   // maxPromptTokensPerCall
                1_000,                   // maxCompletionTokensPerCall
                100,                     // maxTotalTokensPerStep
                Duration.ofSeconds(30),  // gatewayTotalDeadline
                Duration.ofSeconds(5),   // inlineRetryMaxDelay
                Duration.ofSeconds(5),   // perCallTimeout
                2,                       // failureThreshold
                Duration.ofSeconds(10),  // circuitCoolDown
                Duration.ofMillis(1),    // backoffBase
                Duration.ofMillis(10),   // backoffMax
                "test-provider", "v1");
    }

    private ModelGateway gateway(boolean withFallback) {
        return new ModelGateway(PRIMARY, withFallback ? FALLBACK : null,
                primaryClient, withFallback ? fallbackClient : null,
                params(), ledger, new PricingService(Map.of()),
                new ExecutionLedger(events), CLOCK);
    }

    private static ModelRequest request() {
        return new ModelRequest("hello prompt", 100, Duration.ofSeconds(5));
    }

    private static ModelCallContext context() {
        return new ModelCallContext(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), 1L, CLOCK.instant().plusSeconds(600), () -> true);
    }

    private static RouteCallOutcome ok(long prompt, long completion) {
        return new RouteCallOutcome.Ok("content", new TokenUsage(prompt, completion, prompt + completion),
                false, "model-a", "req-1", Duration.ofMillis(5));
    }

    private static RouteCallOutcome failed(ModelCallFailure f) {
        return new RouteCallOutcome.Failed(f, 500, null, null, Duration.ofMillis(5));
    }

    private ExecutionEvent lastEvent(ExecutionEventType type) {
        return events.all().stream()
                .filter(e -> e.eventType() == type)
                .reduce((a, b) -> b)
                .orElseThrow(() -> new AssertionError("缺少事件 " + type));
    }

    // ------------------------------------------------------------------ 1. 主路由一次成功

    @Test
    void primarySuccessWritesOneSucceededRow() {
        primaryClient.enqueue(ok(10, 5));

        RoutedModelResult result = gateway(true).complete(request(), context());

        assertThat(primaryClient.calls()).isEqualTo(1);
        assertThat(fallbackClient.calls()).isZero();
        assertThat(ledger.rows()).hasSize(1);
        ModelCallLedgerEntry row = ledger.rows().get(0);
        assertThat(ledger.stateOf(row.id())).isEqualTo("SUCCEEDED");
        assertThat(row.callSeq()).isEqualTo(1);
        assertThat(row.routeRole()).isEqualTo("PRIMARY");
        assertThat(row.routeId()).isEqualTo("route-p");
        assertThat(result.callSeq()).isEqualTo(1);
        assertThat(result.route()).isEqualTo(PRIMARY);
        assertThat(result.contractIdentity())
                .isEqualTo(new ModelRouteIdentity("test-provider", "model-a", "v1"));
        assertThat(result.invocationId()).isEqualTo(row.invocationId());
        assertThat(result.fallbackFrom()).isNull();
    }

    // ------------------------------------------------------------------ 2. INSERT STARTED 写失败（D5 闸门：零触网）

    @Test
    void insertStartedFailureThrowsWithoutTouchingNetwork() {
        ledger.failInsertStarted(new RuntimeException("db down"));

        assertThatThrownBy(() -> gateway(true).complete(request(), context()))
                .isInstanceOfSatisfying(ModelCallFailedException.class, e ->
                        assertThat(e.errorCode()).isEqualTo("LEDGER_WRITE_FAILED"));

        assertThat(primaryClient.calls()).isZero();
        assertThat(fallbackClient.calls()).isZero();
        assertThat(ledger.rows()).isEmpty();
    }

    // ------------------------------------------------------------------ 3. ServerError 一次后成功

    @Test
    void serverErrorRetriedOnceThenSucceedsWithTwoLedgerRows() {
        primaryClient.enqueue(failed(new ModelCallFailure.ServerError(FaultScope.ENDPOINT)));
        primaryClient.enqueue(ok(10, 5));

        RoutedModelResult result = gateway(true).complete(request(), context());

        assertThat(primaryClient.calls()).isEqualTo(2);
        assertThat(ledger.rows()).hasSize(2);
        ModelCallLedgerEntry first = ledger.rows().get(0);
        ModelCallLedgerEntry second = ledger.rows().get(1);
        assertThat(first.callSeq()).isEqualTo(1);
        assertThat(ledger.stateOf(first.id())).isEqualTo("FAILED");
        assertThat(ledger.outcomeOf(first.id())).isEqualTo("SERVER_ERROR");
        assertThat(second.callSeq()).isEqualTo(2);
        assertThat(ledger.stateOf(second.id())).isEqualTo("SUCCEEDED");
        // 同一 invocation 两行
        assertThat(second.invocationId()).isEqualTo(first.invocationId());
        assertThat(result.callSeq()).isEqualTo(2);
    }

    // ------------------------------------------------------------------ 4. NetworkError 耗尽重试 → fallback 成功

    @Test
    void networkErrorExhaustsRetriesThenFallbackSucceeds() {
        primaryClient.enqueue(failed(new ModelCallFailure.NetworkError(FaultScope.ENDPOINT)));
        primaryClient.enqueue(failed(new ModelCallFailure.NetworkError(FaultScope.ENDPOINT)));
        fallbackClient.enqueue(ok(10, 5));

        RoutedModelResult result = gateway(true).complete(request(), context());

        assertThat(primaryClient.calls()).isEqualTo(2); // 上限 maxCallRetries+1=2
        assertThat(fallbackClient.calls()).isEqualTo(1);
        assertThat(ledger.rows()).hasSize(3);
        ModelCallLedgerEntry fallbackRow = ledger.rows().get(2);
        assertThat(fallbackRow.routeRole()).isEqualTo("FALLBACK");
        assertThat(fallbackRow.fallbackFrom()).isEqualTo("route-p");
        assertThat(fallbackRow.routeId()).isEqualTo("route-f");
        assertThat(ledger.stateOf(fallbackRow.id())).isEqualTo("SUCCEEDED");
        assertThat(result.route()).isEqualTo(FALLBACK);
        assertThat(result.fallbackFrom()).isEqualTo("route-p");

        ExecutionEvent ev = lastEvent(ExecutionEventType.MODEL_FALLBACK_SELECTED);
        assertThat(ev.payload()).containsKeys("route_id", "invocation_id", "reason");
        assertThat(ev.payload()).containsEntry("from_route", "route-p")
                .containsEntry("to_route", "route-f");
    }

    // ------------------------------------------------------------------ 5. QuotaTemporary(notBefore) → Defer

    @Test
    void quotaTemporaryDefersWithExactNotBefore() {
        Instant notBefore = CLOCK.instant().plusSeconds(3600);
        primaryClient.enqueue(failed(new ModelCallFailure.QuotaTemporary(FaultScope.ACCOUNT, notBefore)));

        assertThatThrownBy(() -> gateway(true).complete(request(), context()))
                .isInstanceOfSatisfying(ModelRetryDeferredException.class, e ->
                        assertThat(e.notBefore()).isEqualTo(notBefore));

        ExecutionEvent ev = lastEvent(ExecutionEventType.MODEL_RETRY_DEFERRED);
        assertThat(ev.payload()).containsKeys("route_id", "invocation_id", "reason");
        assertThat(ev.payload()).containsEntry("not_before", notBefore.toString());
    }

    // ------------------------------------------------------------------ 6. 熔断 OPEN 快败

    @Test
    void circuitOpenRejectsWithoutNetworkCall() {
        ModelGateway gw = gateway(false); // 单路由：无 fallback，OPEN 即快败
        // 灌 failureThreshold=2 次计数型失败使熔断 OPEN（最终以 Defer 收尾，忽略）
        primaryClient.enqueue(failed(new ModelCallFailure.ServerError(FaultScope.ENDPOINT)));
        primaryClient.enqueue(failed(new ModelCallFailure.ServerError(FaultScope.ENDPOINT)));
        assertThatThrownBy(() -> gw.complete(request(), context()))
                .isInstanceOf(ModelRetryDeferredException.class);
        int callsAfterOpen = primaryClient.calls();

        assertThatThrownBy(() -> gw.complete(request(), context()))
                .isInstanceOfSatisfying(ModelCallFailedException.class, e ->
                        assertThat(e.errorCode()).isEqualTo("CIRCUIT_OPEN"));

        assertThat(primaryClient.calls()).isEqualTo(callsAfterOpen); // 零新增触网
        ExecutionEvent ev = lastEvent(ExecutionEventType.MODEL_CIRCUIT_OPEN_REJECT);
        assertThat(ev.payload()).containsKeys("route_id", "invocation_id", "reason");
        assertThat(ev.payload()).containsEntry("route_id", "route-p")
                .containsEntry("fault_scope", "ENDPOINT");
    }

    // ------------------------------------------------------------------ 7. 终态写失败（SUCCEEDED 更新抛）

    @Test
    void terminalSuccessWriteFailureThrowsLedgerWriteFailed() {
        primaryClient.enqueue(ok(10, 5));
        ledger.failCompleteSuccess(new RuntimeException("db down"));

        assertThatThrownBy(() -> gateway(true).complete(request(), context()))
                .isInstanceOfSatisfying(ModelCallFailedException.class, e ->
                        assertThat(e.errorCode()).isEqualTo("LEDGER_WRITE_FAILED"));

        assertThat(primaryClient.calls()).isEqualTo(1);
        // 行滞留 STARTED（Recovery 标 UNKNOWN 语义）
        assertThat(ledger.stateOf(ledger.rows().get(0).id())).isEqualTo("STARTED");
    }

    // ------------------------------------------------------------------ 8. post-call 超 Step 总 token 预算

    @Test
    void postCallTotalTokenOverflowKeepsSucceededRowThenThrows() {
        primaryClient.enqueue(ok(60, 50)); // total=110 > maxTotalTokensPerStep=100

        assertThatThrownBy(() -> gateway(true).complete(request(), context()))
                .isInstanceOf(ModelBudgetExceededException.class);

        // usage 已落账（钱已花，事实先记）再抛
        assertThat(ledger.stateOf(ledger.rows().get(0).id())).isEqualTo("SUCCEEDED");
        ExecutionEvent ev = lastEvent(ExecutionEventType.MODEL_BUDGET_REJECTED);
        assertThat(ev.payload()).containsKeys("route_id", "invocation_id", "reason");
        assertThat(ev.payload()).containsEntry("reason", "POST_CALL_TOTAL_TOKENS");
    }

    // ------------------------------------------------------------------ 9. 预算预检拒绝（零触网零账本）

    @Test
    void preCheckRejectsOversizedPromptWithoutNetworkOrLedger() {
        ModelRequest bigPrompt = new ModelRequest("x".repeat(4004), 100, Duration.ofSeconds(5));

        assertThatThrownBy(() -> gateway(true).complete(bigPrompt, context()))
                .isInstanceOf(ModelBudgetExceededException.class);

        assertThat(primaryClient.calls()).isZero();
        assertThat(fallbackClient.calls()).isZero();
        assertThat(ledger.rows()).isEmpty();
        ExecutionEvent ev = lastEvent(ExecutionEventType.MODEL_BUDGET_REJECTED);
        assertThat(ev.payload()).containsKeys("route_id", "invocation_id", "reason");
        assertThat(ev.payload()).containsEntry("reason", "PRE_CHECK");
    }

    @Test
    void preCheckRejectsMaxTokensOverLimitWithoutNetworkOrLedger() {
        ModelRequest bigCompletion = new ModelRequest("hello prompt", 1001, Duration.ofSeconds(5));

        assertThatThrownBy(() -> gateway(true).complete(bigCompletion, context()))
                .isInstanceOf(ModelBudgetExceededException.class);

        assertThat(primaryClient.calls()).isZero();
        assertThat(ledger.rows()).isEmpty();
        assertThat(lastEvent(ExecutionEventType.MODEL_BUDGET_REJECTED).payload())
                .containsEntry("reason", "PRE_CHECK");
    }

    // ------------------------------------------------------------------ 假实现

    /** 脚本化 RouteClientPort：按序消费 outcome，记录调用次数与下传超时 */
    private static final class ScriptedRouteClient implements RouteClientPort {
        private final Queue<RouteCallOutcome> outcomes = new ArrayDeque<>();
        private final List<Duration> timeouts = new ArrayList<>();

        void enqueue(RouteCallOutcome outcome) {
            outcomes.add(outcome);
        }

        @Override
        public RouteCallOutcome complete(ModelRequest request, Duration perCallTimeout) {
            timeouts.add(perCallTimeout);
            RouteCallOutcome outcome = outcomes.poll();
            if (outcome == null) {
                throw new IllegalStateException("脚本耗尽，却被调用了第 " + timeouts.size() + " 次");
            }
            return outcome;
        }

        int calls() {
            return timeouts.size();
        }
    }

    /** 内存账本假实现：行按插入序留痕，状态/终态 outcome 按 id 记录；支持注入写失败 */
    private static final class FakeLedgerRepository implements ModelCallLedgerRepository {
        private final List<ModelCallLedgerEntry> rows = new ArrayList<>();
        private final Map<UUID, String> states = new HashMap<>();
        private final Map<UUID, String> outcomes = new HashMap<>();
        private RuntimeException insertStartedFailure;
        private RuntimeException completeSuccessFailure;

        void failInsertStarted(RuntimeException e) {
            this.insertStartedFailure = e;
        }

        void failCompleteSuccess(RuntimeException e) {
            this.completeSuccessFailure = e;
        }

        @Override
        public void insertStarted(ModelCallLedgerEntry entry) {
            if (insertStartedFailure != null) {
                throw insertStartedFailure;
            }
            rows.add(entry);
            states.put(entry.id(), "STARTED");
        }

        @Override
        public boolean completeTerminalSuccess(UUID id, TokenUsage usage, boolean usageMissing,
                                               String reportedModel, String providerRequestId,
                                               Duration latency, Long costMicros, String pricingVersion,
                                               String currency, Long inputPriceMicrosPerK,
                                               Long outputPriceMicrosPerK) {
            if (completeSuccessFailure != null) {
                throw completeSuccessFailure;
            }
            states.put(id, "SUCCEEDED");
            outcomes.put(id, "OK");
            return true;
        }

        @Override
        public boolean completeTerminalFailure(UUID id, String outcome, Integer httpStatus,
                                               Duration retryAfter, Duration latency, String errorCode,
                                               String errorFingerprint, String sanitizedMessage) {
            states.put(id, "FAILED");
            outcomes.put(id, outcome);
            return true;
        }

        @Override
        public int markUnknownOlderThan(Instant threshold) {
            return 0;
        }

        List<ModelCallLedgerEntry> rows() {
            return rows;
        }

        String stateOf(UUID id) {
            return states.get(id);
        }

        String outcomeOf(UUID id) {
            return outcomes.get(id);
        }
    }
}
