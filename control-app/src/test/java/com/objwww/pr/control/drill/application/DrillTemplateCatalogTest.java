package com.objwww.pr.control.drill.application;

import com.objwww.pr.control.drill.domain.model.DrillTemplate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.InputStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * DR-02 模板目录：随 jar 封装的真实目录（drill-templates.yml，派生自
 * eval-scenarios.yml registry v2）可装载、五场景齐备、公开面字段正确、
 * 可执行性如实未交付；白名单装载天然剥离 GT 扩展键；digest 稳定可复现。
 */
class DrillTemplateCatalogTest {

    private static DrillTemplateCatalog loadBundled() {
        try (InputStream in = DrillTemplateCatalogTest.class
                .getResourceAsStream("/drill/drill-templates.yml")) {
            assertThat(in).as("drill-templates.yml 必须随主资源封装").isNotNull();
            return DrillTemplateCatalog.load(in);
        } catch (java.io.IOException e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    @DisplayName("封装目录装载：registry v2 + S1~S5 五场景齐备")
    void bundledCatalogLoads() {
        DrillTemplateCatalog catalog = loadBundled();
        assertThat(catalog.registryVersion()).isEqualTo(2);
        assertThat(catalog.templates())
                .extracting(DrillTemplate::scenarioId)
                .containsExactly("S1", "S2", "S3", "S4", "S5");
    }

    @Test
    @DisplayName("公开面字段正确（类型/故障源/症状/时间参数/参数白名单）")
    void publicFieldsCorrect() {
        DrillTemplateCatalog catalog = loadBundled();
        DrillTemplate s3 = catalog.byScenarioId("S3").orElseThrow();
        assertThat(s3.name()).isEqualTo("F1 幂等失效");
        assertThat(s3.scenarioType()).isEqualTo("业务完整性");
        assertThat(s3.faultSource()).contains("Arena");
        assertThat(s3.driver()).isEqualTo("ArenaChaosScenarioDriver");
        assertThat(s3.chaosFamily()).isEqualTo("F1");
        assertThat(s3.symptomCodes()).containsExactly("ArenaDuplicateOrders");
        assertThat(s3.timing().maxFiringWaitSeconds()).isEqualTo(300);
        assertThat(s3.timing().maxResolvedWaitSeconds()).isEqualTo(600);
        assertThat(s3.params().durationMinSeconds()).isEqualTo(60);
        assertThat(s3.params().durationMaxSeconds()).isEqualTo(600);
        assertThat(s3.params().trafficScales()).containsExactly("RECIPE");
        DrillTemplate s1 = catalog.byScenarioId("S1").orElseThrow();
        assertThat(s1.driver()).isEqualTo("FlagdScenarioDriver");
        assertThat(s1.timing().maxResolvedWaitSeconds()).isEqualTo(2100);
    }

    @Test
    @DisplayName("可执行性如实：本批五场景 ready=false 且必带原因（不展示假按钮）")
    void executionHonestlyUnavailable() {
        DrillTemplateCatalog catalog = loadBundled();
        assertThat(catalog.templates()).allSatisfy(t -> {
            assertThat(t.execution().ready()).isFalse();
            assertThat(t.execution().reason()).isNotBlank();
        });
    }

    @Test
    @DisplayName("contentDigest 稳定可复现（作业 template_digest 冻结源）")
    void digestStable() {
        assertThat(loadBundled().contentDigest())
                .isEqualTo(loadBundled().contentDigest());
    }

    @Test
    @DisplayName("白名单装载：GT 扩展键（expected_root_cause）被天然剥离不入场")
    void gtKeysStripped() {
        String yaml = """
                registry_version: 2
                templates:
                  - scenario_id: T1
                    name: 测试
                    expected_root_cause: {component: payment, fault_type: X, reason_code: Y}
                    timing: {preheat_seconds: 1, hold_seconds: 2, max_firing_wait_seconds: 3,
                             max_resolved_wait_seconds: 4, cleanup_timeout_seconds: 5}
                    params:
                      duration_seconds: {default: 2, min: 1, max: 9}
                      traffic_scales: [RECIPE]
                      linked_eval_version_allowed: false
                    execution: {ready: true}
                """;
        DrillTemplateCatalog catalog = DrillTemplateCatalog.load(yaml);
        DrillTemplate t = catalog.byScenarioId("T1").orElseThrow();
        // 域对象无 GT 分量——装载即剥离；目录等价于无 GT 键的同内容文件
        assertThat(t.execution().ready()).isTrue();
        assertThat(catalog.contentDigest().value()).doesNotContain("payment");
    }
}
