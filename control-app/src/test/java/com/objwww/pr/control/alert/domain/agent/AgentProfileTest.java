package com.objwww.pr.control.alert.domain.agent;

import com.objwww.pr.control.alert.domain.budget.BudgetKind;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * UT-AM4-24：AgentProfile 契约——固定 prompt/tool allowlist/budget/schema 四件套；
 * 不可自由生 Agent（构造即校验、集合不可变）；digest 稳定（字段序/集合序无关，
 * 任一固定件变更 digest 必变）。allowlist 为空合法（Planner 等无工具 Agent）。
 */
class AgentProfileTest {

    private static AgentProfile profile(String prompt) {
        return new AgentProfile("metrics-agent", "1.0.0", prompt, "prompt-v3",
                Set.of("metrics.query", "health.ping"),
                Map.of(BudgetKind.STEP, 4L, BudgetKind.TOOL_CALL, 2L),
                Map.of("type", "object", "required", List.of("value")));
    }

    @Test
    void 合法构造_四件套齐备() {
        AgentProfile p = profile("固定 prompt 正文");
        assertThat(p.name()).isEqualTo("metrics-agent");
        assertThat(p.toolAllowlist()).containsExactlyInAnyOrder("metrics.query", "health.ping");
        assertThat(p.budgetLimits()).containsEntry(BudgetKind.TOOL_CALL, 2L);
        assertThat(p.outputSchema()).isNotEmpty();
    }

    @Test
    void 集合不可变_构造后改不了() {
        AgentProfile p = profile("p");
        assertThatThrownBy(() -> p.toolAllowlist().add("evil")).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> p.budgetLimits().put(BudgetKind.TOKEN, 1L))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> p.outputSchema().put("evil", "x"))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void 非法名称版本_空prompt_负限额_全拒绝() {
        assertThatThrownBy(() -> new AgentProfile("Bad Name", "1.0.0", "p", "v", Set.of(), Map.of(), Map.of()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new AgentProfile("", "1.0.0", "p", "v", Set.of(), Map.of(), Map.of()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new AgentProfile("a", "1.0.0", " ", "v", Set.of(), Map.of(), Map.of()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new AgentProfile("a", "1.0.0", "p", "v", Set.of(), Map.of(), null))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new AgentProfile("a", "-bad", "p", "v", Set.of(), Map.of(), Map.of()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new AgentProfile("a", "1.0.0", "p", " ", Set.of(), Map.of(), Map.of()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new AgentProfile("a", "1.0.0", "p", "v", Set.of(),
                Map.of(BudgetKind.STEP, -1L), Map.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void digest稳定_集合序无关_字段序无关() {
        AgentProfile a = new AgentProfile("metrics-agent", "1.0.0", "p", "v1",
                Set.of("b.tool", "a.tool"),
                Map.of(BudgetKind.TOOL_CALL, 2L, BudgetKind.STEP, 4L),
                Map.of("type", "object", "x", 1));
        AgentProfile b = new AgentProfile("metrics-agent", "1.0.0", "p", "v1",
                Set.of("a.tool", "b.tool"),
                Map.of(BudgetKind.STEP, 4L, BudgetKind.TOOL_CALL, 2L),
                Map.of("x", 1, "type", "object"));
        assertThat(a.digest()).isEqualTo(b.digest()).hasSize(64);
    }

    @Test
    void 任一固定件变_digest必变() {
        AgentProfile base = profile("p");
        assertThat(profile("p2").digest()).isNotEqualTo(base.digest()); // prompt 变
        assertThat(new AgentProfile("metrics-agent", "1.0.0", "p", "prompt-v3",
                Set.of("metrics.query"), base.budgetLimits(), base.outputSchema()).digest())
                .isNotEqualTo(base.digest()); // allowlist 变
        assertThat(new AgentProfile("metrics-agent", "1.0.0", "p", "prompt-v3",
                base.toolAllowlist(), Map.of(BudgetKind.STEP, 4L, BudgetKind.TOOL_CALL, 3L),
                base.outputSchema()).digest())
                .isNotEqualTo(base.digest()); // budget 变
        assertThat(new AgentProfile("metrics-agent", "1.0.0", "p", "prompt-v3",
                base.toolAllowlist(), base.budgetLimits(),
                Map.of("type", "object", "required", List.of("other"))).digest())
                .isNotEqualTo(base.digest()); // schema 变
        assertThat(new AgentProfile("metrics-agent", "1.0.0", "p", "prompt-v4",
                base.toolAllowlist(), base.budgetLimits(), base.outputSchema()).digest())
                .isNotEqualTo(base.digest()); // promptVersion 变
    }

    @Test
    void 空allowlist合法_无工具Agent() {
        AgentProfile planner = new AgentProfile("planner", "1.0.0", "p", "v",
                Set.of(), Map.of(BudgetKind.STEP, 1L),
                Map.of("type", "object"));
        assertThat(planner.toolAllowlist()).isEmpty();
        assertThat(planner.digest()).hasSize(64);
    }
}
