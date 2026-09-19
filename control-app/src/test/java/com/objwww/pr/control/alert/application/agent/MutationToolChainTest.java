package com.objwww.pr.control.alert.application.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.objwww.pr.control.alert.application.mutation.ActionIntentLedger;
import com.objwww.pr.control.alert.application.mutation.IntentFollowUp;
import com.objwww.pr.control.alert.application.tool.MutationToolCatalog;
import com.objwww.pr.control.alert.application.tool.ToolGateway;
import com.objwww.pr.control.alert.application.tool.ToolRegistry;
import com.objwww.pr.control.alert.domain.agent.AgentProfile;
import com.objwww.pr.control.alert.domain.event.RcaEventAppender;
import com.objwww.pr.control.alert.domain.evidence.EvidenceEnvelope;
import com.objwww.pr.control.alert.domain.evidence.EvidenceRepository;
import com.objwww.pr.control.alert.domain.mutation.ActionIntent;
import com.objwww.pr.control.alert.domain.tool.RcaToolInvocationLedger;
import com.objwww.pr.control.alert.domain.tool.ToolControlPlaneException;
import com.objwww.pr.control.alert.domain.tool.ToolControlReason;
import com.objwww.pr.control.alert.domain.tool.ToolInvocationState;
import com.objwww.pr.control.alert.domain.tool.ToolPolicy;
import com.objwww.pr.control.alert.domain.tool.ToolReasonCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * BA-171 写类工具审批链（E 脸，进程内零网络）：主 Agent 经 DirectReadToolAgent 调
 * service.restart —— Gateway VALIDATE_ONLY 短路（R3 占位执行器零触达，触达即炸）
 * → 意图落账（资源键 = args["service"] 原样）→ IntentFollowUp 自动进审批队列 →
 * 待审批反馈体铸证据（PENDING_APPROVAL + 审批编号，模型经证据窗可读）→ 账本
 * SUCCESS + EVIDENCE_PRODUCED。旧行为（VALIDATE_ONLY 当终止族抛 POLICY_DENIED）
 * 会把主 Agent step 打 DEAD，本链钉住「待审批模型可见」新语义。
 */
class MutationToolChainTest {

    private static final UUID RUN = UUID.randomUUID();
    private static final UUID TASK = UUID.randomUUID();
    private static final UUID ATTEMPT = UUID.randomUUID();
    private static final String TIME_RANGE = "2026-09-18T00:00:00Z/2026-09-18T00:05:00Z";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final MemLedger ledger = new MemLedger();
    private final MemEvidence evidence = new MemEvidence();

    /** 意图台账捕获件（ToolGatewayIntentLedgerTest.CapturingLedger 同形，跨包不可复用故复制最小面） */
    static final class CapturingIntentLedger implements ActionIntentLedger {
        final List<ActionIntent> rows = new ArrayList<>();

        @Override
        public long record(ActionIntent intent, RcaEventAppender.EventDraft intentEvent) {
            rows.add(intent);
            return 1;
        }
    }

    private DirectReadToolAgent restartAgent(CapturingIntentLedger intents,
            IntentFollowUp followUp, ToolPolicy policy, ExecutorService pool) {
        ToolRegistry registry = new ToolRegistry(List.of(new ToolRegistry.Registration(
                MutationToolCatalog.serviceRestart(4_000, 65_536),
                MutationToolCatalog.nonExecutablePlaceholder(
                        MutationToolCatalog.TOOL_SERVICE_RESTART))));
        ToolGateway gateway = new ToolGateway(registry, policy, pool,
                java.time.Clock.systemUTC(), null, null, null, intents, followUp);
        return new DirectReadToolAgent(profile(), DirectReadToolCatalog.spec(
                MutationToolCatalog.TOOL_SERVICE_RESTART, "mutation.intent", "mutation"),
                registry, gateway, evidence, ledger, MAPPER);
    }

    @Test
    @DisplayName("BA-171 E 脸：调 service.restart → 意图落账+审批推进回调+待审批证据+账本 SUCCESS")
    void restartCallLandsIntentApprovalFollowUpAndPendingEvidence() {
        CapturingIntentLedger intents = new CapturingIntentLedger();
        List<String> followUpCalls = new ArrayList<>();
        ExecutorService pool = Executors.newFixedThreadPool(2);
        DirectReadToolAgent agent = restartAgent(intents,
                (intentId, requestedKey) -> followUpCalls.add(intentId + "|" + requestedKey),
                new ToolPolicy(Set.of(MutationToolCatalog.TOOL_SERVICE_RESTART)), pool);

        SingleToolEvidenceAgent.AgentResult result = agent.investigate(context(),
                Map.of("service", "checkout", "reason", "告警自愈建议重启"));

        // 结局=证据产出（不再 POLICY_DENIED 打 DEAD），执行器零触达（触达即 ISE 炸出）
        assertThat(result.outcome())
                .isEqualTo(SingleToolEvidenceAgent.AgentOutcome.EVIDENCE_PRODUCED);
        assertThat(result.errorClass()).isNull();
        // 意图落账：资源键 = args["service"] 原样（审批解析输入，授权身份仍只信 Resolver）
        assertThat(intents.rows).hasSize(1);
        ActionIntent intent = intents.rows.get(0);
        assertThat(intent.requestedResourceKey()).isEqualTo("checkout");
        // 审批推进回调收到同 intentId + 资源键
        assertThat(followUpCalls).containsExactly(intent.intentId() + "|checkout");
        // 待审批反馈铸证据：类型/来源面 + PENDING_APPROVAL + 审批编号（intentId 前 8 位）
        assertThat(result.evidenceIds()).hasSize(1);
        EvidenceEnvelope stored = evidence.rows.get(0);
        EvidenceEnvelope.verify(stored);
        assertThat(stored.evidenceType()).isEqualTo("mutation.intent");
        assertThat(stored.source()).isEqualTo("mutation");
        assertThat(stored.canonicalPayload()).contains("PENDING_APPROVAL")
                .contains(intent.intentId().toString().substring(0, 8))
                .contains("请勿重试同一操作");
        // 账本：PENDING 先行 → SUCCESS 收尾（调用面事实 = 意图受理成功）
        assertThat(ledger.rows).hasSize(1);
        assertThat(ledger.rows.get(0).state()).isEqualTo(ToolInvocationState.SUCCESS);
        pool.shutdownNow();
    }

    @Test
    @DisplayName("BA-171 双闸之二：allowlist 未列入 service.restart → POLICY_DENIED，意图/账本零落")
    void policyGateStillRejectsUnlistedMutationTool() {
        CapturingIntentLedger intents = new CapturingIntentLedger();
        ExecutorService pool = Executors.newFixedThreadPool(2);
        DirectReadToolAgent agent = restartAgent(intents,
                (intentId, requestedKey) -> { },
                new ToolPolicy(Set.of("prometheus.instant")), pool);

        assertThatThrownBy(() -> agent.investigate(context(),
                        Map.of("service", "checkout")))
                .isInstanceOf(ToolControlPlaneException.class)
                .hasFieldOrPropertyWithValue("reason", ToolControlReason.POLICY_DENIED);
        assertThat(intents.rows).as("策略拒先于意图落账").isEmpty();
        pool.shutdownNow();
    }

    // ------------------------------------------------------------------ 辅助

    private static AgentProfile profile() {
        Map<String, Object> schema = Map.of("type", "object");
        Map<com.objwww.pr.control.alert.domain.budget.BudgetKind, Long> budget =
                Map.of(com.objwww.pr.control.alert.domain.budget.BudgetKind.TOOL_CALL, 10L);
        return new AgentProfile("ba171-mutation", "1", "prompt", "am4-native-v7",
                Set.of(MutationToolCatalog.TOOL_SERVICE_RESTART), budget, schema);
    }

    private static SingleToolEvidenceAgent.CallContext context() {
        return new SingleToolEvidenceAgent.CallContext(RUN, TASK, ATTEMPT, 1L, 3L,
                null, TIME_RANGE);
    }

    /** 调用账本内存件（En05DirectToolChainTest 同形） */
    private static class MemLedger implements RcaToolInvocationLedger {
        record Row(UUID operationId, UUID runId, UUID taskId, UUID attemptId, long callSeq,
                String toolName, String toolVersion, String actionDigest,
                ToolInvocationState state, ToolReasonCode reason) {
        }

        final List<Row> rows = new ArrayList<>();

        @Override
        public void open(InvocationIdentity identity) {
            rows.add(new Row(identity.operationId(), identity.runId(), identity.taskId(),
                    identity.attemptId(), identity.callSeq(), identity.toolName(),
                    identity.toolVersion(), identity.actionDigest(),
                    ToolInvocationState.PENDING, null));
        }

        @Override
        public boolean succeed(UUID operationId) {
            return settle(operationId, ToolInvocationState.SUCCESS, null);
        }

        @Override
        public boolean fail(UUID operationId, ToolInvocationState terminal,
                ToolReasonCode reasonCode) {
            return settle(operationId, terminal, reasonCode);
        }

        private boolean settle(UUID id, ToolInvocationState state, ToolReasonCode reason) {
            for (int k = 0; k < rows.size(); k++) {
                Row row = rows.get(k);
                if (row.operationId().equals(id) && row.state() == ToolInvocationState.PENDING) {
                    rows.set(k, new Row(row.operationId(), row.runId(), row.taskId(),
                            row.attemptId(), row.callSeq(), row.toolName(), row.toolVersion(),
                            row.actionDigest(), state, reason));
                    return true;
                }
            }
            return false;
        }
    }

    /** 证据仓储内存件 */
    private static class MemEvidence implements EvidenceRepository {
        final List<EvidenceEnvelope> rows = new ArrayList<>();

        @Override
        public void insert(EvidenceEnvelope envelope) {
            rows.add(envelope);
        }

        @Override
        public Optional<EvidenceEnvelope> findById(UUID id) {
            return rows.stream().filter(e -> e.evidenceId().equals(id)).findFirst();
        }

        @Override
        public List<EvidenceEnvelope> findByRunId(UUID runId) {
            return rows.stream().filter(e -> e.runId().equals(runId)).toList();
        }
    }
}
