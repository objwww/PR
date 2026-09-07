package com.objwww.pr.control.alert.application.agent;

import com.objwww.pr.control.alert.domain.agent.AgentProfile;
import com.objwww.pr.control.alert.domain.budget.BudgetKind;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * UT-AM4-24：AgentRegistry——启动期一次性构造 fail-fast（空注册表/重名同版本/重名同
 * 版本不同 digest 全拒，构造后不可变无运行时注册 API，对齐 ToolRegistry 惯例）；
 * <b>不可自由生 Agent</b>：require 面未注册/未声明版本一律显式拒绝。
 */
class AgentRegistryTest {

    private static AgentProfile profile(String name, String version) {
        return new AgentProfile(name, version, "prompt-" + name, "pv-1",
                Set.of(), Map.of(BudgetKind.STEP, 1L), Map.of("type", "object"));
    }

    @Test
    void 未注册Agent_require显式拒绝() {
        AgentRegistry registry = new AgentRegistry(List.of(profile("metrics-agent", "1.0.0")));
        assertThat(registry.find("metrics-agent", "1.0.0")).isPresent();
        assertThatThrownBy(() -> registry.require("unknown-agent", "1.0.0"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("未注册");
        assertThatThrownBy(() -> registry.require("metrics-agent", "9.9.9"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("未注册");
        assertThat(registry.find("unknown-agent", "1.0.0")).isEmpty();
    }

    @Test
    void 重名同版本拒绝_含digest冲突定位() {
        // 同名同版本不同内容（digest 不同）= 冒名顶替，启动即炸
        AgentProfile a = profile("metrics-agent", "1.0.0");
        AgentProfile b = new AgentProfile("metrics-agent", "1.0.0", "another-prompt", "pv-1",
                Set.of(), Map.of(BudgetKind.STEP, 1L), Map.of("type", "object"));
        assertThat(a.digest()).isNotEqualTo(b.digest());
        assertThatThrownBy(() -> new AgentRegistry(List.of(a, b)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("digest");
    }

    @Test
    void 完全重复注册拒绝() {
        AgentProfile a = profile("metrics-agent", "1.0.0");
        assertThatThrownBy(() -> new AgentRegistry(List.of(a, profile("metrics-agent", "1.0.0"))))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void 空注册表与null项拒绝() {
        assertThatThrownBy(() -> new AgentRegistry(List.of()))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new AgentRegistry(null))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new AgentRegistry(java.util.Arrays.asList(
                profile("a", "1.0.0"), null)))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void all_按名称版本字典序稳定输出() {
        AgentRegistry registry = new AgentRegistry(List.of(
                profile("z-agent", "1.0.0"), profile("a-agent", "2.0.0"),
                profile("a-agent", "1.0.0")));
        assertThat(registry.all()).extracting(AgentProfile::name)
                .containsExactly("a-agent", "a-agent", "z-agent");
        assertThat(registry.all()).extracting(AgentProfile::version)
                .containsExactly("1.0.0", "2.0.0", "1.0.0");
        assertThatThrownBy(() -> registry.all().add(profile("evil", "1.0.0")))
                .isInstanceOf(UnsupportedOperationException.class);
    }
}
