package com.objwww.pr.control.drill.application;

import com.objwww.pr.control.drill.domain.model.DrillTemplate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * DR-02 模板目录：随 jar 封装的真实目录（drill-templates.yml，派生自
 * eval-scenarios.yml registry v8——BA-178 追平：S16/S26 入目录，F9 流量配方同批接线；
 * BA-179：S17~S22/S24/S25 入目录，F10~F17 流量配方同批接线；BA-185：S27 变更回归
 * 入目录，change_ledger 账本联动+审批回滚处置链；ME-T08：v8 同步升锚，模板面零变化）
 * 可装载、十五场景齐备、公开面字段正确、可执行性分批开放（DR-A 批：S1/S2 已接线 ready=true，
 * S3~S5 维持 false）；白名单装载天然剥离 GT 扩展键；digest 稳定可复现。
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
    @DisplayName("封装目录装载：registry v8 + S1~S5/S16~S22/S24~S27 十六场景齐备")
    void bundledCatalogLoads() {
        DrillTemplateCatalog catalog = loadBundled();
        assertThat(catalog.registryVersion()).isEqualTo(8);
        assertThat(catalog.templates())
                .extracting(DrillTemplate::scenarioId)
                .containsExactly("S1", "S2", "S3", "S4", "S5", "S16", "S17", "S18",
                        "S19", "S20", "S21", "S22", "S24", "S25", "S26", "S27");
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
    @DisplayName("可执行性分批开放（DR-A 批）：五场景全 ready=true——S1/S2 随 DR-05 接线开放；"
            + "S3~S5 于 195 chaos-admin 连通+鉴权实测过后同窗开放（探针：对令牌 404/无令牌 401/"
            + "错令牌 401）；全员必带原因（不展示假按钮），拒注闸语义由合成模板用例保持")
    void executionHonestlyUnavailable() {
        DrillTemplateCatalog catalog = loadBundled();
        assertThat(catalog.templates()).allSatisfy(t -> {
            assertThat(t.execution().ready()).isTrue();
            assertThat(t.execution().reason()).isNotBlank();
        });
    }

    @Test
    @DisplayName("BA-178：S26 全工具复合模板公开面（F9 单族/时间参数同 S16/ready 带真实原因）")
    void s26TemplatePublicFace() {
        DrillTemplateCatalog catalog = loadBundled();
        DrillTemplate s26 = catalog.byScenarioId("S26").orElseThrow();
        assertThat(s26.name()).contains("全工具复合");
        assertThat(s26.driver()).isEqualTo("ArenaChaosScenarioDriver");
        assertThat(s26.chaosFamily()).isEqualTo("F9");
        assertThat(s26.symptomCodes()).containsExactly("ArenaPaymentOrderMismatch");
        assertThat(s26.timing().preheatSeconds()).isEqualTo(60);
        assertThat(s26.timing().holdSeconds()).isEqualTo(600);
        assertThat(s26.timing().maxFiringWaitSeconds()).isEqualTo(300);
        assertThat(s26.timing().maxResolvedWaitSeconds()).isEqualTo(600);
        assertThat(s26.timing().cleanupTimeoutSeconds()).isEqualTo(120);
        assertThat(s26.params().durationDefaultSeconds()).isEqualTo(600);
        assertThat(s26.params().trafficScales()).containsExactly("RECIPE");
        assertThat(s26.execution().ready()).isTrue();
        assertThat(s26.execution().reason()).contains("BA-178");

        DrillTemplate s16 = catalog.byScenarioId("S16").orElseThrow();
        assertThat(s16.chaosFamily()).isEqualTo("F9");
        assertThat(s16.execution().ready()).isTrue();
    }

    @Test
    @DisplayName("BA-179：S17~S22/S24/S25 八模板公开面（F10~F17 单族/ready 带真实原因/"
            + "时间参数与 eval registry v6 一致）")
    void ba179TemplatesPublicFace() {
        DrillTemplateCatalog catalog = loadBundled();
        Map<String, String> familyByScenario = Map.of(
                "S17", "F10", "S18", "F11", "S19", "F12", "S20", "F13",
                "S21", "F14", "S22", "F15", "S24", "F16", "S25", "F17");
        familyByScenario.forEach((scenarioId, family) -> {
            DrillTemplate t = catalog.byScenarioId(scenarioId).orElseThrow();
            assertThat(t.driver()).isEqualTo("ArenaChaosScenarioDriver");
            assertThat(t.chaosFamily()).isEqualTo(family);
            assertThat(t.symptomCodes()).hasSize(1);
            assertThat(t.params().trafficScales()).containsExactly("RECIPE");
            assertThat(t.execution().ready()).isTrue();
            assertThat(t.execution().reason()).contains("BA-179");
        });
        // 长窗族（积压/差值/零流）：firing/resolved 等待与 eval-scenarios.yml v6 一致
        assertThat(catalog.byScenarioId("S17").orElseThrow().timing()
                .maxFiringWaitSeconds()).isEqualTo(900);
        assertThat(catalog.byScenarioId("S21").orElseThrow().timing()
                .maxResolvedWaitSeconds()).isEqualTo(900);
        assertThat(catalog.byScenarioId("S24").orElseThrow().timing()
                .maxFiringWaitSeconds()).isEqualTo(900);
        assertThat(catalog.byScenarioId("S25").orElseThrow().timing()
                .maxFiringWaitSeconds()).isEqualTo(600);
        // 快检出族同 S3 档位
        assertThat(catalog.byScenarioId("S20").orElseThrow().timing()
                .maxFiringWaitSeconds()).isEqualTo(300);
        assertThat(catalog.byScenarioId("S22").orElseThrow().timing()
                .maxResolvedWaitSeconds()).isEqualTo(600);
    }

    @Test
    @DisplayName("BA-185：S27 变更回归模板公开面（FlagdScenarioDriver/时间参数同 S1/"
            + "ready 带真实原因——change_ledger 账本联动+R3 审批链处置）")
    void s27TemplatePublicFace() {
        DrillTemplateCatalog catalog = loadBundled();
        DrillTemplate s27 = catalog.byScenarioId("S27").orElseThrow();
        assertThat(s27.name()).contains("变更回归");
        assertThat(s27.driver()).isEqualTo("FlagdScenarioDriver");
        assertThat(s27.chaosFamily()).isNull();
        assertThat(s27.symptomCodes()).containsExactly("checkout");
        assertThat(s27.timing().preheatSeconds()).isEqualTo(60);
        assertThat(s27.timing().holdSeconds()).isEqualTo(600);
        assertThat(s27.timing().maxFiringWaitSeconds()).isEqualTo(1500);
        assertThat(s27.timing().maxResolvedWaitSeconds()).isEqualTo(2100);
        assertThat(s27.timing().cleanupTimeoutSeconds()).isEqualTo(120);
        assertThat(s27.params().durationDefaultSeconds()).isEqualTo(600);
        assertThat(s27.params().trafficScales()).containsExactly("RECIPE");
        assertThat(s27.execution().ready()).isTrue();
        assertThat(s27.execution().reason()).contains("BA-185");
    }

    @Test
    @DisplayName("contentDigest 稳定可复现（作业 template_digest 冻结源）")
    void digestStable() {        assertThat(loadBundled().contentDigest())
                .isEqualTo(loadBundled().contentDigest());
    }

    @Test
    @DisplayName("DR-04 恢复参数：recovery 块缺席 = 保守缺省（probe 10s / 截止 "
            + "= maxResolvedWait+cleanupTimeout）；显式块按白名单读入且入 digest")
    void recoveryParamsDefaultedAndParsed() {
        DrillTemplateCatalog bundled = loadBundled();
        DrillTemplate s1 = bundled.byScenarioId("S1").orElseThrow();
        assertThat(s1.recovery().probeSeconds())
                .isEqualTo(DrillTemplate.Recovery.DEFAULT_PROBE_SECONDS);
        assertThat(s1.recovery().deadlineSeconds()).isEqualTo(2100 + 120);

        String yaml = """
                registry_version: 2
                templates:
                  - scenario_id: T1
                    name: 测试
                    timing: {preheat_seconds: 1, hold_seconds: 2, max_firing_wait_seconds: 3,
                             max_resolved_wait_seconds: 4, cleanup_timeout_seconds: 5}
                    recovery: {probe_seconds: 7, deadline_seconds: 42}
                    params:
                      duration_seconds: {default: 2, min: 1, max: 9}
                      traffic_scales: [RECIPE]
                      linked_eval_version_allowed: false
                    execution: {ready: true}
                """;
        DrillTemplate t = DrillTemplateCatalog.load(yaml).byScenarioId("T1").orElseThrow();
        assertThat(t.recovery().probeSeconds()).isEqualTo(7);
        assertThat(t.recovery().deadlineSeconds()).isEqualTo(42);
        // 恢复参数是冻结面：异 recovery 块异 digest
        String defaulted = yaml.replace(
                "    recovery: {probe_seconds: 7, deadline_seconds: 42}\n", "");
        assertThat(DrillTemplateCatalog.load(defaulted).contentDigest())
                .isNotEqualTo(DrillTemplateCatalog.load(yaml).contentDigest());
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
