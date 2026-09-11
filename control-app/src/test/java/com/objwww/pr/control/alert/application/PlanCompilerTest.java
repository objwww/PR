package com.objwww.pr.control.alert.application;

import com.objwww.pr.control.alert.application.PlanCompiler;
import com.objwww.pr.control.alert.application.agent.AgentRegistry;
import com.objwww.pr.control.alert.domain.agent.AgentProfile;
import com.objwww.pr.control.alert.domain.budget.BudgetKind;
import com.objwww.pr.control.alert.domain.dag.DependencyType;
import com.objwww.pr.control.alert.domain.model.RcaTask;
import com.objwww.pr.control.alert.domain.model.RcaTaskState;
import com.objwww.pr.control.alert.domain.repository.RcaTaskRepository;
import com.objwww.pr.control.alert.domain.repository.TaskEdgeRepository;
import com.objwww.pr.control.alert.domain.dag.TaskEdge;
import com.objwww.pr.control.alert.support.AlertInMemoryStores;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionOperations;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * UT-AM4-25 后半：PlanCompiler 语义校验与单事务落库（双设防第二设防）——
 * 注册表任务类型/≤8 任务/深度 ≤3/无环/活跃 VERIFY ≤1/输入引用本 run artifact 集
 * 逐条拒绝且<b>零写入</b>；相同提案 → 相同任务图（proposalDigest 稳定）。
 * 模型无调度权：编译器是唯一落库路径。
 */
class PlanCompilerTest {

    private static final Instant NOW = Instant.parse("2026-09-07T00:00:00Z");

    private AlertInMemoryStores.Tasks tasks;
    private EdgeStore edges;
    private PlanCompiler compiler;

    @BeforeEach
    void setUp() {
        tasks = new AlertInMemoryStores.Tasks();
        edges = new EdgeStore();
        AgentRegistry registry = new AgentRegistry(List.of(
                profile("metrics-agent", "1.0.0"),
                profile("logs-agent", "1.0.0"),
                profile("change-agent", "1.0.0"),
                profile("verify", "1.0.0")));
        compiler = new PlanCompiler(registry, tasks, edges,
                new AlertInMemoryStores.Bindings(), inPlaceTx());
    }

    private static Map<String, Object> task(String key, String type) {
        return Map.of("key", key, "type", type, "inputs", List.of("alert"));
    }

    private static Map<String, Object> plan(Object... tasksAndEdges) {
        List<Map<String, Object>> t = new ArrayList<>();
        List<Map<String, Object>> e = new ArrayList<>();
        for (Object o : tasksAndEdges) {
            if (o instanceof Map<?, ?> m && m.containsKey("from")) {
                @SuppressWarnings("unchecked")
                Map<String, Object> edge = (Map<String, Object>) o;
                e.add(edge);
            } else {
                @SuppressWarnings("unchecked")
                Map<String, Object> tk = (Map<String, Object>) o;
                t.add(tk);
            }
        }
        return Map.of("schema_version", "am4-plan.v1", "tasks", t, "edges", e);
    }

    private static Map<String, Object> edge(String from, String to) {
        return Map.of("from", from, "to", to, "dependency", "REQUIRED");
    }

    @Test
    void 合法编译_单事务落tasks与edges_BLOCKED出生() {
        UUID runId = UUID.randomUUID();
        PlanCompiler.PlanCompilation r = compiler.compile(runId, plan(
                task("metrics", "metrics-agent@1.0.0"),
                task("verify", "verify@1.0.0"),
                edge("metrics", "verify")), Set.of("alert"));

        assertThat(r.taskIds()).containsOnlyKeys("metrics", "verify");
        assertThat(tasks.findByRunId(runId)).hasSize(2);
        RcaTask metrics = tasks.findByRunId(runId).stream()
                .filter(t -> t.taskKey().equals("metrics")).findFirst().orElseThrow();
        assertThat(metrics.state()).isEqualTo(RcaTaskState.BLOCKED); // 推进器负责 READY
        assertThat(metrics.runId()).isEqualTo(runId);
        assertThat(edges.rows).hasSize(1);
        assertThat(edges.rows.get(0).dependencyType()).isEqualTo(DependencyType.REQUIRED);
        assertThat(r.proposalDigest()).hasSize(64);
    }

    @Test
    void 未知任务类型_拒绝且零写入() {
        UUID runId = UUID.randomUUID();
        assertThatThrownBy(() -> compiler.compile(runId, plan(
                task("weird", "not-registered@9.9.9")), Set.of("alert")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("未注册");
        assertThat(tasks.findByRunId(runId)).isEmpty();
    }

    @Test
    void 超过8任务_拒绝且零写入() {
        UUID runId = UUID.randomUUID();
        Map<String, Object>[] nine = new java.util.ArrayList<Map<String, Object>>() {{
            for (int i = 0; i < 9; i++) {
                add(task("t" + i, "metrics-agent@1.0.0"));
            }
        }}.toArray(new Map[0]);
        assertThatThrownBy(() -> compiler.compile(runId, plan((Object[]) nine), Set.of("alert")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("8");
        assertThat(tasks.findByRunId(runId)).isEmpty();
    }

    @Test
    void 深度超3_拒绝_恰好3通过() {
        UUID runId = UUID.randomUUID();
        // a→b→c→d 链 = 深度 3 条边？不：3 条边 4 节点 = 深度 3，通过的是 3 边；4 边拒
        assertThatThrownBy(() -> compiler.compile(runId, plan(
                task("a", "metrics-agent@1.0.0"), task("b", "logs-agent@1.0.0"),
                task("c", "change-agent@1.0.0"), task("d", "verify@1.0.0"),
                task("e", "metrics-agent@1.0.0"),
                edge("a", "b"), edge("b", "c"), edge("c", "d"), edge("d", "e")),
                Set.of("alert")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("深度");
        assertThat(tasks.findByRunId(runId)).isEmpty();

        compiler.compile(UUID.randomUUID(), plan(
                task("a", "metrics-agent@1.0.0"), task("b", "logs-agent@1.0.0"),
                task("c", "change-agent@1.0.0"), task("d", "verify@1.0.0"),
                edge("a", "b"), edge("b", "c"), edge("c", "d")), Set.of("alert"));
    }

    @Test
    void 成环提案_拒绝且零写入() {
        UUID runId = UUID.randomUUID();
        assertThatThrownBy(() -> compiler.compile(runId, plan(
                task("a", "metrics-agent@1.0.0"), task("b", "logs-agent@1.0.0"),
                edge("a", "b"), edge("b", "a")), Set.of("alert")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("环");
        assertThat(tasks.findByRunId(runId)).isEmpty();
    }

    @Test
    void 活跃VERIFY超过1_拒绝() {
        UUID runId = UUID.randomUUID();
        assertThatThrownBy(() -> compiler.compile(runId, plan(
                task("v1", "verify@1.0.0"), task("v2", "verify@1.0.0"),
                task("m", "metrics-agent@1.0.0")), Set.of("alert")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("VERIFY");
        assertThat(tasks.findByRunId(runId)).isEmpty();
    }

    @Test
    void 输入引用不属于本run_拒绝() {
        UUID runId = UUID.randomUUID();
        assertThatThrownBy(() -> compiler.compile(runId, plan(
                Map.of("key", "m", "type", "metrics-agent@1.0.0",
                        "inputs", List.of("other-run-artifact"))), Set.of("alert")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("other-run-artifact");
        assertThat(tasks.findByRunId(runId)).isEmpty();
    }

    @Test
    void 相同提案相同任务图_digest稳定() {
        Map<String, Object> proposal = plan(
                task("metrics", "metrics-agent@1.0.0"), task("verify", "verify@1.0.0"),
                edge("metrics", "verify"));
        PlanCompiler.PlanCompilation r1 = compiler.compile(UUID.randomUUID(), proposal,
                Set.of("alert"));
        PlanCompiler.PlanCompilation r2 = compiler.compile(UUID.randomUUID(), proposal,
                Set.of("alert"));
        assertThat(r1.proposalDigest()).isEqualTo(r2.proposalDigest());
        assertThat(r1.taskIds().keySet()).isEqualTo(r2.taskIds().keySet());
    }

    // ------------------------------------------------------------------ 夹具

    private static AgentProfile profile(String name, String version) {
        return new AgentProfile(name, version, "prompt-" + name, "pv",
                Set.of(), Map.of(BudgetKind.STEP, 1L), Map.of("type", "object"));
    }

    private static TransactionOperations inPlaceTx() {
        return new TransactionOperations() {
            @Override
            public <T> T execute(TransactionCallback<T> action) {
                return action.doInTransaction(null);
            }
        };
    }

    /** 最小边仓储内存件（只服务本测试的读断言） */
    private static final class EdgeStore implements TaskEdgeRepository {
        final List<TaskEdge> rows = new ArrayList<>();

        @Override
        public void insert(UUID runId, UUID fromTaskId, UUID toTaskId,
                DependencyType dependencyType) {
            rows.add(new TaskEdge(fromTaskId.toString(), toTaskId.toString(), dependencyType));
        }

        @Override
        public List<TaskEdge> findByRunId(UUID runId) {
            return List.copyOf(rows);
        }
    }
}
