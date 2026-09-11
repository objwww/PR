package com.objwww.pr.control.alert.application.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.objwww.pr.control.alert.application.tool.ToolGateway;
import com.objwww.pr.control.alert.application.tool.ToolRegistry;
import com.objwww.pr.control.alert.domain.agent.AgentProfile;
import com.objwww.pr.control.alert.domain.evidence.EvidenceEnvelope;
import com.objwww.pr.control.alert.domain.evidence.EvidenceRepository;
import com.objwww.pr.control.alert.domain.tool.RcaToolInvocationLedger;
import com.objwww.pr.control.alert.domain.tool.ToolControlPlaneException;
import com.objwww.pr.control.alert.domain.tool.ToolControlReason;
import com.objwww.pr.control.alert.domain.tool.ToolDefinition;
import com.objwww.pr.control.alert.domain.tool.ToolInvocationState;
import com.objwww.pr.control.alert.domain.tool.ToolModelVisibleException;
import com.objwww.pr.control.alert.domain.tool.ToolModelVisibleReason;
import com.objwww.pr.control.alert.domain.tool.ToolPolicy;
import com.objwww.pr.control.alert.domain.tool.ToolReasonCode;
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

import static org.assertj.core.api.Assertions.assertThat;

/**
 * EN-05（T01/T02/T03 E 脸）受控直查链：DirectReadToolAgent 同构面按 §一 P0 工具装配
 * ——catalog → label_values → instant 三跳全经 Gateway 唯一咽喉（策略双闸/账本
 * PENDING 先行/证据落库），调用顺序由观测驱动：第一跳的目录/标签发现喂给下一跳参数
 * （T01"名称来自目录、真实 refs 可回读"）；T02 空结果如实 NO_DATA 零伪造；
 * T03 策略未列入的工具零执行（双闸之二在 Gateway，非执行器层）。
 */
class En05DirectToolChainTest {

    private static final UUID RUN = UUID.randomUUID();
    private static final UUID TASK = UUID.randomUUID();
    private static final UUID ATTEMPT = UUID.randomUUID();
    private static final String TIME_RANGE = "2026-09-10T00:00:00Z/2026-09-10T00:05:00Z";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final byte[] CATALOG_JSON = """
            {"status":"success","data":{"result":[
              {"name":"http_server_requests_seconds_count","type":"counter","unit":"requests"},
              {"name":"up","type":"gauge","unit":""}]}}
            """.getBytes(StandardCharsets.UTF_8);
    private static final byte[] LABELS_JSON = """
            {"status":"success","data":{"result":[{"value":"checkout"},{"value":"control-app"}]}}
            """.getBytes(StandardCharsets.UTF_8);
    private static final byte[] INSTANT_JSON = """
            {"status":"success","data":{"resultType":"vector",
              "result":[{"metric":{"service":"checkout"},"value":[1757059260,"0.42"]}]}}
            """.getBytes(StandardCharsets.UTF_8);
    private static final byte[] EMPTY_CATALOG_JSON = """
            {"status":"success","data":{"result":[]}}
            """.getBytes(StandardCharsets.UTF_8);

    private final MemLedger ledger = new MemLedger();
    private final MemEvidence evidence = new MemEvidence();

    /** T01 三跳链：每跳证据类型/来源/账本 SUCCESS；证据引用可回读 */
    @Test
    @DisplayName("T01：catalog→label_values→instant 三跳，三证据三账本行全 SUCCESS 可回读")
    void threeHopChainProducesThreeEvidenceFaces() {
        ToolRegistry registry = new ToolRegistry(List.of(
                reg(DirectReadToolCatalog.TOOL_CATALOG, CATALOG_JSON),
                reg(DirectReadToolCatalog.TOOL_LABEL_VALUES, LABELS_JSON),
                reg(DirectReadToolCatalog.TOOL_INSTANT, INSTANT_JSON)));
        ExecutorService pool = Executors.newFixedThreadPool(2);
        ToolGateway gateway = new ToolGateway(registry, policyFor(
                DirectReadToolCatalog.TOOL_CATALOG, DirectReadToolCatalog.TOOL_LABEL_VALUES,
                DirectReadToolCatalog.TOOL_INSTANT), pool,
                java.time.Clock.systemUTC(), null);
        DirectReadToolAgent catalog = agent(registry, gateway,
                DirectReadToolCatalog.TOOL_CATALOG, "metrics.catalog");
        DirectReadToolAgent labels = agent(registry, gateway,
                DirectReadToolCatalog.TOOL_LABEL_VALUES, "metrics.label_values");
        DirectReadToolAgent instant = agent(registry, gateway,
                DirectReadToolCatalog.TOOL_INSTANT, "metrics.instant");

        // 观测驱动顺序：目录 →（目录内名称喂给下一步查询面）标签 → 即时值
        SingleToolEvidenceAgent.AgentResult hop1 = catalog.investigate(
                context(1), Map.of("match", "http_server_requests"));
        SingleToolEvidenceAgent.AgentResult hop2 = labels.investigate(
                context(2), Map.of("label", "service"));
        SingleToolEvidenceAgent.AgentResult hop3 = instant.investigate(
                context(3), Map.of("query", "up", "time", "1757059260"));

        assertThat(List.of(hop1.outcome(), hop2.outcome(), hop3.outcome()))
                .containsOnly(SingleToolEvidenceAgent.AgentOutcome.EVIDENCE_PRODUCED);
        assertThat(evidence.rows).hasSize(3);
        assertThat(evidence.rows.stream().map(EvidenceEnvelope::evidenceType))
                .containsExactly("metrics.catalog", "metrics.label_values", "metrics.instant");
        assertThat(evidence.rows).allSatisfy(e ->
                assertThat(e.source()).isEqualTo("prometheus"));
        for (UUID id : List.of(hop1.evidenceIds().get(0), hop2.evidenceIds().get(0),
                hop3.evidenceIds().get(0))) {
            assertThat(evidence.findById(id)).as("证据引用可回读").isPresent();
        }
        assertThat(ledger.rows).hasSize(3);
        assertThat(ledger.rows).allSatisfy(r ->
                assertThat(r.state()).isEqualTo(ToolInvocationState.SUCCESS));
        pool.shutdownNow();
    }

    /** T02：目录查询空结果 → NO_DATA 如实呈现（零证据零伪造，不重试不降级） */
    @Test
    @DisplayName("T02：catalog 空目录如实 NO_DATA——零证据零伪造")
    void emptyCatalogIsHonestNoData() {
        ToolRegistry registry = new ToolRegistry(List.of(
                reg(DirectReadToolCatalog.TOOL_CATALOG, EMPTY_CATALOG_JSON)));
        ExecutorService pool = Executors.newFixedThreadPool(2);
        ToolGateway gateway = new ToolGateway(registry,
                policyFor(DirectReadToolCatalog.TOOL_CATALOG), pool,
                java.time.Clock.systemUTC(), null);
        DirectReadToolAgent catalog = agent(registry, gateway,
                DirectReadToolCatalog.TOOL_CATALOG, "metrics.catalog");

        SingleToolEvidenceAgent.AgentResult result = catalog.investigate(
                context(1), Map.of("match", "ghost_metric"));

        assertThat(result.outcome()).isEqualTo(SingleToolEvidenceAgent.AgentOutcome.NO_DATA);
        assertThat(result.errorClass()).as("正常空结果非故障").isNull();
        assertThat(result.evidenceIds()).isEmpty();
        assertThat(evidence.rows).as("零伪造证据").isEmpty();
        assertThat(ledger.rows.get(0).state()).isEqualTo(ToolInvocationState.SUCCESS);
        pool.shutdownNow();
    }

    /** T03：策略未列入的工具 → 双闸之二 POLICY_DENIED 终止族（执行器零触达） */
    @Test
    @DisplayName("T03：policy 未列入 prometheus.instant → POLICY_DENIED，执行器零触达")
    void policyGateRejectsUnlistedToolBeforeExecution() {
        List<Boolean> touched = new ArrayList<>();
        ToolRegistry registry = new ToolRegistry(List.of(
                new ToolRegistry.Registration(
                        DirectReadToolCatalog.prometheusInstant(4_000, 65_536),
                        exec -> {
                            touched.add(Boolean.TRUE);
                            return INSTANT_JSON;
                        })));
        ExecutorService pool = Executors.newFixedThreadPool(2);
        // 策略只列 catalog（M4-16：空策略构造期硬失败）——instant 未列入即被双闸之二拒
        ToolGateway gateway = new ToolGateway(registry,
                policyFor(DirectReadToolCatalog.TOOL_CATALOG), pool,
                java.time.Clock.systemUTC(), null);
        DirectReadToolAgent instant = agent(registry, gateway,
                DirectReadToolCatalog.TOOL_INSTANT, "metrics.instant");

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> instant.investigate(
                        context(1), Map.of("query", "up", "time", "1757059260")))
                .isInstanceOf(ToolControlPlaneException.class)
                .hasFieldOrPropertyWithValue("reason", ToolControlReason.POLICY_DENIED);
        assertThat(touched).as("执行器零触达").isEmpty();
        pool.shutdownNow();
    }

    // ------------------------------------------------------------------ 辅助

    private static ToolRegistry.Registration reg(String toolName, byte[] body) {
        ToolDefinition definition = switch (toolName) {
            case DirectReadToolCatalog.TOOL_CATALOG ->
                    DirectReadToolCatalog.prometheusCatalog(4_000, 65_536);
            case DirectReadToolCatalog.TOOL_LABEL_VALUES ->
                    DirectReadToolCatalog.prometheusLabelValues(4_000, 65_536);
            case DirectReadToolCatalog.TOOL_INSTANT ->
                    DirectReadToolCatalog.prometheusInstant(4_000, 65_536);
            default -> throw new IllegalStateException(toolName);
        };
        return new ToolRegistry.Registration(definition, exec -> body);
    }

    private DirectReadToolAgent agent(ToolRegistry registry, ToolGateway gateway,
            String toolName, String evidenceType) {
        return new DirectReadToolAgent(profile(toolName),
                DirectReadToolCatalog.spec(toolName, evidenceType, "prometheus"),
                registry, gateway, evidence, ledger, MAPPER);
    }

    private static AgentProfile profile(String toolName) {
        Map<String, Object> schema = Map.of("type", "object");
        Map<com.objwww.pr.control.alert.domain.budget.BudgetKind, Long> budget =
                Map.of(com.objwww.pr.control.alert.domain.budget.BudgetKind.TOOL_CALL, 10L);
        return new AgentProfile("en05-" + toolName, "1", "prompt", "am4-native-v1",
                Set.of(toolName), budget, schema);
    }

    private static ToolPolicy policyFor(String... tools) {
        return new ToolPolicy(Set.of(tools));
    }

    private static SingleToolEvidenceAgent.CallContext context(long callSeq) {
        return new SingleToolEvidenceAgent.CallContext(RUN, TASK, ATTEMPT, callSeq, 3L,
                null, TIME_RANGE);
    }

    /** 调用账本内存件（MetricsAgentTest 同形） */
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
