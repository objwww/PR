package com.objwww.pr.control.alert.domain.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.EnumSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * MC32/P0-4 断言钉（合规面）：RcaRunState 九态封闭集<b>无"等待人工"态</b>——
 * 等待人工不是 run 生命周期状态（人工补充材料走 incident_operator_material 受理面，
 * MC31/32；CONFIG_SWITCH 的 WAITING_SAFE_POINT 是配置切换子域内部态，非 run 态）。
 * 活跃集与 DB ck（V12 ck_rca_run_finish + uq_rca_run_active_incident 谓词）同源。
 */
class RcaRunStateTest {

    /** 九态封闭集（与 V12 ck_rca_run_finish 词表同源；增删状态必须先改 DB ck） */
    private static final Set<RcaRunState> NINE_STATES = EnumSet.allOf(RcaRunState.class);

    @Test
    @DisplayName("MC32：九态封闭集恰为词表，无 WAIT/MANUAL 等待人工态")
    void nineStatesContainNoManualWaitState() {
        assertThat(NINE_STATES).containsExactlyInAnyOrder(
                RcaRunState.QUEUED, RcaRunState.RUNNING, RcaRunState.SUCCEEDED,
                RcaRunState.FAILED, RcaRunState.CANCELLED, RcaRunState.SUPERSEDED,
                RcaRunState.REPORTING, RcaRunState.PARTIAL, RcaRunState.EXPIRED);
        assertThat(NINE_STATES).as("无人工等待态：等待人工=材料受理面语义，非 run 态")
                .noneMatch(s -> s.name().contains("WAIT") || s.name().contains("MANUAL"));
    }

    @Test
    @DisplayName("活跃集 = QUEUED/RUNNING/REPORTING（V12 ck + uq 谓词同源；其余=终态需 finished_at）")
    void activeSetMatchesDatabasePredicate() {
        assertThat(EnumSet.of(RcaRunState.QUEUED, RcaRunState.RUNNING,
                RcaRunState.REPORTING)).allMatch(RcaRunState::isActive);
        Set<RcaRunState> terminal = EnumSet.complementOf(EnumSet.of(
                RcaRunState.QUEUED, RcaRunState.RUNNING, RcaRunState.REPORTING));
        assertThat(terminal).as("非活跃态 = 终态集（DB ck：finished_at 不得为空）")
                .allMatch(s -> !s.isActive())
                .containsExactlyInAnyOrder(RcaRunState.SUCCEEDED, RcaRunState.FAILED,
                        RcaRunState.CANCELLED, RcaRunState.SUPERSEDED,
                        RcaRunState.PARTIAL, RcaRunState.EXPIRED);
    }
}
