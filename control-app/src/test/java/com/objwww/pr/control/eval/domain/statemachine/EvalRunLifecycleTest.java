package com.objwww.pr.control.eval.domain.statemachine;

import com.objwww.pr.control.eval.domain.EvalRun;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * EV-04 生命周期状态机：取消合法迁移（仅 RUNNING 受理；终态拒绝）、模式词表、
 * L 模式恢复义务、worker 失联孤儿卡因（L 孤儿不冒充已恢复）。
 */
class EvalRunLifecycleTest {

    @Test
    @DisplayName("取消合法迁移：RUNNING 受理；SUCCEEDED/FAILED 终态拒绝")
    void cancelLegalOnlyOnRunning() {
        assertThat(EvalRunLifecycle.cancelLegal(EvalRun.EvalRunState.RUNNING)).isTrue();
        assertThat(EvalRunLifecycle.cancelLegal(EvalRun.EvalRunState.SUCCEEDED)).isFalse();
        assertThat(EvalRunLifecycle.cancelLegal(EvalRun.EvalRunState.FAILED)).isFalse();
    }

    @Test
    @DisplayName("模式词表：E/B/L 合法；小写/未知/空 非法")
    void modeVocabulary() {
        assertThat(EvalRunLifecycle.modeLegal("E")).isTrue();
        assertThat(EvalRunLifecycle.modeLegal("B")).isTrue();
        assertThat(EvalRunLifecycle.modeLegal("L")).isTrue();
        assertThat(EvalRunLifecycle.modeLegal("e")).isFalse();
        assertThat(EvalRunLifecycle.modeLegal("X")).isFalse();
        assertThat(EvalRunLifecycle.modeLegal(null)).isFalse();
    }

    @Test
    @DisplayName("恢复义务：仅 L 模式（真实靶场现场）要求恢复核验；E/B 无现场副作用")
    void recoveryRequiredOnlyForLiveMode() {
        assertThat(EvalRunLifecycle.requiresRecovery("L")).isTrue();
        assertThat(EvalRunLifecycle.requiresRecovery("E")).isFalse();
        assertThat(EvalRunLifecycle.requiresRecovery("B")).isFalse();
        assertThat(EvalRunLifecycle.requiresRecovery(null)).isFalse();
    }

    @Test
    @DisplayName("worker 失联孤儿卡因：L 孤儿带 recovery_unverified（不冒充现场已恢复）")
    void orphanReasonHonestAboutUnverifiedRecovery() {
        assertThat(EvalRunLifecycle.orphanTerminalReason("L"))
                .isEqualTo("worker_lost;recovery_unverified");
        assertThat(EvalRunLifecycle.orphanTerminalReason("E")).isEqualTo("worker_lost");
        assertThat(EvalRunLifecycle.orphanTerminalReason(null)).isEqualTo("worker_lost");
    }
}
