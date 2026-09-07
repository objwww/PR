package com.objwww.pr.control.alert.application.agent;

import com.objwww.pr.control.alert.application.tool.ToolGateway;
import com.objwww.pr.control.alert.application.tool.ToolRegistry;
import com.objwww.pr.control.alert.domain.agent.AgentProfile;
import com.objwww.pr.control.alert.domain.evidence.EvidenceEnvelope;
import com.objwww.pr.control.alert.domain.tool.ToolControlPlaneException;
import com.objwww.pr.control.alert.domain.tool.ToolDefinition;
import com.objwww.pr.control.alert.domain.tool.ToolInvocationState;
import com.objwww.pr.control.alert.domain.tool.ToolReasonCode;
import com.objwww.pr.control.alert.domain.tool.ToolRisk;
import com.objwww.pr.control.infrastructure.tool.ReplayToolExecutor;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Logs Agent 单测（AM4 M4-28，TDD 先行，replay fixture 数据面——P0-7 无实时源）：
 * fixture 回放产证据、NO_DATA 零证据、status=error 归因、与 Metrics 工具集隔离
 * （agent 构造面 + 策略双闸面）。
 */
class LogsAgentTest {

    private final ReplayAgentFixtures.MemEvidence evidence = new ReplayAgentFixtures.MemEvidence();

    private ToolRegistry registry() {
        return new ToolRegistry(List.of(
                new ToolRegistry.Registration(
                        LogsAgent.toolDefinition(2000, 65536),
                        new ReplayToolExecutor(ReplayAgentFixtures
                                .fixture("/fixtures/logs/query-f1.json"))),
                new ToolRegistry.Registration(
                        prometheusToolDefinition(),
                        new ReplayToolExecutor(ReplayAgentFixtures
                                .fixture("/fixtures/logs/query-f1.json")))));
    }

    private LogsAgent agent(Set<String> allowlist, Set<String> policyTools) {
        AgentProfile profile = new AgentProfile("logs", "1", "prompt-logs", "pv",
                allowlist, Map.of(), Map.of("type", "object"));
        return new LogsAgent(profile, registry(),
                ReplayAgentFixtures.gateway(registry(), policyTools),
                evidence, evidence.ledger, ReplayAgentFixtures.MAPPER);
    }

    @Test
    void fixtureReplayProducesEvidence() {
        LogsAgent agent = agent(Set.of("logs.query"), Set.of("logs.query"));

        LogsAgent.AgentResult result = agent.investigate(context(),
                new LogsAgent.LogsQuery("1757059200", "1757059260"));

        assertThat(result.outcome()).isEqualTo(LogsAgent.AgentOutcome.EVIDENCE_PRODUCED);
        EvidenceEnvelope stored = EvidenceEnvelope.verify(evidence.rows.get(0));
        assertThat(stored.evidenceType()).isEqualTo("logs.query");
        assertThat(stored.source()).isEqualTo("logs");
        assertThat(stored.canonicalPayload()).contains("pool exhausted");
        assertThat(evidence.ledger.rows.get(0).state()).isEqualTo(ToolInvocationState.SUCCESS);
    }

    @Test
    void emptyResultIsNoDataWithoutEvidence() {
        LogsAgent agent = agent(Set.of("logs.query"), Set.of("logs.query"),
                """
                        {"status":"success","data":{"result":[]}}
                        """.getBytes(StandardCharsets.UTF_8));

        LogsAgent.AgentResult result = agent.investigate(context(),
                new LogsAgent.LogsQuery("1757059200", "1757059260"));

        assertThat(result.outcome()).isEqualTo(LogsAgent.AgentOutcome.NO_DATA);
        assertThat(evidence.rows).isEmpty();
        assertThat(evidence.ledger.rows.get(0).state()).isEqualTo(ToolInvocationState.SUCCESS);
    }

    @Test
    void sourceErrorFailsLedgerRemote4xx() {
        LogsAgent agent = agent(Set.of("logs.query"), Set.of("logs.query"),
                """
                        {"status":"error","error":"parse error: invalid query"}
                        """.getBytes(StandardCharsets.UTF_8));

        LogsAgent.AgentResult result = agent.investigate(context(),
                new LogsAgent.LogsQuery("1757059200", "1757059260"));

        assertThat(result.outcome()).isEqualTo(LogsAgent.AgentOutcome.FAILED);
        assertThat(evidence.rows).isEmpty();
        assertThat(evidence.ledger.rows.get(0).state()).isEqualTo(ToolInvocationState.FAILED);
        assertThat(evidence.ledger.rows.get(0).reason())
                .isEqualTo(ToolReasonCode.REMOTE_4XX);
    }

    @Test
    void toolsetIsolationAtProfileLevel() {
        // 与 Metrics 工具集隔离：logs Agent 不允许携带 prometheus 工具 allowlist
        assertThatThrownBy(() -> agent(Set.of("prometheus.query"), Set.of("logs.query")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("logs.query");
    }

    @Test
    void toolsetIsolationAtPolicyGate() {
        // 策略只允许 logs 工具——越闸调用 prometheus 工具被控制面 POLICY_DENIED 拒绝
        ToolGateway gateway = ReplayAgentFixtures.gateway(registry(), Set.of("logs.query"));

        assertThatThrownBy(() -> gateway.invoke(new ToolGateway.ToolInvocation(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), 1,
                "prometheus.query", "1", "2026-09-05T07:50:00Z/2026-09-05T08:00:00Z",
                Map.of("query", "up", "start", "0", "end", "1", "step", "30s"), null)))
                .isInstanceOf(ToolControlPlaneException.class)
                .hasMessageContaining("POLICY_DENIED");
    }

    // ------------------------------------------------------------------ 夹具

    private LogsAgent agent(Set<String> allowlist, Set<String> policyTools, byte[] payload) {
        ToolRegistry registry = new ToolRegistry(List.of(new ToolRegistry.Registration(
                LogsAgent.toolDefinition(2000, 65536), new ReplayToolExecutor(payload))));
        AgentProfile profile = new AgentProfile("logs", "1", "prompt-logs", "pv",
                allowlist, Map.of(), Map.of("type", "object"));
        return new LogsAgent(profile, registry,
                ReplayAgentFixtures.gateway(registry, policyTools),
                evidence, evidence.ledger, ReplayAgentFixtures.MAPPER);
    }

    private static ToolDefinition prometheusToolDefinition() {
        return MetricsAgent.toolDefinition(2000, 65536);
    }

    private static LogsAgent.CallContext context() {
        return new LogsAgent.CallContext(UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), 1, 0L, null,
                "2026-09-05T07:50:00Z/2026-09-05T08:00:00Z");
    }
}
