package com.objwww.pr.control.infrastructure.config;

import com.objwww.pr.shared.Digest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * NativeCapabilityProbe UT（M6-01 落点 10；C-67）：NATIVE 执行面就绪判定与
 * capability digest 派生——缺任一依赖（bean 缺件/配置空）即 not ready，路由面
 * 据此 NATIVE_DEFERRED；无热开关，ready 与 digest 均为装配事实的纯函数。
 */
class NativeCapabilityProbeTest {

    /** 12 组件全在场的基线（名字与 AlertFlowConfig 装配键一致） */
    private static Map<String, Object> fullComponents() {
        Map<String, Object> components = new LinkedHashMap<>();
        components.put("configBundleRepository", new Object());
        components.put("deterministicSupervisor", new Object());
        components.put("rcaTaskRepository", new Object());
        components.put("rcaRunRepository", new Object());
        components.put("evidenceRepository", new Object());
        components.put("evidenceSnapshotRepository", new Object());
        components.put("metricsAgent", new Object());
        components.put("logsAgent", new Object());
        components.put("changeAgent", new Object());
        components.put("nativeRcaAgent", new Object());
        components.put("claimStore", new Object());
        components.put("validator", new Object());
        return components;
    }

    @Test
    @DisplayName("全件在场 + 配置齐 → ready，capability digest 为 64 位十六进制")
    void readyWhenAllComponentsPresent() {
        NativeCapabilityProbe probe = new NativeCapabilityProbe(fullComponents(),
                "up{job=\"prometheus\"}", "a".repeat(64));

        assertThat(probe.ready()).isTrue();
        assertThat(probe.missing()).isEmpty();
        assertThat(probe.capabilityDigest().hex()).hasSize(64).matches("[0-9a-f]{64}");
    }

    @Test
    @DisplayName("缺件（bean 缺失=null）→ not ready，missing 列名")
    void missingComponentIsNotReady() {
        Map<String, Object> components = fullComponents();
        components.put("nativeRcaAgent", null);

        NativeCapabilityProbe probe = new NativeCapabilityProbe(components,
                "up", "a".repeat(64));

        assertThat(probe.ready()).isFalse();
        assertThat(probe.missing()).containsExactly("nativeRcaAgent");
        assertThatThrownBy(probe::capabilityDigest).isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("配置空 = 缺件（metricsExpr/toolRegistryDigest 空串即 deferred）")
    void blankConfigIsMissing() {
        NativeCapabilityProbe noExpr = new NativeCapabilityProbe(fullComponents(),
                "  ", "a".repeat(64));
        NativeCapabilityProbe noDigest = new NativeCapabilityProbe(fullComponents(),
                "up", null);

        assertThat(noExpr.ready()).isFalse();
        assertThat(noExpr.missing()).containsExactly("metricsExpr");
        assertThat(noDigest.ready()).isFalse();
        assertThat(noDigest.missing()).containsExactly("toolRegistryDigest");
    }

    @Test
    @DisplayName("多件同缺全列（fail-closed 一次看清缺什么）+ digest 不可取")
    void multipleMissingAreAllReported() {
        Map<String, Object> components = fullComponents();
        components.put("metricsAgent", null);
        components.put("claimStore", null);

        NativeCapabilityProbe probe = new NativeCapabilityProbe(components,
                null, "a".repeat(64));

        assertThat(probe.ready()).isFalse();
        assertThat(probe.missing()).containsExactlyInAnyOrder(
                "metricsAgent", "claimStore", "metricsExpr");
    }

    @Test
    @DisplayName("capability digest 由实际装配集派生：组件集/配置变 → digest 变；同装配 → 同 digest")
    void digestTracksAssembledCapability() {
        NativeCapabilityProbe probe = new NativeCapabilityProbe(fullComponents(),
                "up", "a".repeat(64));
        NativeCapabilityProbe same = new NativeCapabilityProbe(fullComponents(),
                "up", "a".repeat(64));

        Map<String, Object> different = fullComponents();
        different.put("validator", null);
        NativeCapabilityProbe other = new NativeCapabilityProbe(different,
                "up", "a".repeat(64));

        assertThat(probe.capabilityDigest()).isEqualTo(same.capabilityDigest());
        assertThat(probe.capabilityDigest()).isNotEqualTo(new NativeCapabilityProbe(
                fullComponents(), "up{other}", "a".repeat(64)).capabilityDigest());
        assertThat(other.ready()).isFalse();
    }
}
