package com.objwww.pr.control.alert.domain.budget;

import com.objwww.pr.control.alert.application.agent.RoleLoopGuard;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * PA-A4（B v2 L5-3 五模式双阈值表）单测：exact repeat 2/5、ping-pong 4/6、
 * monologue 2/3（同 tool-different-args → TOOL_CALL 预算承接；context window →
 * BA-120 同签名二停承接——两模式为映射登记，不在本类）。
 */
class FivePatternLoopGuardsTest {

    private static final UUID TASK = UUID.randomUUID();

    private DoomLoopGuard guard(long warn, long stop, long ppWarn, long ppStop) {
        return new DoomLoopGuard(new DoomLoopGuard.Policy(warn, stop, ppWarn, ppStop,
                "ut", Set.of()));
    }

    // ------------------------------------------------ exact repeat（双阈值）

    @Test
    void pa4_exactRepeatWarnAt2StopAt5() {
        DoomLoopGuard g = guard(2, 5, 4, 6);
        for (int i = 1; i <= 4; i++) {
            assertThat(g.record(TASK, "q", "d1", false))
                    .as("第 %d 次未达硬停", i).isFalse();
        }
        assertThat(g.inExactRepeatWarningZone(TASK, "q", "d1"))
                .as("≥2 进入预警区（观测面，不拦截）").isTrue();
        assertThat(g.isOpen(TASK, "q", "d1")).isTrue();
        assertThat(g.record(TASK, "q", "d1", false)).isTrue();
        assertThat(g.isOpen(TASK, "q", "d1")).as("第 5 次熔断（粘滞）").isFalse();
    }

    @Test
    void pa4_progressResetsExactRepeatAndSequence() {
        DoomLoopGuard g = guard(2, 5, 4, 6);
        g.record(TASK, "q", "d1", false);
        g.record(TASK, "q", "d2", false);
        assertThat(g.record(TASK, "q", "d1", true)).isFalse();
        assertThat(g.noProgressCount(TASK, "q", "d1")).isZero();
        assertThat(g.inPingPongWarningZone(TASK)).as("有进展即清序列").isFalse();
    }

    // ------------------------------------------------ ping-pong（交替两签名）

    @Test
    void pa4_pingPongAlternationWarnAt4StopAt6() {
        DoomLoopGuard g = guard(5, 5, 4, 6);
        for (int i = 0; i < 3; i++) {
            g.record(TASK, "q", i % 2 == 0 ? "sigA" : "sigB", false);
        }
        assertThat(g.inPingPongWarningZone(TASK)).isFalse();
        g.record(TASK, "q", "sigB", false);
        assertThat(g.inPingPongWarningZone(TASK)).as("A,B,A,B 四连交替=预警").isTrue();
        assertThat(g.isOpen(TASK, "q", "sigA")).isTrue();

        g.record(TASK, "q", "sigA", false);
        g.record(TASK, "q", "sigB", false);
        boolean tripped = g.record(TASK, "q", "sigA", false);
        assertThat(tripped).as("第 6 连交替=硬停，两签名双双熔断").isTrue();
        assertThat(g.isOpen(TASK, "q", "sigA")).isFalse();
        assertThat(g.isOpen(TASK, "q", "sigB")).isFalse();
    }

    @Test
    void pa4_threeSignatureRotationIsNotPingPong() {
        DoomLoopGuard g = guard(5, 5, 4, 6);
        for (int i = 0; i < 6; i++) {
            g.record(TASK, "q", "sig" + (i % 3), false);
        }
        // 三签名轮转不是两签名乒乓（各自计数也未达 exact stop）——交给预算/步数上限
        assertThat(g.isOpen(TASK, "q", "sig0")).isTrue();
        assertThat(g.inPingPongWarningZone(TASK)).isFalse();
    }

    @Test
    void pa4_compatCtorKeepsLegacySingleThresholdSemantics() {
        DoomLoopGuard legacy = new DoomLoopGuard(
                new DoomLoopGuard.Policy(3, "ut", Set.of()));
        assertThat(legacy.policy().warnAfterNoProgress()).isEqualTo(3);
        assertThat(legacy.policy().pingPongStopAfter()).isEqualTo(Long.MAX_VALUE);
        legacy.record(TASK, "q", "d", false);
        legacy.record(TASK, "q", "d", false);
        assertThat(legacy.inExactRepeatWarningZone(TASK, "q", "d"))
                .as("warn=stop → 无独立预警区（现行为零变化）").isFalse();
    }

    @Test
    void pa4_invalidThresholdsRejected() {
        assertThatThrownBy(() -> guard(5, 2, 4, 6))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> guard(2, 5, 2, 6))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ------------------------------------------------ monologue（RoleLoopGuard）

    @Test
    void pa4_monologueWarnAt2StopAt3AndToolUseResets() {
        RoleLoopGuard g = new RoleLoopGuard(new RoleLoopGuard.Policy(2, 3, "ut"));
        assertThat(g.recordRound(TASK, false)).isEqualTo(RoleLoopGuard.Level.NONE);
        assertThat(g.recordRound(TASK, false)).isEqualTo(RoleLoopGuard.Level.WARN);
        assertThat(g.recordRound(TASK, true)).as("发起工具（含失败）即非独白")
                .isEqualTo(RoleLoopGuard.Level.NONE);
        assertThat(g.monologueRounds(TASK)).isZero();
        g.recordRound(TASK, false);
        g.recordRound(TASK, false);
        assertThat(g.recordRound(TASK, false)).isEqualTo(RoleLoopGuard.Level.STOP);
    }

    @Test
    void pa4_permissiveNeverStops() {
        RoleLoopGuard g = RoleLoopGuard.permissive();
        for (int i = 0; i < 100; i++) {
            assertThat(g.recordRound(TASK, false)).isEqualTo(RoleLoopGuard.Level.NONE);
        }
    }

    @Test
    void pa4_policyVersionCarriedForAudit() {
        assertThat(guard(2, 5, 4, 6).policy().version())
                .isEqualTo("ut");
        assertThat(new RoleLoopGuard(new RoleLoopGuard.Policy(2, 3, "r7-monologue-v1"))
                .policy().version()).isEqualTo("r7-monologue-v1");
    }
}
