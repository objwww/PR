package com.objwww.pr.control.alert.application.tool;

import com.objwww.pr.control.alert.domain.event.DecisionProvenance;
import com.objwww.pr.control.alert.domain.event.RcaEventAppender;
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
 * ToolGateway 意图事件 provenance 块单测（PA-A5）：装配溯源标签时 TOOL_INTENT_VALIDATED
 * 载荷携带统一 provenance JSON（build sha + policy version + 工具 schema 锚）；
 * 未装配（null）不含该块（渐进采纳，旧装配零漂移）。
 */
class ToolGatewayProvenanceTest {

    private static final UUID RUN = new UUID(0L, 77L);
    private static final Clock FIXED = Clock.fixed(Instant.ofEpochMilli(2_000_000),
            ZoneOffset.UTC);
    private static final ExecutorService POOL = Executors.newSingleThreadExecutor();

    @AfterAll
    static void shutdownPool() {
        POOL.shutdownNow();
    }

    record AppendedEvent(UUID runId, String eventType, String payloadJson) {
    }

    static final class CapturingAppender implements RcaEventAppender {
        final List<AppendedEvent> events = new ArrayList<>();

        @Override
        public long append(UUID runId, EventDraft draft) {
            events.add(new AppendedEvent(runId, draft.eventType(), draft.payloadJson()));
            return events.size();
        }

        @Override
        public long appendIndependent(UUID runId, EventDraft draft) {
            return append(runId, draft);
        }
    }

    private static ToolRegistry.Registration writeTool() {
        ToolDefinition definition = new ToolDefinition("write.tool", "1.0.0",
                Map.of("type", "object", "properties", Map.of("q", Map.of("type", "string"))),
                ToolRisk.R2, 1_000, 16);
        return new ToolRegistry.Registration(definition, execution -> new byte[1]);
    }

    private static ToolGateway.ToolInvocation invocation() {
        return new ToolGateway.ToolInvocation(RUN, RUN, RUN, 1, "write.tool", "1.0.0",
                "2026-09-15T00:00:00Z/2026-09-15T01:00:00Z", Map.of("q", "x"), null);
    }

    @Test
    void utG01_装配provenance_意图事件携带统一溯源块() {
        CapturingAppender events = new CapturingAppender();
        DecisionProvenance tags = DecisionProvenance.empty("pa-prod-v1")
                .withAgentBuildSha("sha-pa5-1");
        ToolGateway gateway = new ToolGateway(new ToolRegistry(List.of(writeTool())),
                new ToolPolicy(Set.of("write.tool")), POOL, FIXED, events, null, tags);
        gateway.invoke(invocation());
        assertThat(events.events).hasSize(1);
        AppendedEvent event = events.events.get(0);
        assertThat(event.eventType()).isEqualTo("TOOL_INTENT_VALIDATED");
        assertThat(event.payloadJson())
                .contains("\"kind\":\"TOOL_INTENT_VALIDATED\"")
                .contains("\"action_digest\"")
                .contains("agent_build_sha")
                .contains("sha-pa5-1")
                .contains("pa-prod-v1")
                .contains("tool_schema_hash");
    }

    @Test
    void utG02_未装配provenance_意图事件不含该块_旧装配零漂移() {
        CapturingAppender events = new CapturingAppender();
        ToolGateway gateway = new ToolGateway(new ToolRegistry(List.of(writeTool())),
                new ToolPolicy(Set.of("write.tool")), POOL, FIXED, events);
        gateway.invoke(invocation());
        assertThat(events.events).hasSize(1);
        assertThat(events.events.get(0).payloadJson()).doesNotContain("provenance");
    }
}
