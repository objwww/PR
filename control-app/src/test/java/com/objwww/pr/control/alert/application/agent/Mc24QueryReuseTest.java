package com.objwww.pr.control.alert.application.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.objwww.pr.control.alert.application.tool.ToolGateway;
import com.objwww.pr.control.alert.application.tool.ToolRegistry;
import com.objwww.pr.control.alert.domain.agent.AgentProfile;
import com.objwww.pr.control.alert.domain.budget.DoomLoopGuard;
import com.objwww.pr.control.alert.domain.evidence.EvidenceEnvelope;
import com.objwww.pr.control.alert.domain.evidence.EvidenceRepository;
import com.objwww.pr.control.alert.domain.tool.RcaToolInvocationLedger;
import com.objwww.pr.control.alert.domain.tool.ToolInvocationState;
import com.objwww.pr.control.alert.domain.tool.ToolPolicy;
import com.objwww.pr.control.alert.domain.tool.ToolReasonCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * MC24/P0-2 同现场复用（E 脸）：同参数换措辞（空白差异）→ 同 digest → 第二次调用
 * 复用既有证据、零新工具执行（假件计数断言）；新现场（新冻结窗）→ 指纹必变正常
 * 重查（新鲜度区分）；复用不触碰熔断计数（卡 ⑤）；预算照扣一次 TOOL_CALL。
 * 规范化器边界面见 {@link com.objwww.pr.control.alert.domain.tool.ArgsNormalizerTest}。
 */
class Mc24QueryReuseTest {

    private static final UUID RUN = UUID.randomUUID();
    private static final UUID TASK = UUID.randomUUID();
    private static final UUID ATTEMPT = UUID.randomUUID();
    private static final String WINDOW_A = "2026-09-10T00:00:00Z/2026-09-10T00:05:00Z";
    private static final String WINDOW_B = "2026-09-10T00:05:00Z/2026-09-10T00:10:00Z";
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final byte[] INSTANT_JSON = """
            {"status":"success","data":{"resultType":"vector",
              "result":[{"metric":{"service":"checkout"},"value":[1757059260,"0.42"]}]}}
            """.getBytes(StandardCharsets.UTF_8);

    private final MemLedger ledger = new MemLedger();
    private final MemEvidence evidence = new MemEvidence();
    private final RecordingDoomLoopGuard doom = new RecordingDoomLoopGuard();
    private final AtomicInteger executions = new AtomicInteger();
    private final ExecutorService pool = Executors.newFixedThreadPool(2);

    private DirectReadToolAgent agent;

    @BeforeEach
    void wire() {
        ToolRegistry registry = new ToolRegistry(List.of(new ToolRegistry.Registration(
                DirectReadToolCatalog.prometheusInstant(4_000, 65_536),
                exec -> {
                    executions.incrementAndGet();
                    return INSTANT_JSON;
                })));
        ToolGateway gateway = new ToolGateway(registry,
                new ToolPolicy(Set.of(DirectReadToolCatalog.TOOL_INSTANT)), pool,
                java.time.Clock.systemUTC(), null);
        agent = new DirectReadToolAgent(profile(),
                DirectReadToolCatalog.spec(DirectReadToolCatalog.TOOL_INSTANT,
                        "metrics.instant", "prometheus"),
                registry, gateway, evidence, ledger, MAPPER,
                new com.objwww.pr.control.alert.application.RunBudgetGate(
                        new com.objwww.pr.control.alert.infrastructure.InMemoryRunBudgetLedger()),
                doom);
    }

    @Test
    @DisplayName("MC24 A：同参数换措辞（空白差异）→ 复用既有证据，零新工具执行")
    void sameQueryDifferentWordingReusesEvidence() {
        SingleToolEvidenceAgent.AgentResult first =
                agent.investigate(context(1, WINDOW_A), Map.of("query", "up", "time", "1757059260"));
        SingleToolEvidenceAgent.AgentResult second =
                agent.investigate(context(2, WINDOW_A), Map.of("query", " up \n", "time", " 1757059260 "));

        assertThat(first.outcome())
                .isEqualTo(SingleToolEvidenceAgent.AgentOutcome.EVIDENCE_PRODUCED);
        assertThat(second.outcome())
                .isEqualTo(SingleToolEvidenceAgent.AgentOutcome.EVIDENCE_PRODUCED);
        assertThat(second.evidenceIds().get(0)).as("复用回喂同一证据行")
                .isEqualTo(first.evidenceIds().get(0));
        assertThat(executions.get()).as("零新工具执行（假件计数）").isEqualTo(1);
        assertThat(evidence.rows).as("证据账不落第二行").hasSize(1);
        assertThat(ledger.rows).as("复用仍落台账行（审计面）").hasSize(2);
        assertThat(ledger.rows.get(1).state()).isEqualTo(ToolInvocationState.SUCCESS);
        assertThat(ledger.rows.get(1).resultRef).as("复用行 result_ref 指向既有证据")
                .isEqualTo(first.evidenceIds().get(0));
    }

    @Test
    @DisplayName("MC24 B：同查询新现场（新冻结窗）→ 指纹必变，正常重查")
    void newFrozenWindowQueriesFresh() {
        agent.investigate(context(1, WINDOW_A), Map.of("query", "up", "time", "1757059260"));
        SingleToolEvidenceAgent.AgentResult fresh =
                agent.investigate(context(2, WINDOW_B), Map.of("query", "up", "time", "1757059260"));

        assertThat(fresh.outcome())
                .isEqualTo(SingleToolEvidenceAgent.AgentOutcome.EVIDENCE_PRODUCED);
        assertThat(executions.get()).as("新现场指纹必变——不误吃旧现场证据").isEqualTo(2);
        assertThat(evidence.rows).hasSize(2);
    }

    @Test
    @DisplayName("卡⑤：复用命中不触碰熔断计数（既非进展亦非无进展）")
    void reuseDoesNotTouchDoomLoopCounter() {
        agent.investigate(context(1, WINDOW_A), Map.of("query", "up", "time", "1757059260"));
        agent.investigate(context(2, WINDOW_A), Map.of("query", "up", "time", "1757059260"));

        assertThat(doom.records.get()).as("仅首次物理执行记录一次 progress").isEqualTo(1);
    }

    // ------------------------------------------------------------------ 假件

    private static AgentProfile profile() {
        Map<String, Object> schema = Map.of("type", "object");
        Map<com.objwww.pr.control.alert.domain.budget.BudgetKind, Long> budget =
                Map.of(com.objwww.pr.control.alert.domain.budget.BudgetKind.TOOL_CALL, 10L);
        return new AgentProfile("mc24-prometheus.instant", "1", "prompt", "am4-native-v1",
                Set.of(DirectReadToolCatalog.TOOL_INSTANT), budget, schema);
    }

    private static SingleToolEvidenceAgent.CallContext context(long callSeq,
            String timeRange) {
        return new SingleToolEvidenceAgent.CallContext(RUN, TASK, ATTEMPT, callSeq, 3L,
                null, timeRange);
    }

    static final class RecordingDoomLoopGuard extends DoomLoopGuard {
        final AtomicInteger records = new AtomicInteger();

        RecordingDoomLoopGuard() {
            super(DoomLoopGuard.permissive().policy());
        }

        @Override
        public boolean record(UUID taskId, String tool, String actionDigest,
                boolean progressed) {
            records.incrementAndGet();
            return super.record(taskId, tool, actionDigest, progressed);
        }
    }

    /** 调用账本内存件（En05 同形 + MC24 复用读面/结果引用） */
    static class MemLedger implements RcaToolInvocationLedger {
        record Row(UUID operationId, UUID runId, UUID taskId, UUID attemptId, long callSeq,
                String toolName, String toolVersion, String actionDigest,
                ToolInvocationState state, ToolReasonCode reason, UUID resultRef) {
        }

        final List<Row> rows = new ArrayList<>();

        @Override
        public void open(InvocationIdentity identity) {
            rows.add(new Row(identity.operationId(), identity.runId(), identity.taskId(),
                    identity.attemptId(), identity.callSeq(), identity.toolName(),
                    identity.toolVersion(), identity.actionDigest(),
                    ToolInvocationState.PENDING, null, null));
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

        @Override
        public boolean markResultRef(UUID operationId, UUID evidenceId) {
            for (int k = 0; k < rows.size(); k++) {
                Row row = rows.get(k);
                if (row.operationId().equals(operationId)
                        && row.state() == ToolInvocationState.PENDING) {
                    rows.set(k, new Row(row.operationId(), row.runId(), row.taskId(),
                            row.attemptId(), row.callSeq(), row.toolName(),
                            row.toolVersion(), row.actionDigest(), row.state(),
                            row.reason(), evidenceId));
                    return true;
                }
            }
            return false;
        }

        @Override
        public List<InvocationRecovery> findSuccessfulByRun(UUID runId) {
            return rows.stream()
                    .filter(r -> r.runId().equals(runId)
                            && r.state() == ToolInvocationState.SUCCESS
                            && r.resultRef != null)
                    .map(r -> new InvocationRecovery(r.operationId(), r.callSeq(),
                            r.attemptId(), r.actionDigest(), r.state(), r.resultRef))
                    .toList();
        }

        private boolean settle(UUID id, ToolInvocationState state, ToolReasonCode reason) {
            for (int k = 0; k < rows.size(); k++) {
                Row row = rows.get(k);
                if (row.operationId().equals(id) && row.state() == ToolInvocationState.PENDING) {
                    rows.set(k, new Row(row.operationId(), row.runId(), row.taskId(),
                            row.attemptId(), row.callSeq(), row.toolName(), row.toolVersion(),
                            row.actionDigest(), state, reason, row.resultRef()));
                    return true;
                }
            }
            return false;
        }
    }

    /** 证据仓储内存件 */
    static class MemEvidence implements EvidenceRepository {
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
