package com.objwww.pr.control.alert.application.agent;

import com.objwww.pr.control.alert.domain.agent.RcaModelCallContext;
import com.objwww.pr.control.alert.domain.agent.RcaModelCallException;
import com.objwww.pr.control.alert.domain.agent.RcaModelCallLedger;
import com.objwww.pr.control.alert.domain.agent.RcaModelOutcome;
import com.objwww.pr.control.alert.support.AlertInMemoryStores;
import com.objwww.pr.control.application.ModelGateway;
import com.objwww.pr.control.domain.ai.FaultScope;
import com.objwww.pr.control.domain.ai.ModelBudgetExceededException;
import com.objwww.pr.control.domain.ai.ModelCallContext;
import com.objwww.pr.control.domain.ai.ModelCallFailure;
import com.objwww.pr.control.domain.ai.ModelCallLedgerEntry;
import com.objwww.pr.control.domain.ai.ModelCallLedgerRepository;
import com.objwww.pr.control.domain.ai.ModelGatewayParams;
import com.objwww.pr.control.domain.ai.ModelRequest;
import com.objwww.pr.control.domain.ai.ModelRoute;
import com.objwww.pr.control.domain.ai.PricingService;
import com.objwww.pr.control.domain.ai.RouteCallOutcome;
import com.objwww.pr.control.domain.ai.RouteClientPort;
import com.objwww.pr.control.domain.ai.TokenUsage;
import com.objwww.pr.control.domain.service.ExecutionLedger;
import com.objwww.pr.shared.Digest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * R7a-1 模型网关适配单测（v2.1 §二，隔离环境零真网）：账本不可写零触网、
 * SUCCESS 账实两落、usage 缺失不猜零（RX19 面）、终态失败 FAILED 原因码、
 * 平台账本面不可写 → UNKNOWN 保守占预算、DEFERRED 映射、预算预检拒绝 +
 * GATEWAY_ 事件汇落 rca_event 假件。
 */
class R7ModelGatewayTest {

    private static final Instant NOW = Instant.parse("2026-09-11T08:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    private static final ModelRoute ROUTE =
            new ModelRoute("route-rca", "model-rca", "ep-rca", "quota-rca", "cred-rca", null);

    private ScriptedRouteClient client;
    private PlatformLedgerFake platformLedger;
    private AlertInMemoryStores stores;
    private AlertInMemoryStores.InputCaptures inputCaptures;
    private RcaModelGateway rcaGateway;

    private final UUID runId = UUID.randomUUID();
    private final UUID taskId = UUID.randomUUID();
    private final UUID attemptId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        client = new ScriptedRouteClient();
        platformLedger = new PlatformLedgerFake();
        stores = new AlertInMemoryStores();
        // RCA 侧事件汇：MODEL_* 决策事件 → rca_event 假件（GATEWAY_ 前缀）
        ExecutionLedger rcaSinkLedger = new ExecutionLedger(
                new com.objwww.pr.control.infrastructure.persistence.RcaModelEventSink(
                        stores.rcaEvents, new com.fasterxml.jackson.databind.ObjectMapper()));
        ModelGateway platform = new ModelGateway(ROUTE, null, client, null,
                params(), platformLedger, new PricingService(Map.of()), rcaSinkLedger, CLOCK);
        inputCaptures = new AlertInMemoryStores.InputCaptures(
                com.objwww.pr.control.alert.domain.agent.RcaModelInputCapture.Level.DIGEST_ONLY);
        rcaGateway = new RcaModelGateway(platform, stores.modelCalls,
                new PricingService(Map.of()), inputCaptures, CLOCK);
    }

    private static ModelGatewayParams params() {
        return new ModelGatewayParams(
                0,                      // maxCallRetries（失败不内联重试，用例聚焦映射面）
                4,                      // maxPhysicalCallsPerStep
                1_000,                  // maxPromptTokensPerCall
                1_000,                  // maxCompletionTokensPerCall
                100_000,                // maxTotalTokensPerStep
                Duration.ofSeconds(30), // gatewayTotalDeadline
                Duration.ofMillis(1),   // inlineRetryMaxDelay
                Duration.ofSeconds(5),  // perCallTimeout
                4,                      // failureThreshold
                Duration.ofSeconds(10), // circuitCoolDown
                Duration.ofMillis(1),   // backoffBase
                Duration.ofMillis(5),   // backoffMax
                "test-provider", "v1");
    }

    private RcaModelCallContext ctx() {
        return new RcaModelCallContext(runId, taskId, attemptId, 0, 0,
                "primary", "1", "a".repeat(64), 3L, null, null, null, null,
                NOW.plusSeconds(600), NOW.plusSeconds(60), () -> true);
    }

    // ------------------------------------------------------- 账本不可写 = 零触网

    @Test
    void 账本open失败_零触网_抛封闭原因码() {
        // 同 (run, task, attempt, action, physical) 预占一行 → open 撞唯一键
        stores.modelCalls.open(new RcaModelCallLedger.OpenRow(UUID.randomUUID(), runId,
                taskId, attemptId, 0, 1, 0, "primary", "1", "a".repeat(64), "pd",
                null, null, null, null, 3L));
        int platformRowsBefore = platformLedger.rows.size();

        assertThatThrownBy(() -> rcaGateway.call(ctx(), "prompt", 100))
                .isInstanceOf(RcaModelCallException.class)
                .hasFieldOrPropertyWithValue("errorCode", "LEDGER_WRITE_FAILED");

        assertThat(client.calls()).as("零触网").isZero();
        assertThat(platformLedger.rows).as("平台账本零行").hasSize(platformRowsBefore);
    }

    @Test
    void epoch栅栏_旧代际动作零触网拒绝发送资格() {
        // EN-04 H04：切换生效（run 现行代际=2）后，携带旧 epoch=1 的新动作领取
        // 发送资格 = EPOCH_FENCE（零触网、非重试、不入账）
        stores.modelCalls.currentEpochView.put(runId, 2L);
        RcaModelCallContext fenced = new RcaModelCallContext(runId, taskId,
                attemptId, 0, 0, "primary", "1", "a".repeat(64), 3L, 1L,
                "d".repeat(64), null, null,
                NOW.plusSeconds(600), NOW.plusSeconds(60), () -> true);

        assertThatThrownBy(() -> rcaGateway.call(fenced, "prompt", 100))
                .isInstanceOf(RcaModelCallException.class)
                .hasFieldOrPropertyWithValue("errorCode", "EPOCH_FENCE");

        assertThat(client.calls()).as("零触网").isZero();
        assertThat(stores.modelCalls.all()).as("旧代际动作不入账").isEmpty();
    }

    // ------------------------------------------------------- 成功：账实两落

    @Test
    void 成功调用_RCA账本SUCCESS带usage_平台账本同铸_outcome带对账锚() {
        client.enqueue(new RouteCallOutcome.Ok("决策JSON",
                new TokenUsage(20, 10, 30), false, "model-rca", "req-abc",
                Duration.ofMillis(7)));

        RcaModelOutcome outcome = rcaGateway.call(ctx(), "prompt", 100);

        assertThat(client.calls()).isEqualTo(1);
        assertThat(outcome.content()).isEqualTo("决策JSON");
        assertThat(outcome.totalTokens()).isEqualTo(30);
        assertThat(outcome.usageMissing()).isFalse();
        assertThat(outcome.routeId()).isEqualTo("route-rca");

        var row = stores.modelCalls.all().get(0);
        assertThat(row.state()).isEqualTo("SUCCESS");
        assertThat(row.usage()).isNotNull();
        assertThat(row.usage().totalTokens()).isEqualTo(30);
        assertThat(row.usage().costMicros()).isNull(); // 测试无价目表：不猜钱数
        assertThat(row.usage().pricingVersion()).as("R4：有 usage 无价 = unpriced 显式语义")
                .isEqualTo("unpriced");
        assertThat(row.usage().gatewayInvocationId()).isEqualTo(outcome.gatewayInvocationId());
        assertThat(platformLedger.rows).as("平台账本同铸").hasSize(1);
    }

    @Test
    void usage缺失_SUCCESS但费用未决_不猜零RX19() {
        client.enqueue(new RouteCallOutcome.Ok("内容", new TokenUsage(0, 0, 0),
                true, "model-rca", null, Duration.ofMillis(5)));

        RcaModelOutcome outcome = rcaGateway.call(ctx(), "prompt", 100);

        assertThat(outcome.usageMissing()).isTrue();
        var row = stores.modelCalls.all().get(0);
        assertThat(row.state()).as("响应成功不伪称失败").isEqualTo("SUCCESS");
        assertThat(row.usage().usageMissing()).isTrue();
        assertThat(row.usage().costMicros()).as("费用未决不猜零").isNull();
        assertThat(row.usage().pricingVersion()).as("usage 缺失 = NOT_PRICED 全 null，与 unpriced 可区分")
                .isNull();
    }

    /** R4/BA-115：有 usage 无价目 = 账行 pricing_version='unpriced' + cost NULL（不填 0 不编价） */
    @Test
    void 有usage无价目_UNPRICED落账_与usage缺失账面可区分() {
        client.enqueue(new RouteCallOutcome.Ok("内容", new TokenUsage(10, 20, 30),
                false, "model-rca", null, Duration.ofMillis(5)));

        rcaGateway.call(ctx(), "prompt", 100);

        var row = stores.modelCalls.all().get(0);
        assertThat(row.state()).isEqualTo("SUCCESS");
        assertThat(row.usage().usageMissing()).isFalse();
        assertThat(row.usage().pricingVersion()).isEqualTo("unpriced");
        assertThat(row.usage().costMicros()).as("unpriced 不猜钱数（红线：不填 0）").isNull();
        assertThat(row.usage().currency()).isNull();
    }

    // ------------------------------------------------------- 失败映射

    @Test
    void 终态失败_RCA账本FAILED带原因码() {
        client.enqueue(new RouteCallOutcome.Failed(
                new ModelCallFailure.RequestInvalid(FaultScope.MODEL), 400, null, null,
                Duration.ofMillis(3)));

        assertThatThrownBy(() -> rcaGateway.call(ctx(), "prompt", 100))
                .isInstanceOf(RcaModelCallException.class)
                .hasFieldOrPropertyWithValue("errorCode", "REQUEST_INVALID");

        var row = stores.modelCalls.all().get(0);
        assertThat(row.state()).isEqualTo("FAILED");
        assertThat(row.errorCode()).isEqualTo("REQUEST_INVALID");
    }

    @Test
    void 平台账本面不可写_UNKNOWN保守占预算_不盲重发() {
        platformLedger.failInsertStarted = true;

        assertThatThrownBy(() -> rcaGateway.call(ctx(), "prompt", 100))
                .isInstanceOf(RcaModelCallException.class)
                .hasFieldOrPropertyWithValue("errorCode", "LEDGER_WRITE_FAILED");

        assertThat(client.calls()).as("平台 D5 闸：账本不可写零触网").isZero();
        var row = stores.modelCalls.all().get(0);
        assertThat(row.state()).as("已发送与否不确定 → UNKNOWN").isEqualTo("UNKNOWN");
        assertThat(stores.modelCalls.findUnsettledByRun(runId))
                .as("恢复对账读可见（不盲重发的输入）").hasSize(1);
    }

    @Test
    void 配额长等待_DEFERRED映射() {
        client.enqueue(new RouteCallOutcome.Failed(
                new ModelCallFailure.QuotaTemporary(FaultScope.ACCOUNT,
                        NOW.plusSeconds(3600)), 429, null, null,
                Duration.ofMillis(2)));

        assertThatThrownBy(() -> rcaGateway.call(ctx(), "prompt", 100))
                .isInstanceOf(RcaModelCallException.class)
                .hasFieldOrPropertyWithValue("errorCode", "DEFERRED");

        assertThat(stores.modelCalls.all().get(0).errorCode()).isEqualTo("DEFERRED");
    }

    // ------------------------------------------------------- 预算/期限闸 + 事件汇

    @Test
    void 预算预检拒绝_RCA账本FAILED_GATEWAY事件落rca_event假件() {
        assertThatThrownBy(() -> rcaGateway.call(ctx(), "x".repeat(8_000), 100))
                .isInstanceOf(RcaModelCallException.class)
                .hasFieldOrPropertyWithValue("errorCode", "BUDGET_EXCEEDED");

        assertThat(client.calls()).isZero();
        assertThat(stores.modelCalls.all().get(0).errorCode()).isEqualTo("BUDGET_EXCEEDED");
        assertThat(stores.rcaEvents.all())
                .as("MODEL_BUDGET_REJECTED 经 RCA 事件汇落 rca_event")
                .anySatisfy(e -> {
                    assertThat(e.eventType()).isEqualTo("GATEWAY_MODEL_BUDGET_REJECTED");
                    assertThat(e.runId()).isEqualTo(runId);
                });
    }

    @Test
    void deadline已耗尽_未发送_DEDLINE映射() {
        RcaModelCallContext expired = new RcaModelCallContext(runId, taskId, attemptId,
                0, 0, "primary", "1", "a".repeat(64), 3L, null, null, null, null,
                NOW.minusSeconds(1), NOW.minusSeconds(1), () -> true);

        assertThatThrownBy(() -> rcaGateway.call(expired, "prompt", 100))
                .isInstanceOf(RcaModelCallException.class)
                .hasFieldOrPropertyWithValue("errorCode", "DEADLINE_EXCEEDED");
        assertThat(client.calls()).isZero();
    }

    // ------------------------------------------------------- R2 输入捕获与回放

    @Test
    void r2_full档_原文落行_digest与账本对账() {
        inputCaptures = new AlertInMemoryStores.InputCaptures(
                com.objwww.pr.control.alert.domain.agent.RcaModelInputCapture.Level.FULL);
        rcaGateway = new RcaModelGateway(gatewayOf(client, platformLedger), stores.modelCalls,
                new PricingService(Map.of()), inputCaptures, CLOCK);
        client.enqueue(new RouteCallOutcome.Ok("决策JSON", new TokenUsage(20, 10, 30),
                false, "model-rca", "req-abc", Duration.ofMillis(7)));

        rcaGateway.call(ctx(), "prompt-with-secret", 100);

        assertThat(inputCaptures.all()).hasSize(1);
        var row = inputCaptures.all().get(0);
        assertThat(row.promptText()).as("FULL 档落原文（回放面完整）").isEqualTo("prompt-with-secret");
        assertThat(row.promptDigest())
                .as("promptDigest 恒为原文摘要，与 rca_model_call 账行对账")
                .isEqualTo(Digest.sha256Of("prompt-with-secret").value());
        assertThat(row.messageBytes()).isPositive();
        assertThat(row.approxTokens()).isPositive();
        assertThat(stores.modelCalls.all().get(0).open().promptDigest())
                .isEqualTo(row.promptDigest());
    }

    @Test
    void r2_digestOnly档_零原文_摘要可对账() {
        client.enqueue(new RouteCallOutcome.Ok("决策JSON", new TokenUsage(20, 10, 30),
                false, "model-rca", "req-abc", Duration.ofMillis(7)));

        rcaGateway.call(ctx(), "prompt", 100);

        var row = inputCaptures.all().get(0);
        assertThat(row.promptText()).as("digest-only 默认面不落原文").isNull();
        assertThat(row.promptDigest()).isEqualTo(Digest.sha256Of("prompt").value());
        assertThat(row.redactionNote()).isNull();
    }

    @Test
    void r2_redacted档_密钥词形零出现_掩文留痕() {
        inputCaptures = new AlertInMemoryStores.InputCaptures(
                com.objwww.pr.control.alert.domain.agent.RcaModelInputCapture.Level.REDACTED);
        rcaGateway = new RcaModelGateway(gatewayOf(client, platformLedger), stores.modelCalls,
                new PricingService(Map.of()), inputCaptures, CLOCK);
        String prompt = "call api with Authorization: Bearer sk-abcdef1234567890 and "
                + "{\"api_key\":\"verysecret\"}";
        client.enqueue(new RouteCallOutcome.Ok("ok", new TokenUsage(20, 10, 30),
                false, "model-rca", null, Duration.ofMillis(7)));

        rcaGateway.call(ctx(), prompt, 100);

        String stored = inputCaptures.all().get(0).promptText();
        assertThat(stored).as("掩文非原文").isNotEqualTo(prompt);
        assertThat(stored).as("Bearer 凭据值零出现").doesNotContain("sk-abcdef1234567890");
        assertThat(stored).as("secret 值零出现").doesNotContain("verysecret");
        assertThat(stored).as("掩码锚在场").contains("***");
        assertThat(inputCaptures.all().get(0).redactionNote()).contains("masked");
        assertThat(inputCaptures.all().get(0).promptDigest())
                .as("摘要仍为原文摘要（掩文不可反推原文=回放诚实'不完整'）")
                .isEqualTo(Digest.sha256Of(prompt).value());
    }

    @Test
    void r2_捕获写失败_LEDGER_WRITE_FAILED零触网() {
        inputCaptures.failure = new IllegalStateException("模拟 rca_model_input 不可写");
        int platformRowsBefore = platformLedger.rows.size();

        assertThatThrownBy(() -> rcaGateway.call(ctx(), "prompt", 100))
                .isInstanceOf(RcaModelCallException.class)
                .hasFieldOrPropertyWithValue("errorCode", "LEDGER_WRITE_FAILED");

        assertThat(client.calls()).as("回放链断裂的调用不发给模型（零触网）").isZero();
        assertThat(platformLedger.rows).as("平台账本零行").hasSize(platformRowsBefore);
        var row = stores.modelCalls.all().get(0);
        assertThat(row.state()).isEqualTo("FAILED");
        assertThat(row.errorCode()).isEqualTo("LEDGER_WRITE_FAILED");
    }

    // ------------------------------------------------------- R10 输入限额（MA-03/MC10~11）

    @Test
    void mc10_输入超限_零触网能力错误_账与捕获零足迹() {
        rcaGateway = new RcaModelGateway(gatewayOf(client, platformLedger), stores.modelCalls,
                new PricingService(Map.of()), inputCaptures, CLOCK, 100);
        int platformRowsBefore = platformLedger.rows.size();
        // len=200 → 估算 200/2+1=101 > V=100 → 拒绝
        String oversized = "x".repeat(200);

        assertThatThrownBy(() -> rcaGateway.call(ctx(), oversized, 100))
                .isInstanceOf(RcaModelCallException.class)
                .hasFieldOrPropertyWithValue("errorCode",
                        RcaModelGateway.CAPABILITY_INPUT_TOO_LARGE)
                .hasMessageContaining("V=100");

        assertThat(client.calls()).as("MC10 零远端请求").isZero();
        assertThat(stores.modelCalls.all()).as("MC10 在 ledger.open 之前拒绝=零账行").isEmpty();
        assertThat(inputCaptures.all()).as("MC10 零捕获行").isEmpty();
        assertThat(platformLedger.rows).as("MC10 平台账本零行").hasSize(platformRowsBefore);
    }

    @Test
    void mc11_估算边界_恰在限内放行_超一字符即拒() {
        rcaGateway = new RcaModelGateway(gatewayOf(client, platformLedger), stores.modelCalls,
                new PricingService(Map.of()), inputCaptures, CLOCK, 100);
        // len=198 → 估算 198/2+1=100 = V → 不超限，放行入模
        client.enqueue(new RouteCallOutcome.Ok("决策JSON", new TokenUsage(20, 10, 30),
                false, "model-rca", "req-abc", Duration.ofMillis(7)));
        rcaGateway.call(ctx(), "x".repeat(198), 100);
        assertThat(client.calls()).as("恰在 V 内不误杀").isEqualTo(1);

        // len=199 → 估算 199/2+1=100 仍 = V（保守取整）→ 放行；len=200 → 101 > V → 拒
        assertThatThrownBy(() -> rcaGateway.call(ctx(), "x".repeat(200), 100))
                .isInstanceOf(RcaModelCallException.class)
                .hasFieldOrPropertyWithValue("errorCode",
                        RcaModelGateway.CAPABILITY_INPUT_TOO_LARGE);
        assertThat(client.calls()).isEqualTo(1);
    }

    @Test
    void r10_遗留五参构造_默认无上限_配置非法值failFast() {
        // 5-arg 构造 = maxInputTokens=Integer.MAX_VALUE（存量装配零行为漂移）
        assertThatThrownBy(() -> rcaGateway.call(ctx(), "x".repeat(8_000), 100))
                .as("默认构造下超大输入走平台预算闸而非能力错误")
                .isInstanceOf(RcaModelCallException.class)
                .hasFieldOrPropertyWithValue("errorCode", "BUDGET_EXCEEDED");

        assertThatThrownBy(() -> new RcaModelGateway(gatewayOf(client, platformLedger),
                stores.modelCalls, new PricingService(Map.of()), inputCaptures, CLOCK, 0))
                .as("max-input-tokens ≤ 0 启动期 fail-fast")
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ------------------------------------------------------- 夹具

    /** 平台侧网关组装（R2 用例换捕获档位时复用） */
    private ModelGateway gatewayOf(ScriptedRouteClient client,
            PlatformLedgerFake platformLedger) {
        ExecutionLedger rcaSinkLedger = new ExecutionLedger(
                new com.objwww.pr.control.infrastructure.persistence.RcaModelEventSink(
                        stores.rcaEvents, new com.fasterxml.jackson.databind.ObjectMapper()));
        return new ModelGateway(ROUTE, null, client, null, params(), platformLedger,
                new PricingService(Map.of()), rcaSinkLedger, CLOCK);
    }

    /** 脚本化路由客户端（零真网） */
    private static final class ScriptedRouteClient implements RouteClientPort {
        private final Queue<RouteCallOutcome> script = new ArrayDeque<>();
        private int calls;

        void enqueue(RouteCallOutcome outcome) {
            script.add(outcome);
        }

        int calls() {
            return calls;
        }

        @Override
        public RouteCallOutcome complete(ModelRequest request, Duration timeout) {
            calls++;
            RouteCallOutcome outcome = script.poll();
            if (outcome == null) {
                throw new IllegalStateException("脚本空跑");
            }
            return outcome;
        }
    }

    /** 平台账本假件（可注入 insertStarted 失败——D5 闸验证；终态面由 RCA 侧断言） */
    private static final class PlatformLedgerFake implements ModelCallLedgerRepository {
        final List<ModelCallLedgerEntry> rows = new ArrayList<>();
        boolean failInsertStarted;

        @Override
        public void insertStarted(ModelCallLedgerEntry entry) {
            if (failInsertStarted) {
                throw new IllegalStateException("模拟平台账本写失败");
            }
            rows.add(entry);
        }

        @Override
        public boolean completeTerminalSuccess(UUID id, TokenUsage usage,
                boolean usageMissing, String reportedModel, String providerRequestId,
                Duration latency, Long costMicros, String pricingVersion, String currency,
                Long inputPriceMicrosPerK, Long outputPriceMicrosPerK) {
            return true;
        }

        @Override
        public boolean completeTerminalFailure(UUID id, String outcome, Integer httpStatus,
                Duration retryAfter, Duration latency, String errorCode,
                String errorFingerprint, String sanitizedMessage) {
            return true;
        }

        @Override
        public int markUnknownOlderThan(java.time.Instant threshold) {
            return 0;
        }
    }
}
