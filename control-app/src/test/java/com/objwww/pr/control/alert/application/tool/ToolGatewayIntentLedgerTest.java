package com.objwww.pr.control.alert.application.tool;

import com.objwww.pr.control.alert.application.mutation.ActionIntentLedger;
import com.objwww.pr.control.alert.domain.event.RcaEventAppender;
import com.objwww.pr.control.alert.domain.mutation.ActionIntent;
import com.objwww.pr.control.alert.domain.tool.ToolDefinition;
import com.objwww.pr.control.alert.domain.tool.ToolPolicy;
import com.objwww.pr.control.alert.domain.tool.ToolRisk;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ToolGateway 意图台账接线单测（PB-B1）：R2/R3 VALIDATE_ONLY 调用 → intent 行 +
 * TOOL_INTENT_VALIDATED 事件同键（intent_id）落账；台账未装配走独立事件路径（零漂移）。
 */
class ToolGatewayIntentLedgerTest {

    private static final UUID RUN = new UUID(0L, 91L);
    private static final UUID TASK = new UUID(0L, 92L);
    private static final UUID ATTEMPT = new UUID(0L, 93L);
    private static final Clock FIXED = Clock.fixed(Instant.ofEpochMilli(3_000_000),
            ZoneOffset.UTC);
    private static final ExecutorService POOL = Executors.newSingleThreadExecutor();

    @AfterAll
    static void shutdownPool() {
        POOL.shutdownNow();
    }

    record LedgerRow(ActionIntent intent, String eventType, String payloadJson) {
    }

    static final class CapturingLedger implements ActionIntentLedger {
        final List<LedgerRow> rows = new ArrayList<>();

        @Override
        public long record(ActionIntent intent, RcaEventAppender.EventDraft intentEvent) {
            rows.add(new LedgerRow(intent, intentEvent.eventType(),
                    intentEvent.payloadJson()));
            return 7;
        }
    }

    private static ToolRegistry.Registration writeTool() {
        ToolDefinition definition = new ToolDefinition("write.tool", "1.0.0",
                Map.of("type", "object", "properties", Map.of("q", Map.of("type", "string"))),
                ToolRisk.R2, 1_000, 16);
        return new ToolRegistry.Registration(definition, execution -> new byte[1]);
    }

    /** BA-171：带 service 参数的 R3 写工具（资源键提取面） */
    private static ToolRegistry.Registration r3ServiceTool() {
        ToolDefinition definition = new ToolDefinition("service.restart", "1",
                Map.of("type", "object", "properties",
                        Map.of("service", Map.of("type", "string"),
                                "reason", Map.of("type", "string")),
                        "required", List.of("service")),
                ToolRisk.R3, 1_000, 1024);
        return new ToolRegistry.Registration(definition,
                MutationToolCatalog.nonExecutablePlaceholder("service.restart"));
    }

    private static ToolGateway.ToolInvocation invocation() {
        return new ToolGateway.ToolInvocation(RUN, TASK, ATTEMPT, 5, "write.tool", "1.0.0",
                "2026-09-15T00:00:00Z/2026-09-15T01:00:00Z", Map.of("q", "x"), null);
    }

    private static ToolGateway.ToolInvocation r3Invocation(Map<String, Object> args) {
        return new ToolGateway.ToolInvocation(RUN, TASK, ATTEMPT, 5, "service.restart", "1",
                "2026-09-15T00:00:00Z/2026-09-15T01:00:00Z", args, null);
    }

    @Test
    void opL01_R2调用_意图行与事件同键入账() {
        CapturingLedger ledger = new CapturingLedger();
        ToolGateway gateway = new ToolGateway(new ToolRegistry(List.of(writeTool())),
                new ToolPolicy(Set.of("write.tool")), POOL, FIXED, null, null, null, ledger);
        ToolGateway.ToolInvocationResult result = gateway.invoke(invocation());
        assertThat(result.kind()).isEqualTo(ToolGateway.ToolInvocationResult.Kind.VALIDATE_ONLY);
        assertThat(ledger.rows).hasSize(1);
        LedgerRow row = ledger.rows.get(0);
        // 行字段与调用身份五元组同源
        assertThat(row.intent().runId()).isEqualTo(RUN);
        assertThat(row.intent().taskId()).isEqualTo(TASK);
        assertThat(row.intent().attemptId()).isEqualTo(ATTEMPT);
        assertThat(row.intent().callSeq()).isEqualTo(5);
        assertThat(row.intent().toolName()).isEqualTo("write.tool");
        assertThat(row.intent().risk()).isEqualTo(ToolRisk.R2);
        assertThat(row.intent().status())
                .isEqualTo(ActionIntent.IntentStatus.OPEN);
        assertThat(row.intent().actionDigest()).hasSize(64)
                .isEqualTo(result.actionDigest());
        // 行与事件同键（intent_id），事件类型不变
        assertThat(row.eventType()).isEqualTo("TOOL_INTENT_VALIDATED");
        assertThat(row.payloadJson()).contains("\"intent_id\":\"" + row.intent().intentId() + "\"");
    }

    @Test
    void opL02_台账未装配_独立事件路径零漂移() {
        // events 直连 + ledger 空 → 旧行为：仅独立事务事件，无行
        final List<String> eventTypes = new ArrayList<>();
        RcaEventAppender events = new RcaEventAppender() {
            @Override
            public long append(UUID runId, EventDraft draft) {
                eventTypes.add(draft.eventType());
                return 1;
            }

            @Override
            public long appendIndependent(UUID runId, EventDraft draft) {
                return append(runId, draft);
            }
        };
        ToolGateway gateway = new ToolGateway(new ToolRegistry(List.of(writeTool())),
                new ToolPolicy(Set.of("write.tool")), POOL, FIXED, events);
        gateway.invoke(invocation());
        assertThat(eventTypes).containsExactly("TOOL_INTENT_VALIDATED");
    }

    @Test
    void ba171_01_资源键随意图落账_跟进回调推进_反馈体含审批编号() {
        CapturingLedger ledger = new CapturingLedger();
        List<String> followUpCalls = new ArrayList<>();
        ToolGateway gateway = new ToolGateway(new ToolRegistry(List.of(r3ServiceTool())),
                new ToolPolicy(Set.of("service.restart")), POOL, FIXED, null, null, null,
                ledger, (intentId, requestedKey) ->
                        followUpCalls.add(intentId + "|" + requestedKey));
        ToolGateway.ToolInvocationResult result = gateway.invoke(
                r3Invocation(Map.of("service", "checkout", "reason", "r")));
        assertThat(result.kind()).isEqualTo(ToolGateway.ToolInvocationResult.Kind.VALIDATE_ONLY);
        // 资源键 = args["service"] 原样（替代旧的恒 null）
        assertThat(ledger.rows).hasSize(1);
        ActionIntent intent = ledger.rows.get(0).intent();
        assertThat(intent.requestedResourceKey()).isEqualTo("checkout");
        assertThat(intent.risk()).isEqualTo(ToolRisk.R3);
        // 跟进回调收到同 intentId + 资源键（审批队列推进入口）
        assertThat(followUpCalls)
                .containsExactly(intent.intentId() + "|checkout");
        // 模型可见反馈体：待审批语义 + 审批编号（intentId 前 8 位）
        String body = new String(result.body(), java.nio.charset.StandardCharsets.UTF_8);
        assertThat(body).contains("PENDING_APPROVAL")
                .contains("已提交人工审批")
                .contains(intent.intentId().toString().substring(0, 8))
                .contains("请勿重试同一操作");
    }

    @Test
    void ba171_02_跟进回调异常不炸调查_意图留账() {
        CapturingLedger ledger = new CapturingLedger();
        ToolGateway gateway = new ToolGateway(new ToolRegistry(List.of(r3ServiceTool())),
                new ToolPolicy(Set.of("service.restart")), POOL, FIXED, null, null, null,
                ledger, (intentId, requestedKey) -> {
                    throw new IllegalStateException("resolver 炸了");
                });
        ToolGateway.ToolInvocationResult result = gateway.invoke(
                r3Invocation(Map.of("service", "checkout")));
        // 回调失败降级为日志：调用结局与意图落账不受影响（意图留 OPEN 未解析=诚实状态）
        assertThat(result.kind()).isEqualTo(ToolGateway.ToolInvocationResult.Kind.VALIDATE_ONLY);
        assertThat(ledger.rows).hasSize(1);
        assertThat(ledger.rows.get(0).intent().requestedResourceKey()).isEqualTo("checkout");
    }

    @Test
    void ba171_03_无service参数_资源键null_跟进回调跳过() {
        CapturingLedger ledger = new CapturingLedger();
        List<String> followUpCalls = new ArrayList<>();
        ToolGateway gateway = new ToolGateway(new ToolRegistry(List.of(writeTool())),
                new ToolPolicy(Set.of("write.tool")), POOL, FIXED, null, null, null,
                ledger, (intentId, requestedKey) ->
                        followUpCalls.add(intentId + "|" + requestedKey));
        ToolGateway.ToolInvocationResult result = gateway.invoke(invocation());
        assertThat(result.kind()).isEqualTo(ToolGateway.ToolInvocationResult.Kind.VALIDATE_ONLY);
        assertThat(ledger.rows.get(0).intent().requestedResourceKey()).isNull();
        assertThat(followUpCalls).isEmpty(); // 无资源键无法权威解析——跳过不猜
    }
}
