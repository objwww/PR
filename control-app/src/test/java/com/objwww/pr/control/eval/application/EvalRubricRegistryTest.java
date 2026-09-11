package com.objwww.pr.control.eval.application;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * EV-08 评审 rubric 注册表：装载白名单、current 恰一条、版本锚唯一、
 * 当前版本/已知版本透出（数据集读面）、未知版本解析缺席（提交 400 面）。
 */
class EvalRubricRegistryTest {

    private static final String YAML = """
            registry_version: 1
            rubrics:
              - id: rca-eval-review
                version: eval-review-rubric-v1
                current: false
                items:
                  - id: root_cause_correct
                    label: 根因判定正确性
                    required: true
              - id: rca-eval-review
                version: eval-review-rubric-v2
                current: true
                items:
                  - id: root_cause_correct
                    label: 根因判定正确性
                    required: true
                  - id: evidence_grounded
                    label: 结论有证据引用支撑
                    required: false
            """;

    @Test
    @DisplayName("装载：版本全表按行序透出，current 恰一条，评分项白名单字段")
    void loadsVersionsAndCurrent() {
        EvalRubricRegistry registry = EvalRubricRegistry.load(YAML);
        assertThat(registry.registryVersion()).isEqualTo(1);
        assertThat(registry.knownVersions()).containsExactly(
                "eval-review-rubric-v1", "eval-review-rubric-v2");
        assertThat(registry.currentVersion()).isEqualTo("eval-review-rubric-v2");
        assertThat(registry.find("eval-review-rubric-v2")).isPresent();
        assertThat(registry.find("eval-review-rubric-v2").orElseThrow().items())
                .hasSize(2);
        assertThat(registry.find("rubric-v999")).isEmpty();
    }

    @Test
    @DisplayName("形状校验：current 非恰一条 / 版本重复 / 缺 items 一律 fail-loud")
    void rejectsMalformedRegistry() {
        assertThatThrownBy(() -> EvalRubricRegistry.load("""
                registry_version: 1
                rubrics:
                  - id: a
                    version: v1
                    current: true
                    items: [{id: x, label: y}]
                  - id: b
                    version: v2
                    current: true
                    items: [{id: x, label: y}]
                """)).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("current");
        assertThatThrownBy(() -> EvalRubricRegistry.load("""
                registry_version: 1
                rubrics:
                  - id: a
                    version: v1
                    current: true
                    items: [{id: x, label: y}]
                  - id: b
                    version: v1
                    current: false
                    items: [{id: x, label: y}]
                """)).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("重复");
        assertThatThrownBy(() -> EvalRubricRegistry.load("""
                registry_version: 1
                rubrics:
                  - id: a
                    version: v1
                    current: true
                """)).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("items");
        assertThatThrownBy(() -> EvalRubricRegistry.load("registry_version: 1"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("随 jar 封装的 eval-rubrics.yml 可装载且 current 可解析")
    void bundledResourceLoads() {
        try (var in = getClass().getResourceAsStream("/eval-rubrics.yml")) {
            assertThat(in).isNotNull();
            EvalRubricRegistry registry = EvalRubricRegistry.load(in);
            assertThat(registry.currentVersion()).startsWith("eval-review-rubric-v");
            assertThat(registry.find(registry.currentVersion())).isPresent();
        } catch (java.io.IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
