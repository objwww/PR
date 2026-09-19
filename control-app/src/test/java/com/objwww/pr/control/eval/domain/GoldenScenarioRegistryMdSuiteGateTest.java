package com.objwww.pr.control.eval.domain;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * M-d T4 套件装载门：加载真实 eval-scenarios.yml（registry v5），断言四类 20 条
 * 套件的注册面完整——新增 B/T 块必须通过既有装载器（七要素+timing 全带全），
 * 套件账 members 与 scenarios 键空间对得上。真装载器跑真文件，不造数。
 */
class GoldenScenarioRegistryMdSuiteGateTest {

    private static final Path REGISTRY =
            Path.of("..", "deploy", "alert", "eval", "eval-scenarios.yml");

    private static GoldenScenarioRegistry loadReal() throws IOException {
        return GoldenScenarioRegistry.load(Files.newBufferedReader(REGISTRY));
    }

    @Test
    void loadsV5WithAllTwentyThreeRegisteredScenarios() throws IOException {
        GoldenScenarioRegistry registry = loadReal();
        assertThat(registry.registryVersion()).isEqualTo(6);
        // 15 既有（S1~S5+S16~S25）+ 8 新增（B1~B5+T1~T3）+ 1 S26（M-d T8 全工具复合）
        // SR 两例走 case_version 回放不在此列
        assertThat(registry.scenarios()).hasSize(24);
    }

    @Test
    void boundaryAndToolFailureBlocksAreRegistered() throws IOException {
        GoldenScenarioRegistry registry = loadReal();
        Set<String> ids = new HashSet<>(registry.scenarios().stream()
                .map(GoldenCase::scenarioId).toList());
        assertThat(ids).contains("B1", "B2", "B3", "B4", "B5", "T1", "T2", "T3", "S26");
        // 边界块装载器关键面：injection/timing 均被解析（缺块即抛——真装载即校验）
        assertThat(registry.byScenarioId("B1").injection()).isNotNull();
        assertThat(registry.byScenarioId("B5").injection().variant()).isEqualTo("25%");
        assertThat(registry.byScenarioId("T3").chaosFamily()).isEqualTo("F10");
        // B1 静默基线：零症状零告警期望（abstention 口径的装载面）
        assertThat(registry.byScenarioId("B1").expectedSymptomCodes()).isEmpty();
        assertThat(registry.byScenarioId("B1").expectedAlertLabels()).isEmpty();
        // S26 全工具复合：F9 单族（不依赖 M-b 复合激活的装载面佐证）
        assertThat(registry.byScenarioId("S26").chaosFamily()).isEqualTo("F9");
        assertThat(registry.byScenarioId("S26").difficulty()).isEqualTo("L4");
    }

    @Test
    void originalScenarioIdsRemainUntouched() throws IOException {
        GoldenScenarioRegistry registry = loadReal();
        Set<String> ids = new HashSet<>(registry.scenarios().stream()
                .map(GoldenCase::scenarioId).toList());
        assertThat(ids).contains("S1", "S2", "S3", "S4", "S5",
                "S16", "S17", "S18", "S19", "S20", "S21", "S22", "S23", "S24", "S25");
    }
}
