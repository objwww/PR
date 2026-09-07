package com.objwww.pr.control.alert.domain.dag;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * UT-AM4-25 前半：PlanProposal 严格解析（双设防第一设防）——LLM 输出不可信：
 * schema 版本/未知字段（禁静默裁字段，GX-4 同纪律）/重复 task key/edge 端点未声明/
 * 自环/同对重复边（含异型冲突边）全部显式拒绝；digest 与解析输入的字段顺序无关。
 */
class PlanProposalTest {

    private static Map<String, Object> task(String key, String type, String... inputs) {
        return Map.of("key", key, "type", type, "inputs", List.of(inputs));
    }

    private static Map<String, Object> edge(String from, String to, String dep) {
        return Map.of("from", from, "to", to, "dependency", dep);
    }

    private static Map<String, Object> plan(Map<String, Object>... tasks) {
        return Map.of("schema_version", "am4-plan.v1", "tasks", List.of(tasks), "edges", List.of());
    }

    @Test
    void 合法提案_解析成结构() {
        PlanProposal p = PlanProposal.parse(Map.of(
                "schema_version", "am4-plan.v1",
                "tasks", List.of(task("metrics", "metrics-agent@1.0.0", "alert"),
                        task("logs", "logs-agent@1.0.0")),
                "edges", List.of(edge("metrics", "logs", "REQUIRED"))));
        assertThat(p.schemaVersion()).isEqualTo("am4-plan.v1");
        assertThat(p.tasks()).hasSize(2);
        assertThat(p.tasks().get(0).key()).isEqualTo("metrics");
        assertThat(p.tasks().get(0).inputs()).containsExactly("alert");
        assertThat(p.tasks().get(1).inputs()).isEmpty(); // inputs 缺省空
        assertThat(p.edges()).hasSize(1);
        assertThat(p.edges().get(0).dependency()).isEqualTo(DependencyType.REQUIRED);
    }

    @Test
    void schema版本错_拒绝() {
        assertThatThrownBy(() -> PlanProposal.parse(Map.of(
                "schema_version", "am4-plan.v9",
                "tasks", List.of(task("a", "x@1.0.0")),
                "edges", List.of())))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("schema_version");
    }

    @Test
    void 未知顶层字段_拒绝不静默裁() {
        Map<String, Object> raw = new java.util.LinkedHashMap<>(plan(task("a", "x@1.0.0")));
        raw.put("temperature", 0.7); // LLM 噪声字段
        assertThatThrownBy(() -> PlanProposal.parse(raw))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("temperature");
    }

    @Test
    void 未知任务字段_拒绝() {
        assertThatThrownBy(() -> PlanProposal.parse(Map.of(
                "schema_version", "am4-plan.v1",
                "tasks", List.of((Object) new java.util.LinkedHashMap<>(Map.of(
                        "key", "a", "type", "x@1", "prompt_override", "evil"))),
                "edges", List.of())))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("prompt_override");
    }

    @Test
    void 重复taskkey_拒绝() {
        assertThatThrownBy(() -> PlanProposal.parse(Map.of(
                "schema_version", "am4-plan.v1",
                "tasks", List.of(task("a", "x@1"), task("a", "y@1")),
                "edges", List.of())))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("a");
    }

    @Test
    void edge端点引用未声明key_拒绝() {
        assertThatThrownBy(() -> PlanProposal.parse(Map.of(
                "schema_version", "am4-plan.v1",
                "tasks", List.of(task("a", "x@1")),
                "edges", List.of(edge("a", "ghost", "REQUIRED")))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("ghost");
    }

    @Test
    void 自环_拒绝() {
        assertThatThrownBy(() -> PlanProposal.parse(Map.of(
                "schema_version", "am4-plan.v1",
                "tasks", List.of(task("a", "x@1")),
                "edges", List.of(edge("a", "a", "REQUIRED")))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("自环");
    }

    @Test
    void 同对重复边_同型异型都拒绝() {
        Map<String, Object> base = Map.of(
                "schema_version", "am4-plan.v1",
                "tasks", List.of(task("a", "x@1"), task("b", "y@1")),
                "edges", List.of(edge("a", "b", "REQUIRED"), edge("a", "b", "REQUIRED")));
        assertThatThrownBy(() -> PlanProposal.parse(base))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("重复");
        Map<String, Object> conflicting = Map.of(
                "schema_version", "am4-plan.v1",
                "tasks", List.of(task("a", "x@1"), task("b", "y@1")),
                "edges", List.of(edge("a", "b", "REQUIRED"), edge("a", "b", "OPTIONAL")));
        assertThatThrownBy(() -> PlanProposal.parse(conflicting))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("冲突");
    }

    @Test
    void type格式必须name加version() {
        assertThatThrownBy(() -> PlanProposal.parse(plan(task("a", "no-version"))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("name@version");
    }

    @Test
    void digest与解析输入字段顺序无关_内容变digest变() {
        PlanProposal a = PlanProposal.parse(Map.of(
                "edges", List.of(edge("metrics", "logs", "REQUIRED")),
                "tasks", List.of(task("metrics", "metrics-agent@1.0.0"),
                        task("logs", "logs-agent@1.0.0")),
                "schema_version", "am4-plan.v1"));
        PlanProposal b = PlanProposal.parse(Map.of(
                "schema_version", "am4-plan.v1",
                "tasks", List.of(task("logs", "logs-agent@1.0.0"),
                        task("metrics", "metrics-agent@1.0.0")),
                "edges", List.of(edge("metrics", "logs", "REQUIRED"))));
        assertThat(a.digest()).isEqualTo(b.digest()).hasSize(64);

        PlanProposal c = PlanProposal.parse(Map.of(
                "schema_version", "am4-plan.v1",
                "tasks", List.of(task("metrics", "metrics-agent@1.0.0"),
                        task("logs", "logs-agent@2.0.0")),
                "edges", List.of()));
        assertThat(c.digest()).isNotEqualTo(a.digest());
    }
}
