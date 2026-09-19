package com.objwww.pr.control.alert.application.tool;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ToolDescriptionZh UT（BA-176）：195 实测 14 个注册工具 + 条件注册件全覆盖，
 * 每条用途说明非空且不是工具名本身；未命中回退工具名（不瞎编用途）。
 */
class ToolDescriptionZhTest {

    /** 195 实测注册面 14 工具（BA-176 任务书清单） + 条件注册件（docker/runbook/code） */
    private static final List<String> KNOWN_TOOLS = List.of(
            "alert.history", "change.diff", "change.query", "logs.aggregate", "logs.query",
            "prometheus.catalog", "prometheus.instant", "prometheus.label_values",
            "prometheus.metric_value", "prometheus.query", "prometheus.rules",
            "rca_history.search", "service.restart", "service.rollback",
            "docker.ps", "docker.inspect", "runbook.catalog", "runbook.fetch",
            "code.search", "code.read");

    @Test
    @DisplayName("注册面 14 工具+条件件全部有非空中文用途（说明≠工具名本身）")
    void allRegisteredToolsHaveHumanDescription() {
        for (String name : KNOWN_TOOLS) {
            String zh = ToolDescriptionZh.of(name);
            assertThat(zh).as(name + " 用途说明").isNotBlank().isNotEqualTo(name);
        }
    }

    @Test
    @DisplayName("BA-183 调用契约面：时间窗工具带格式+窗幅上限；service 枚举不硬编码部署值")
    void callConstraintsPresentAndNoDeploymentEnum() {
        // 时间格式与窗幅上限与各 executor 语义校验面（INVALID_ARGS 分支）逐字对齐
        assertThat(ToolDescriptionZh.of("change.query"))
                .contains("ISO-8601 或 epoch 秒").contains("900 秒");
        assertThat(ToolDescriptionZh.of("change.diff"))
                .contains("ISO-8601 或 epoch 秒").contains("900 秒");
        assertThat(ToolDescriptionZh.of("logs.query"))
                .contains("ISO-8601 或 epoch 秒").contains("900 秒");
        assertThat(ToolDescriptionZh.of("logs.aggregate"))
                .contains("ISO-8601 或 epoch 秒").contains("900 秒");
        assertThat(ToolDescriptionZh.of("alert.history"))
                .contains("ISO-8601 或 epoch 秒").contains("72 小时");
        assertThat(ToolDescriptionZh.of("rca_history.search"))
                .contains("ISO-8601 或 epoch 秒").contains("30 天");
        assertThat(ToolDescriptionZh.of("prometheus.query"))
                .contains("epoch 秒").contains("3600 秒");
        // service allowlist 成员是部署配置面（BA-182 env）——词典只写"限部署白名单"，
        // 不钉具体服务名（防两处漂移；越界拒因经 V88 反馈环回喂模型）
        for (String name : KNOWN_TOOLS) {
            assertThat(ToolDescriptionZh.of(name))
                    .as(name + " 不得硬编码部署服务枚举")
                    .doesNotContain("order-arena").doesNotContain("checkout")
                    .doesNotContain("recommendation").doesNotContain("frontend")
                    .doesNotContain("payment");
        }
    }

    @Test
    @DisplayName("未命中回退工具名本身；null 回退空串（不瞎编）")
    void unknownToolFallsBackToName() {
        assertThat(ToolDescriptionZh.of("kafka.lag")).isEqualTo("kafka.lag");
        assertThat(ToolDescriptionZh.of(null)).isEmpty();
    }
}
