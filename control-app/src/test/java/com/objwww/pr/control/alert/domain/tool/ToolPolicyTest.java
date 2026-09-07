package com.objwww.pr.control.alert.domain.tool;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * ToolPolicy 穷举单测（AM4 M4-16）：空策略硬失败（无模糊分支）、允许集判定、
 * 可执行风险集（R0/R1 执行、R2/R3 仅 VALIDATE_ONLY）。
 */
class ToolPolicyTest {

    @Test
    void utP01_空策略与null策略硬失败() {
        assertThatThrownBy(() -> new ToolPolicy(Set.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("空工具策略硬失败");
        assertThatThrownBy(() -> new ToolPolicy(null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void utP02_允许集正反判定_不可变快照() {
        ToolPolicy policy = new ToolPolicy(Set.of("prometheus.query"));
        assertThat(policy.allows("prometheus.query")).isTrue();
        assertThat(policy.allows("logs.tail")).isFalse();
        assertThatThrownBy(() -> policy.allowedTools().add("logs.tail"))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void utP03_可执行风险集_R0R1执行_R2R3仅记录() {
        assertThat(ToolRisk.R0.executable()).isTrue();
        assertThat(ToolRisk.R1.executable()).isTrue();
        assertThat(ToolRisk.R2.executable()).isFalse();
        assertThat(ToolRisk.R3.executable()).isFalse();
        // 伪造 annotation 无从影响判定：ToolRisk 无任何外部输入口（结构断言=枚举封闭）
        assertThat(ToolRisk.valueOf("R2").executable()).isFalse();
    }
}
