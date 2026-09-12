package com.objwww.pr.control.alert.application.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.objwww.pr.control.alert.application.DagExecutionService;
import com.objwww.pr.control.alert.application.DeterministicSupervisor;
import com.objwww.pr.control.alert.application.PlanCompiler;
import com.objwww.pr.control.alert.domain.agent.AgentPhase;
import com.objwww.pr.control.alert.domain.agent.AgentProfile;
import com.objwww.pr.control.alert.domain.agent.DelegationDecision;
import com.objwww.pr.control.alert.domain.agent.PrimaryDecision;
import com.objwww.pr.control.alert.domain.agent.RoleRuntimeKind;
import com.objwww.pr.control.alert.domain.budget.BudgetKind;
import com.objwww.pr.control.alert.domain.dag.DependencyType;
import com.objwww.pr.control.alert.domain.dag.TaskEdge;
import com.objwww.pr.control.alert.domain.model.RcaRun;
import com.objwww.pr.control.alert.domain.model.RcaRunState;
import com.objwww.pr.control.alert.domain.model.RcaTask;
import com.objwww.pr.control.alert.domain.model.RunTrigger;
import com.objwww.pr.control.alert.domain.model.TaskExecutionBinding;
import com.objwww.pr.control.alert.domain.repository.TaskEdgeRepository;
import com.objwww.pr.control.alert.support.AlertInMemoryStores;
import com.objwww.pr.shared.Digest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionOperations;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * R3 委派语义诚实面（路线B）：prompt 协议文本与执行面行为一致——
 * ① PROTOCOL_SUFFIX 委派字段集 = PrimaryDecision.DelegateRequest 分量集（双向，
 *    协议漂移=新决策字段无协议描述即红）；② 诚实语义词句在场（固定查询专家/
 *    不接受自由文本指令/question 仅台账审计）；③ 执行面透传实据：APPROVED 后
 *    binding 携带 inputRefs、台账行留 question，binding 分量集无 question
 *    （执行面结构性不消费）。
 */
class R3DelegationHonestyTest {

    private static final Instant NOW = Instant.parse("2026-09-12T09:00:00Z");

    private final AlertInMemoryStores stores = new AlertInMemoryStores();
    private final EdgeStore edges = new EdgeStore();
    private final UUID runId = UUID.randomUUID();

    private final AgentRegistry agents = new AgentRegistry(List.of(
            primaryProfile(), expertProfile("metrics-expert")));

    private DeterministicSupervisor supervisor;

    @BeforeEach
    void setUp() {
        stores.runs.insert(new RcaRun(runId, UUID.randomUUID(), 0, RunTrigger.RERUN,
                RcaRunState.QUEUED, Digest.sha256Of("material"), NOW, NOW, null,
                null, null));
        supervisor = new DeterministicSupervisor(
                new PlanCompiler(agents, stores.tasks, edges, stores.bindings, inPlaceTx()),
                new DagExecutionService(edges, stores.tasks),
                stores.runs, stores.tasks, stores.bindings, stores.checkpoints,
                stores.delegationDecisions, agents, inPlaceTx(), () -> NOW,
                DeterministicSupervisor.MAX_DELEGATION_BATCHES);
    }

    // ------------------------------------------------- ① 协议字段集 = 决策分量集

    @Test
    void 协议委派字段集与DelegateRequest分量集双向一致() {
        Set<String> recordFields = java.util.Arrays.stream(
                        PrimaryDecision.DelegateRequest.class.getRecordComponents())
                .map(c -> camelToSnake(c.getName()))
                .collect(Collectors.toSet());

        assertThat(recordFields).as("决策分量都有协议描述")
                .containsExactlyInAnyOrderElementsOf(BoundedLlmRoleRunner.DELEGATE_REQUEST_FIELDS);
        assertThat(BoundedLlmRoleRunner.DELEGATE_REQUEST_FIELDS)
                .as("协议字段都有决策分量（漂移即红）")
                .containsExactlyInAnyOrderElementsOf(recordFields);
    }

    // ------------------------------------------------- ② 诚实语义词句在场

    @Test
    void 协议文案如实声明固定查询专家语义() {
        String protocol = BoundedLlmRoleRunner.PROTOCOL_SUFFIX;
        assertThat(protocol).contains("固定查询专家");
        assertThat(protocol).contains("不接受自由文本");
        assertThat(protocol).contains("question 仅入台账审计");
        for (String field : BoundedLlmRoleRunner.DELEGATE_REQUEST_FIELDS) {
            assertThat(protocol).as("协议含字段 %s", field).contains("\"" + field + "\"");
        }
    }

    // ------------------------------------------------- ③ 执行面透传实据

    @Test
    void 获批委派_binding带inputRefs_question只留台账_执行面无question分量() {
        UUID primaryId = startPrimary();
        Map<String, Object> request = request("gap-1", "metrics-expert",
                List.of("ev-1", "ev-2"));

        DeterministicSupervisor.Adjudication result = supervisor.adjudicateDelegation(
                runId, primaryId, PrimaryDecision.parse(Map.of("delegate",
                        Map.of("requests", List.of(request)))));

        DelegationDecision approved = result.decisions().get(0);
        assertThat(approved.status()).isEqualTo(DelegationDecision.Status.APPROVED);
        assertThat(approved.question()).as("台账留模型自述 question（审计面）")
                .isEqualTo("q-gap-1");

        TaskExecutionBinding binding =
                stores.bindings.findByTask(approved.childTaskId()).orElseThrow();
        assertThat(binding.inputRefs()).as("执行面携带冻结 input_refs")
                .containsExactly("ev-1", "ev-2");
        assertThat(binding.roleId()).isEqualTo("metrics-expert");

        Set<String> bindingFields = java.util.Arrays.stream(
                        TaskExecutionBinding.class.getRecordComponents())
                .map(c -> c.getName())
                .collect(Collectors.toSet());
        assertThat(bindingFields).as("执行面绑定结构性无 question（不消费自由文本指令）")
                .doesNotContain("question");
    }

    // ------------------------------------------------- 夹具

    private UUID startPrimary() {
        supervisor.startPrimary(runId, primaryProfile(), Set.of());
        return stores.tasks.findByRunId(runId).stream()
                .filter(t -> t.taskKey().equals(RcaTask.PRIMARY_INVESTIGATE))
                .findFirst().orElseThrow().id();
    }

    private static String camelToSnake(String name) {
        return name.replaceAll("([a-z])([A-Z])", "$1_$2").toLowerCase();
    }

    private static Map<String, Object> request(String gapId, String roleId,
            List<String> inputRefs) {
        Map<String, Object> req = new LinkedHashMap<>();
        req.put("gap_id", gapId);
        req.put("role_id", roleId);
        req.put("question", "q-" + gapId);
        req.put("input_refs", inputRefs);
        req.put("scope", Map.of());
        req.put("requested_budget", 4);
        return req;
    }

    private static AgentProfile primaryProfile() {
        return new AgentProfile("primary", "1", "prompt-primary", "pv",
                Set.of(), Map.of(BudgetKind.STEP, 8L), Map.of("type", "object"),
                Map.of(), AgentPhase.PRIMARY, RoleRuntimeKind.BOUNDED_LLM,
                Set.of(), 8, "single-pass");
    }

    private static AgentProfile expertProfile(String name) {
        return new AgentProfile(name, "1", "prompt-" + name, "pv",
                Set.of(), Map.of(BudgetKind.STEP, 4L), Map.of("type", "object"),
                Map.of(), AgentPhase.INVESTIGATE, RoleRuntimeKind.DETERMINISTIC_SINGLE_TOOL,
                Set.of(), 1, "single-pass");
    }

    private static TransactionOperations inPlaceTx() {
        return new TransactionOperations() {
            @Override
            public <T> T execute(TransactionCallback<T> action) {
                return action.doInTransaction(null);
            }
        };
    }

    /** 最小边仓储内存件（KnobTest 同形） */
    private static final class EdgeStore implements TaskEdgeRepository {
        final List<TaskEdge> rows = new ArrayList<>();

        @Override
        public void insert(UUID runId, UUID fromTaskId, UUID toTaskId,
                DependencyType dependencyType) {
            rows.add(new TaskEdge(fromTaskId.toString(), toTaskId.toString(),
                    dependencyType));
        }

        @Override
        public List<TaskEdge> findByRunId(UUID runId) {
            return rows.stream().toList();
        }
    }
}
