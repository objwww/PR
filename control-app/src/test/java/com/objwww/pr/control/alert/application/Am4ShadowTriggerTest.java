package com.objwww.pr.control.alert.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.objwww.pr.control.alert.application.agent.AgentRegistry;
import com.objwww.pr.control.alert.application.agent.ChangeAgent;
import com.objwww.pr.control.alert.application.agent.LogsAgent;
import com.objwww.pr.control.alert.application.agent.MetricsAgent;
import com.objwww.pr.control.alert.application.agent.NativeRcaAgent;
import com.objwww.pr.control.alert.application.tool.ToolGateway;
import com.objwww.pr.control.alert.application.tool.ToolInvoker;
import com.objwww.pr.control.alert.application.tool.ToolRegistry;
import com.objwww.pr.control.alert.domain.agent.AgentProfile;
import com.objwww.pr.control.alert.domain.budget.BudgetKind;
import com.objwww.pr.control.alert.domain.claim.ClaimIdentity;
import com.objwww.pr.control.alert.domain.claim.ClaimProjection;
import com.objwww.pr.control.alert.domain.claim.ClaimReducer;
import com.objwww.pr.control.alert.domain.claim.ClaimStore;
import com.objwww.pr.control.alert.domain.claim.ClaimVerdict;
import com.objwww.pr.control.alert.domain.dag.DependencyType;
import com.objwww.pr.control.alert.domain.dag.TaskEdge;
import com.objwww.pr.control.alert.domain.evidence.EvidenceEnvelope;
import com.objwww.pr.control.alert.domain.evidence.EvidenceRepository;
import com.objwww.pr.control.alert.domain.model.RcaRun;
import com.objwww.pr.control.alert.domain.model.RcaRunState;
import com.objwww.pr.control.alert.domain.model.RcaTask;
import com.objwww.pr.control.alert.domain.model.RcaTaskState;
import com.objwww.pr.control.alert.domain.model.RunTrigger;
import com.objwww.pr.control.alert.domain.repository.RcaRunRepository;
import com.objwww.pr.control.alert.domain.repository.RcaTaskRepository;
import com.objwww.pr.control.alert.domain.repository.TaskEdgeRepository;
import com.objwww.pr.control.alert.domain.tool.RcaToolInvocationLedger;
import com.objwww.pr.control.alert.domain.tool.ToolInvocationState;
import com.objwww.pr.control.alert.domain.tool.ToolReasonCode;
import com.objwww.pr.control.alert.support.AlertInMemoryStores;
import com.objwww.pr.control.infrastructure.tool.ReplayToolExecutor;
import com.objwww.pr.shared.Digest;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionOperations;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Am4ShadowTrigger 单测（M4-38 执行者工具面）：影子 run 镜像 holmes 身份
 * （同 incident/同 generation/同 investigation hash）+ DAG 三调查任务全 DONE →
 * REPORTING + 三源证据同快照盖章 + 影子零报告零发布 + stdout 输出
 * {@code AM4_SHADOW_RUN_ID=} 标记（E2E 脚本捕获锚点）。
 *
 * @author wanghua
 * @date 2026-09-05
 */
class Am4ShadowTriggerTest {

    private static final Instant NOW = Instant.parse("2026-09-05T10:00:00Z");
    private static final long GENERATION = 14L;
    private static final String TOOL_RESPONSE_JSON =
            "{\"status\":\"success\",\"data\":{\"result\":[{\"metric\":{},"
                    + "\"values\":[[1788781788,\"2\"]]}]}}";
    private static final byte[] TOOL_RESPONSE = TOOL_RESPONSE_JSON.getBytes(StandardCharsets.UTF_8);

    private final AlertInMemoryStores stores = new AlertInMemoryStores();
    private final TriggerEdgeStore edges = new TriggerEdgeStore();
    private final TriggerEvidence evidence = new TriggerEvidence();
    private final TriggerLedger ledger = new TriggerLedger();
    private final TriggerClaims claims = new TriggerClaims();
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void shadowRunMirrorsHolmesAndRunsDagToReportingWithoutPublishing() {
        Digest snapshot = Digest.sha256Of("holmes-input");
        UUID holmesId = UUID.randomUUID();
        UUID incidentId = UUID.randomUUID();
        stores.runs.insert(new RcaRun(holmesId, incidentId, 14, RunTrigger.RERUN,
                RcaRunState.SUCCEEDED, snapshot, NOW, NOW, NOW, NOW, null));

        PrintStream originalOut = System.out;
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        System.setOut(new PrintStream(captured, true, StandardCharsets.UTF_8));
        UUID shadowId;
        try {
            shadowId = trigger().trigger(holmesId);
        } finally {
            System.setOut(originalOut);
        }

        assertThat(shadowId).isNotEqualTo(holmesId);
        RcaRun shadow = stores.runs.findById(shadowId).orElseThrow();
        assertThat(shadow.incidentId()).isEqualTo(incidentId);
        assertThat(shadow.generation()).isEqualTo(GENERATION);
        assertThat(shadow.investigationHash()).isEqualTo(snapshot);
        assertThat(shadow.state()).isEqualTo(RcaRunState.REPORTING);

        List<RcaTask> tasks = stores.tasks.findByRunId(shadowId);
        assertThat(tasks).extracting(RcaTask::taskKey)
                .containsExactlyInAnyOrder(Am4ShadowTrigger.TASK_METRICS,
                        Am4ShadowTrigger.TASK_LOGS, Am4ShadowTrigger.TASK_CHANGE);
        assertThat(tasks).allSatisfy(t -> assertThat(t.state()).isEqualTo(RcaTaskState.DONE));

        assertThat(evidence.rows).hasSize(3);
        assertThat(evidence.rows).allSatisfy(e ->
                assertThat(e.scope().get("input_snapshot_digest")).isEqualTo(snapshot.hex()));
        assertThat(evidence.rows).extracting(EvidenceEnvelope::source)
                .containsExactlyInAnyOrder("prometheus", "logs", "change");
        assertThat(ledger.succeeded).isEqualTo(3);

        assertThat(stores.reports.all()).isEmpty();
        assertThat(stores.publications.all()).isEmpty();

        assertThat(captured.toString(StandardCharsets.UTF_8))
                .contains(Am4ShadowTrigger.RUN_ID_MARKER + shadowId);
    }

    // ------------------------------------------------------------------ 组装

    private Am4ShadowTrigger trigger() {
        DeterministicSupervisor supervisor = new DeterministicSupervisor(
                new PlanCompiler(planAgents(), stores.tasks, edges, inPlaceTx()),
                new DagExecutionService(edges, stores.tasks),
                stores.runs, stores.tasks, inPlaceTx(), () -> NOW);
        ToolInvoker stub = invocation -> new ToolGateway.ToolInvocationResult(
                ToolGateway.ToolInvocationResult.Kind.EXECUTED, "ut-digest", TOOL_RESPONSE);
        ToolRegistry tools = new ToolRegistry(List.of(
                new ToolRegistry.Registration(
                        MetricsAgent.toolDefinition(4_000L, 65_536L),
                        new ReplayToolExecutor(TOOL_RESPONSE)),
                new ToolRegistry.Registration(
                        LogsAgent.toolDefinition(4_000L, 65_536L),
                        new ReplayToolExecutor(TOOL_RESPONSE)),
                new ToolRegistry.Registration(
                        ChangeAgent.toolDefinition(4_000L, 65_536L),
                        new ReplayToolExecutor(TOOL_RESPONSE))));
        MetricsAgent metrics = new MetricsAgent(profile("metrics", MetricsAgent.TOOL_NAME),
                tools, stub, evidence, ledger, mapper);
        LogsAgent logs = new LogsAgent(profile("logs", LogsAgent.TOOL_NAME),
                tools, stub, evidence, ledger, mapper);
        ChangeAgent change = new ChangeAgent(profile("change", ChangeAgent.TOOL_NAME),
                tools, stub, evidence, ledger, mapper);
        NativeRcaAgent nativeRca = new NativeRcaAgent(evidence, claims,
                new ClaimReducer(Set.of("holmes", "prometheus"), "ut-policy"));
        return new Am4ShadowTrigger(supervisor, stores.runs, stores.tasks,
                metrics, logs, change, nativeRca, () -> NOW);
    }

    private static AgentProfile profile(String name, String toolName) {
        return new AgentProfile(name, "1", "prompt-" + name, "pv",
                Set.of(toolName), Map.of(BudgetKind.TOOL_CALL, 4L), Map.of("type", "object"));
    }

    private static AgentRegistry planAgents() {
        return new AgentRegistry(List.of(
                new AgentProfile("metrics", "1", "prompt-m", "pv", Set.of(),
                        Map.of(BudgetKind.STEP, 8L), Map.of("type", "object")),
                new AgentProfile("logs", "1", "prompt-l", "pv", Set.of(),
                        Map.of(BudgetKind.STEP, 8L), Map.of("type", "object")),
                new AgentProfile("change", "1", "prompt-c", "pv", Set.of(),
                        Map.of(BudgetKind.STEP, 8L), Map.of("type", "object"))));
    }

    private static TransactionOperations inPlaceTx() {
        return new TransactionOperations() {
            @Override
            public <T> T execute(TransactionCallback<T> action) {
                return action.doInTransaction(null);
            }
        };
    }

    // ------------------------------------------------------------------ 内存件

    private static final class TriggerEdgeStore implements TaskEdgeRepository {

        private final List<UUID> runIds = new ArrayList<>();
        private final List<TaskEdge> edgeRows = new ArrayList<>();

        @Override
        public void insert(UUID runId, UUID fromTaskId, UUID toTaskId,
                DependencyType dependencyType) {
            runIds.add(runId);
            edgeRows.add(new TaskEdge(fromTaskId.toString(), toTaskId.toString(),
                    dependencyType));
        }

        @Override
        public List<TaskEdge> findByRunId(UUID runId) {
            List<TaskEdge> matched = new ArrayList<>();
            for (int k = 0; k < runIds.size(); k++) {
                if (runIds.get(k).equals(runId)) {
                    matched.add(edgeRows.get(k));
                }
            }
            return matched;
        }
    }

    private static final class TriggerEvidence implements EvidenceRepository {

        private final List<EvidenceEnvelope> rows = new ArrayList<>();

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

    private static final class TriggerLedger implements RcaToolInvocationLedger {

        private int succeeded;

        @Override
        public void open(InvocationIdentity identity) {
            // 只统计终态；PENDING 行本测试不消费
        }

        @Override
        public boolean succeed(UUID operationId) {
            succeeded++;
            return true;
        }

        @Override
        public boolean fail(UUID operationId, ToolInvocationState terminal,
                ToolReasonCode reasonCode) {
            return true;
        }
    }

    private static final class TriggerClaims implements ClaimStore {

        private final List<ClaimVerdict> appended = new ArrayList<>();

        @Override
        public ClaimAppendResult append(UUID runId, ClaimVerdict verdict) {
            appended.add(verdict);
            return new ClaimAppendResult(ClaimProjection.Outcome.CREATED,
                    verdict.fingerprint(), verdict.contentHash(), null, 0, 1L);
        }

        @Override
        public long markUnresolved(UUID runId, ClaimIdentity identity, String policyVersion) {
            return 1L;
        }

        @Override
        public List<ClaimRow> findByRunId(UUID runId) {
            return List.of();
        }
    }
}
