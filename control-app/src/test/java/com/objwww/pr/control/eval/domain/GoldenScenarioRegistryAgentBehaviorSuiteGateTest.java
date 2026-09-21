package com.objwww.pr.control.eval.domain;

import com.objwww.pr.control.eval.domain.service.ToolSelectionEvaluator;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * M-e T8（REPORT D08）agent_behavior_v1 套件装载门：加载真实 eval-scenarios.yml
 * （registry v8），断言六标签 13 案例的注册面完整——十要素 + cluster_id +
 * 生命周期状态如实（MECHANISM_READY 仅机制落地者，MCP/SAFETY 如实 REGISTERED）。
 * 装载器白名单忽略本节（scenarios 装载面不受套件账影响），本节由 snakeyaml
 * 直读断言；TOOL 八例 applicable_checks 与 ToolSelectionEvaluator 检查名常量对齐。
 */
class GoldenScenarioRegistryAgentBehaviorSuiteGateTest {

    private static final Path REGISTRY =
            Path.of("..", "deploy", "alert", "eval", "eval-scenarios.yml");

    private static final List<String> REQUIRED_CASE_KEYS = List.of(
            "case_id", "tag", "lifecycle_status", "cluster_id",
            "preconditions", "applicable_engines", "environment_seed", "available_tools",
            "hidden_truth", "allowed_valid_paths", "max_step_budget",
            "expected_terminal_state", "applicable_checks", "recovery_requirement",
            "execution_note");

    private static final Map<String, String> TOOL_CASE_CHECKS = Map.of(
            "AB-TOOL-01", ToolSelectionEvaluator.CHECK_TOOL_CHOICE,
            "AB-TOOL-02", ToolSelectionEvaluator.CHECK_PARAM_SEMANTICS,
            "AB-TOOL-03", ToolSelectionEvaluator.CHECK_DEGRADED_RECOVERY,
            "AB-TOOL-04", ToolSelectionEvaluator.CHECK_EMPTY_SET,
            "AB-TOOL-05", ToolSelectionEvaluator.CHECK_REPLAY_COVERAGE,
            "AB-TOOL-06", ToolSelectionEvaluator.CHECK_TERMINAL_STATE,
            "AB-TOOL-07", ToolSelectionEvaluator.CHECK_ENV_VALIDITY,
            "AB-TOOL-08", ToolSelectionEvaluator.CHECK_TRIAL_HYGIENE);

    @SuppressWarnings("unchecked")
    private static Map<String, Object> suite() throws IOException {
        Object root = new Yaml().load(Files.newBufferedReader(REGISTRY));
        Object suite = ((Map<String, Object>) root).get("agent_behavior_v1");
        assertThat(suite).as("agent_behavior_v1 套件账必须存在").isNotNull();
        return (Map<String, Object>) suite;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> cases() throws IOException {
        return ((List<Object>) suite().get("cases")).stream()
                .map(c -> (Map<String, Object>) c).toList();
    }

    @Test
    void registryV8KeepsScenarioFaceUntouched() throws IOException {
        GoldenScenarioRegistry registry =
                GoldenScenarioRegistry.load(Files.newBufferedReader(REGISTRY));
        assertThat(registry.registryVersion()).isEqualTo(8);
        // v8 纯套件账追加：scenarios 装载面 25 场景零新增零改动
        assertThat(registry.scenarios()).hasSize(25);
    }

    @Test
    void suiteHeaderVocabularyAndLifecycleStates() throws IOException {
        Map<String, Object> suite = suite();
        assertThat(suite.get("version")).isEqualTo(1);
        assertThat((List<Object>) suite.get("vocabulary"))
                .containsExactly("LOOP", "CONTEXT", "COLLAB", "TOOL", "MCP", "SAFETY");
        assertThat((List<Object>) suite.get("lifecycle_states"))
                .containsExactly("REGISTERED", "MECHANISM_READY", "EXECUTABLE", "VALIDATED");
        assertThat(suite.get("runner_note").toString()).contains("标签≠新 runner");
        assertThat(suite.get("execution_note").toString())
                .contains("不构成可跑承诺").contains("夜间专项");
    }

    @Test
    void thirteenCasesWithTenElementsClusterAndHonestStatus() throws IOException {
        List<Map<String, Object>> cases = cases();
        assertThat(cases).hasSize(13);
        Set<String> tags = Set.of("LOOP", "CONTEXT", "COLLAB", "TOOL", "MCP", "SAFETY");
        Set<String> states = Set.of("REGISTERED", "MECHANISM_READY",
                "EXECUTABLE", "VALIDATED");
        for (Map<String, Object> c : cases) {
            assertThat(c.keySet()).as("案例 %s 十要素+状态键齐备", c.get("case_id"))
                    .containsAll(REQUIRED_CASE_KEYS);
            assertThat(c.get("tag").toString()).isIn(tags);
            assertThat(c.get("lifecycle_status").toString()).isIn(states);
            assertThat(c.get("cluster_id").toString()).isNotBlank();
            assertThat(c.get("execution_note").toString()).isNotBlank();
        }
        // 状态如实：机制落地者 MECHANISM_READY；MCP/SAFETY 如实 REGISTERED；
        // 本任务无任何 EXECUTABLE/VALIDATED（跑批归夜间专项，不冒充可跑）
        Map<String, String> statusById = cases.stream().collect(Collectors.toMap(
                c -> c.get("case_id").toString(), c -> c.get("lifecycle_status").toString()));
        assertThat(statusById).containsEntry("AB-MCP-01", "REGISTERED")
                .containsEntry("AB-SAFETY-01", "REGISTERED");
        assertThat(statusById.values()).doesNotContain("EXECUTABLE", "VALIDATED");
        assertThat(statusById.values().stream()
                .filter("MECHANISM_READY"::equals).count()).isEqualTo(11);
        // cluster_id 数据分层字段面：同簇案例同键（TOOL-01/03/05 同宿主 S16 族）
        Map<String, String> clusterById = cases.stream().collect(Collectors.toMap(
                c -> c.get("case_id").toString(), c -> c.get("cluster_id").toString()));
        assertThat(clusterById.get("AB-TOOL-01"))
                .isEqualTo(clusterById.get("AB-TOOL-03"))
                .isEqualTo(clusterById.get("AB-TOOL-05"));
    }

    @Test
    void toolCasesAlignedWithEvaluatorCheckNames() throws IOException {
        Map<String, List<String>> checksById = cases().stream().collect(Collectors.toMap(
                c -> c.get("case_id").toString(),
                c -> ((List<Object>) c.get("applicable_checks")).stream()
                        .map(Object::toString).toList()));
        TOOL_CASE_CHECKS.forEach((caseId, checkName) -> {
            assertThat(checksById).containsKey(caseId);
            assertThat(checksById.get(caseId)).as("%s applicable_checks", caseId)
                    .contains(checkName);
        });
    }
}
