package com.objwww.pr.control.alert.domain.budget;

import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * DoomLoopGuard 穷举单测（AM4 M4-08，技术方案 v1.3 §6 DoomLoopGuard）：
 * 按 (task, tool, action digest) 签名的连续无进展熔断——命中仍扣一次 step（触发调用
 * 本身已放行、下次起零调用）、阈值配置化+版本化、轮询工具豁免、熔断粘滞。
 */
class DoomLoopGuardTest {

    private final UUID task = UUID.randomUUID();
    private final DoomLoopGuard guard = new DoomLoopGuard(
            new DoomLoopGuard.Policy(3, "test-policy-v1", Set.of()));

    @Test
    void utD01_未记录任何调用时前置门放行() {
        assertThat(guard.isOpen(task, "promql", "digest-a")).isTrue();
    }

    @Test
    void utD02_连续无进展达到阈值才熔断_触发那次已放行() {
        assertThat(guard.record(task, "promql", "d", false)).isFalse();
        assertThat(guard.record(task, "promql", "d", false)).isFalse();
        assertThat(guard.isOpen(task, "promql", "d")).isTrue(); // 第 3 次调用仍放行（命中仍扣一次 step）
        assertThat(guard.record(task, "promql", "d", false)).isTrue(); // 第 3 次无进展 → 熔断
        assertThat(guard.isOpen(task, "promql", "d")).isFalse(); // 下次起零调用
    }

    @Test
    void utD03_熔断粘滞_其后任何记录不解熔() {
        trip(guard);
        assertThat(guard.record(task, "promql", "d", true)).isTrue();
        assertThat(guard.isOpen(task, "promql", "d")).isFalse();
    }

    @Test
    void utD04_有进展重置连续无进展计数() {
        DoomLoopGuard.Policy p = new DoomLoopGuard.Policy(2, "v1", Set.of());
        DoomLoopGuard g = new DoomLoopGuard(p);
        UUID t = UUID.randomUUID();
        assertThat(g.record(t, "promql", "d", false)).isFalse();
        assertThat(g.record(t, "promql", "d", true)).isFalse(); // 重置
        assertThat(g.record(t, "promql", "d", false)).isFalse();
        assertThat(g.isOpen(t, "promql", "d")).isTrue();
        assertThat(g.record(t, "promql", "d", false)).isTrue(); // 第 2 次连续无进展才熔断
    }

    @Test
    void utD05_签名正交_不同tool或digest互不影响() {
        guard.record(task, "promql", "d-a", false);
        guard.record(task, "promql", "d-a", false);
        // 另一 digest 未到阈值
        assertThat(guard.record(task, "promql", "d-b", false)).isFalse();
        // 原 digest 第 3 次无进展 → 只熔自己
        assertThat(guard.record(task, "promql", "d-a", false)).isTrue();
        assertThat(guard.isOpen(task, "promql", "d-a")).isFalse();
        assertThat(guard.isOpen(task, "promql", "d-b")).isTrue();
        UUID otherTask = UUID.randomUUID();
        assertThat(guard.isOpen(otherTask, "promql", "d-a")).isTrue();
    }

    @Test
    void utD06_轮询工具豁免_合法重复永不熔断() {
        DoomLoopGuard g = new DoomLoopGuard(
                new DoomLoopGuard.Policy(1, "v1", Set.of("promql")));
        UUID t = UUID.randomUUID();
        for (int i = 0; i < 10; i++) {
            assertThat(g.record(t, "promql", "same-digest", false)).isFalse();
            assertThat(g.isOpen(t, "promql", "same-digest")).isTrue();
        }
    }

    @Test
    void utD07_策略校验_阈值必须非负一以上且版本暴露() {
        assertThatThrownBy(() -> new DoomLoopGuard.Policy(0, "v1", Set.of()))
                .isInstanceOf(IllegalArgumentException.class);
        DoomLoopGuard.Policy p = new DoomLoopGuard.Policy(3, "doom-policy-v7", Set.of());
        assertThat(new DoomLoopGuard(p).policy().version()).isEqualTo("doom-policy-v7");
    }

    private void trip(DoomLoopGuard g) {
        g.record(task, "promql", "d", false);
        g.record(task, "promql", "d", false);
        g.record(task, "promql", "d", false);
    }
}
