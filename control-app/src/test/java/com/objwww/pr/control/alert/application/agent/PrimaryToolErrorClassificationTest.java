package com.objwww.pr.control.alert.application.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.objwww.pr.control.alert.application.tool.ToolRegistry;
import com.objwww.pr.control.alert.domain.agent.AgentPhase;
import com.objwww.pr.control.alert.domain.agent.AgentProfile;
import com.objwww.pr.control.alert.domain.agent.RoleRuntimeKind;
import com.objwww.pr.control.alert.domain.budget.BudgetKind;
import com.objwww.pr.control.alert.domain.tool.RcaToolInvocationLedger;
import com.objwww.pr.control.alert.domain.tool.ToolControlPlaneException;
import com.objwww.pr.control.alert.domain.tool.ToolControlReason;
import com.objwww.pr.control.alert.domain.tool.ToolDefinition;
import com.objwww.pr.control.alert.domain.tool.ToolInvocationState;
import com.objwww.pr.control.alert.domain.tool.ToolModelVisibleException;
import com.objwww.pr.control.alert.domain.tool.ToolModelVisibleReason;
import com.objwww.pr.control.alert.domain.tool.ToolReasonCode;
import com.objwww.pr.control.alert.domain.tool.ToolRisk;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 主端口错误分类 UT（A0 补充方案 §3/AS-03/04/05）：AgentResult.errorClass 封闭
 * 解析——预算/熔断=控制终止（不当网络错误重试）；模型可见原因保真；未知值显式
 * INTERNAL_ERROR 不默认 REMOTE_UNAVAILABLE；越权零触网有界反馈；装配缺口
 * CONFIGURATION_ERROR 控制终止。
 */
class PrimaryToolErrorClassificationTest {

    private static final UUID RUN = UUID.randomUUID();
    private static final UUID TASK = UUID.randomUUID();
    private static final UUID ATTEMPT = UUID.randomUUID();

    /** 罐头结局桩：绕物理执行，专测端口分类面 */
    private static final class StubAgent extends SingleToolEvidenceAgent {
        private final AgentResult canned;

        StubAgent(AgentResult canned) {
            super(profile(), spec(), new ToolRegistry(List.of(new ToolRegistry.Registration(
                    definition(), args -> new byte[0]))), invocation -> null,
                    evidenceNoop(), ledgerNoop(), new ObjectMapper());
            this.canned = canned;
        }

        @Override
        public AgentResult investigate(CallContext ctx, Map<String, Object> args) {
            return canned;
        }
    }

    private static AgentProfile profile() {
        return new AgentProfile("primary", "1", "p", "pv", Set.of("stub.tool"),
                Map.of(BudgetKind.STEP, 8L), Map.of("type", "object"), Map.of(),
                AgentPhase.PRIMARY, RoleRuntimeKind.BOUNDED_LLM, Set.of(), 8, "stub");
    }

    private static SingleToolEvidenceAgent.ToolSpec spec() {
        return new SingleToolEvidenceAgent.ToolSpec("stub.tool", "1", "test.evidence",
                "test-src");
    }

    private static ToolDefinition definition() {
        Map<String, Object> properties = Map.of("service",
                Map.of("type", "string", "maxLength", 64));
        Map<String, Object> schema = Map.of("type", "object",
                "properties", properties);
        return new ToolDefinition("stub.tool", "1", schema, ToolRisk.R0, 1_000, 4_096);
    }

    private static com.objwww.pr.control.alert.domain.evidence.EvidenceRepository
            evidenceNoop() {
        return new com.objwww.pr.control.alert.domain.evidence.EvidenceRepository() {
            @Override
            public void insert(
                    com.objwww.pr.control.alert.domain.evidence.EvidenceEnvelope envelope) {
            }

            @Override
            public java.util.Optional<com.objwww.pr.control.alert.domain.evidence.EvidenceEnvelope>
                    findById(UUID evidenceId) {
                return java.util.Optional.empty();
            }

            @Override
            public java.util.List<com.objwww.pr.control.alert.domain.evidence.EvidenceEnvelope>
                    findByRunId(UUID runId) {
                return List.of();
            }
        };
    }

    private static RcaToolInvocationLedger ledgerNoop() {
        return new RcaToolInvocationLedger() {
            @Override
            public void open(InvocationIdentity identity) {
            }

            @Override
            public boolean succeed(UUID operationId) {
                return true;
            }

            @Override
            public boolean fail(UUID operationId, ToolInvocationState terminal,
                    ToolReasonCode reasonCode) {
                return true;
            }
        };
    }

    private static SingleToolEvidenceAgent.CallContext ctx() {
        return new SingleToolEvidenceAgent.CallContext(RUN, TASK, ATTEMPT, 1, 1L, null,
                "2026-09-13T00:00:00Z/2026-09-13T00:05:00Z", () -> { }, null);
    }

    private static PrimaryGatewayToolPort port(SingleToolEvidenceAgent agent) {
        Map<String, SingleToolEvidenceAgent> delegates = agent == null
                ? Map.of() : Map.of("stub.tool", agent);
        return new PrimaryGatewayToolPort(delegates, ledgerNoop(), Set.of("stub.tool"));
    }

    @Test
    @DisplayName("AS-03：预算耗尽=控制面终止（BUDGET_EXHAUSTED），不当网络错误重试")
    void budgetExhaustedIsControlTerminationNotRemote() {
        PrimaryGatewayToolPort p = port(new StubAgent(
                new SingleToolEvidenceAgent.AgentResult(
                        SingleToolEvidenceAgent.AgentOutcome.FAILED, List.of(),
                        "BUDGET_EXHAUSTED")));
        assertThatThrownBy(() -> p.invoke(ctx(), "stub.tool", Map.of()))
                .isInstanceOf(ToolControlPlaneException.class)
                .satisfies(e -> assertThat(((ToolControlPlaneException) e).reason())
                        .isEqualTo(ToolControlReason.BUDGET_EXHAUSTED));
    }

    @Test
    @DisplayName("AS-03：熔断触发=控制面终止（DOOM_LOOP_TRIPPED），零网络重试")
    void doomLoopTrippedIsControlTermination() {
        PrimaryGatewayToolPort p = port(new StubAgent(
                new SingleToolEvidenceAgent.AgentResult(
                        SingleToolEvidenceAgent.AgentOutcome.FAILED, List.of(),
                        "DOOM_LOOP_TRIPPED")));
        assertThatThrownBy(() -> p.invoke(ctx(), "stub.tool", Map.of()))
                .isInstanceOf(ToolControlPlaneException.class)
                .satisfies(e -> assertThat(((ToolControlPlaneException) e).reason())
                        .isEqualTo(ToolControlReason.DOOM_LOOP_TRIPPED));
    }

    @Test
    @DisplayName("分类保真：SOURCE_UNAVAILABLE 原样透传，不再统一折叠 REMOTE_UNAVAILABLE")
    void originalClassificationPreserved() {
        PrimaryGatewayToolPort p = port(new StubAgent(
                new SingleToolEvidenceAgent.AgentResult(
                        SingleToolEvidenceAgent.AgentOutcome.FAILED, List.of(),
                        "SOURCE_UNAVAILABLE")));
        assertThatThrownBy(() -> p.invoke(ctx(), "stub.tool", Map.of()))
                .isInstanceOf(ToolModelVisibleException.class)
                .satisfies(e -> assertThat(((ToolModelVisibleException) e).reason())
                        .isEqualTo(ToolModelVisibleReason.SOURCE_UNAVAILABLE));
    }

    @Test
    @DisplayName("未知/缺失分类 → 显式 INTERNAL_ERROR，不默认 REMOTE_UNAVAILABLE")
    void unknownClassBecomesInternalErrorExplicitly() {
        PrimaryGatewayToolPort messy = port(new StubAgent(
                new SingleToolEvidenceAgent.AgentResult(
                        SingleToolEvidenceAgent.AgentOutcome.FAILED, List.of(),
                        "SOME_UNMAPPED_CODE")));
        assertThatThrownBy(() -> messy.invoke(ctx(), "stub.tool", Map.of()))
                .isInstanceOf(ToolModelVisibleException.class)
                .satisfies(e -> assertThat(((ToolModelVisibleException) e).reason())
                        .isEqualTo(ToolModelVisibleReason.INTERNAL_ERROR));

        PrimaryGatewayToolPort bare = port(new StubAgent(
                new SingleToolEvidenceAgent.AgentResult(
                        SingleToolEvidenceAgent.AgentOutcome.FAILED, List.of(), null)));
        assertThatThrownBy(() -> bare.invoke(ctx(), "stub.tool", Map.of()))
                .isInstanceOf(ToolModelVisibleException.class)
                .satisfies(e -> assertThat(((ToolModelVisibleException) e).reason())
                        .isEqualTo(ToolModelVisibleReason.INTERNAL_ERROR));
    }

    @Test
    @DisplayName("AS-04：allowlist 外工具零触网有界反馈（TOOL_NOT_ALLOWED），权限不扩大")
    void outOfAllowlistToolRejectedBeforeAnyLookup() {
        PrimaryGatewayToolPort p = port(null); // delegates 空=零装配可达
        assertThatThrownBy(() -> p.invoke(ctx(), "rogue.tool", Map.of()))
                .isInstanceOf(ToolModelVisibleException.class)
                .satisfies(e -> assertThat(((ToolModelVisibleException) e).reason())
                        .isEqualTo(ToolModelVisibleReason.TOOL_NOT_ALLOWED));
    }

    @Test
    @DisplayName("AS-05：allowlist 内但装配缺席 → CONFIGURATION_ERROR 控制终止")
    void allowlistedButUnwiredIsConfigurationError() {
        PrimaryGatewayToolPort p = port(null);
        assertThatThrownBy(() -> p.invoke(ctx(), "stub.tool", Map.of()))
                .isInstanceOf(ToolControlPlaneException.class)
                .satisfies(e -> assertThat(((ToolControlPlaneException) e).reason())
                        .isEqualTo(ToolControlReason.CONFIGURATION_ERROR));
    }
}
