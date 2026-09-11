package com.objwww.pr.control.infrastructure.config;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * BA-117 装配缝转换（RoleQueryHandler 纪元秒 → 工具执行器 ISO-8601）：
 * 修复前纪元秒原样透传给 logs/change 执行器，触网前 INVALID_ARGS，确定性单工具
 * 子任务无模型修 args = 秒死（195 真窗 qwen/deepseek 四子任务全灭实证）。
 */
class AlertFlowConfigTest {

    @Test
    void 纪元秒转ISO8601_执行器域内可解析() {
        String iso = AlertFlowConfig.epochSecondsToIso("1757580000");
        assertThat(iso).isEqualTo(Instant.ofEpochSecond(1_757_580_000L).toString());
        assertThatCode(() -> Instant.parse(iso))
                .as("转换产物必须过执行器 Instant.parse 良构面")
                .doesNotThrowAnyException();
    }

    @Test
    void 非纪元秒串_failFast不静默透传() {
        assertThatThrownBy(() -> AlertFlowConfig.epochSecondsToIso("2026-09-11T00:00:00Z"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("非纪元秒串");
        assertThatThrownBy(() -> AlertFlowConfig.epochSecondsToIso(""))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
