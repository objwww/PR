package com.objwww.pr.control.alert.application.agent;

import com.objwww.pr.control.alert.application.tool.ToolGateway;
import com.objwww.pr.control.alert.application.tool.ToolRegistry;
import com.objwww.pr.control.alert.domain.agent.AgentProfile;
import com.objwww.pr.control.alert.domain.evidence.EvidenceEnvelope;
import com.objwww.pr.control.alert.domain.tool.ToolControlPlaneException;
import com.objwww.pr.control.alert.domain.tool.ToolDefinition;
import com.objwww.pr.control.alert.domain.tool.ToolInvocationState;
import com.objwww.pr.control.alert.domain.tool.ToolRisk;
import com.objwww.pr.control.infrastructure.tool.ReplayToolExecutor;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Change Agent 单测（AM4 M4-29，TDD 先行，replay fixture 数据面）：
 * 只读变更查询产证据；无写权限——写面工具被控制面拒绝（策略闸 POLICY_DENIED 终止族）
 * 或即便误入策略也被 R2 风险级 VALIDATE_ONLY 拦截（零执行，只记意图）。
 */
class ChangeAgentTest {

    private final ReplayAgentFixtures.MemEvidence evidence = new ReplayAgentFixtures.MemEvidence();

    @Test
    void fixtureReplayProducesEvidence() {
        ToolRegistry registry = new ToolRegistry(List.of(new ToolRegistry.Registration(
                ChangeAgent.toolDefinition(2000, 65536),
                new ReplayToolExecutor(ReplayAgentFixtures
                        .fixture("/fixtures/change/query-f1.json")))));
        ChangeAgent agent = agent(registry, Set.of("change.query"));

        ChangeAgent.AgentResult result = agent.investigate(context(),
                new ChangeAgent.ChangeQuery("1757050000", "1757060000"));

        assertThat(result.outcome()).isEqualTo(ChangeAgent.AgentOutcome.EVIDENCE_PRODUCED);
        EvidenceEnvelope stored = EvidenceEnvelope.verify(evidence.rows.get(0));
        assertThat(stored.evidenceType()).isEqualTo("change.query");
        assertThat(stored.source()).isEqualTo("change");
        assertThat(stored.canonicalPayload()).contains("CHG-2026-0901-04");
        assertThat(evidence.ledger.rows.get(0).state()).isEqualTo(ToolInvocationState.SUCCESS);
    }

    @Test
    void writeToolOutsidePolicyIsControlPlaneRejected() {
        // 验收行：无权限工具调用被控制面拒绝——change.execute 不在策略允许集
        ToolGateway gateway = ReplayAgentFixtures.gateway(registryWithWriteTool(),
                Set.of("change.query"));

        assertThatThrownBy(() -> gateway.invoke(new ToolGateway.ToolInvocation(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), 1,
                "change.execute", "1", "2026-09-05T07:50:00Z/2026-09-05T08:00:00Z",
                Map.of("change_id", "CHG-2026-0901-04"), null)))
                .isInstanceOf(ToolControlPlaneException.class)
                .hasMessageContaining("POLICY_DENIED");
        assertThat(evidence.rows).isEmpty();
    }

    @Test
    void writeToolInsidePolicyIsStillValidateOnly() {
        // 第二道闸：即便误入策略，R2 非可执行 → VALIDATE_ONLY 记意图零执行
        ToolGateway gateway = ReplayAgentFixtures.gateway(registryWithWriteTool(),
                Set.of("change.query", "change.execute"));

        ToolGateway.ToolInvocationResult result = gateway.invoke(new ToolGateway.ToolInvocation(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), 1,
                "change.execute", "1", "2026-09-05T07:50:00Z/2026-09-05T08:00:00Z",
                Map.of("change_id", "CHG-2026-0901-04"), null));

        assertThat(result.kind().name()).isEqualTo("VALIDATE_ONLY");
        assertThat(result.body()).isNull();
    }

    @Test
    void changeAgentCannotCarryWriteToolInAllowlist() {
        assertThatThrownBy(() -> agent(registryWithWriteTool(),
                Set.of("change.query", "change.execute")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("change.query");
    }

    // ------------------------------------------------------------------ 夹具

    private ToolRegistry registryWithWriteTool() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("change_id", Map.of("type", "string"));
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        return new ToolRegistry(List.of(
                new ToolRegistry.Registration(
                        ChangeAgent.toolDefinition(2000, 65536),
                        new ReplayToolExecutor(ReplayAgentFixtures
                                .fixture("/fixtures/change/query-f1.json"))),
                new ToolRegistry.Registration(
                        new ToolDefinition("change.execute", "1", schema, ToolRisk.R2,
                                2000, 65536),
                        execution -> {
                            throw new AssertionError("R2 写工具绝不可执行（零执行红线）");
                        })));
    }

    private ChangeAgent agent(ToolRegistry registry, Set<String> allowlist) {
        AgentProfile profile = new AgentProfile("change", "1", "prompt-change", "pv",
                allowlist, Map.of(), Map.of("type", "object"));
        return new ChangeAgent(profile, registry,
                ReplayAgentFixtures.gateway(registry, Set.of("change.query")),
                evidence, evidence.ledger, ReplayAgentFixtures.MAPPER);
    }

    private static ChangeAgent.CallContext context() {
        return new ChangeAgent.CallContext(UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), 1, 0L, null,
                "2026-09-05T07:50:00Z/2026-09-05T08:00:00Z");
    }
}
