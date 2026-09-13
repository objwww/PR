package com.objwww.pr.control.alert.application;

import com.objwww.pr.control.alert.domain.model.RcaRunState;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * WC-3（方案 v2 §5.1/§5.2）停止分类与控制信号单测（WC-T12 面）：
 * stopKindOf 决策表 / controlSignal 终态探针·失租转换·DB 故障 fail-stop /
 * aliveHeartbeat 平台 BooleanSupplier 适配。
 */
class ExecutionControlTest {

    private static final UUID RUN = UUID.randomUUID();

    @Test
    @DisplayName("wc3_t12a stopKindOf 决策表：活跃不停；CANCELLED/EXPIRED 精确分类；其余终态 RUN_TERMINAL")
    void stopKindOfDecisionTable() {
        assertThat(ExecutionControl.stopKindOf(null)).as("行缺席=按活跃放行").isNull();
        for (RcaRunState active : new RcaRunState[]{
                RcaRunState.QUEUED, RcaRunState.RUNNING, RcaRunState.REPORTING}) {
            assertThat(ExecutionControl.stopKindOf(active))
                    .as("活跃态 %s 不停", active).isNull();
        }
        assertThat(ExecutionControl.stopKindOf(RcaRunState.CANCELLED))
                .isEqualTo(ExecutionControl.STOP_RUN_CANCELLED);
        assertThat(ExecutionControl.stopKindOf(RcaRunState.EXPIRED))
                .isEqualTo(ExecutionControl.STOP_RUN_DEADLINE_EXCEEDED);
        for (RcaRunState terminal : new RcaRunState[]{
                RcaRunState.SUCCEEDED, RcaRunState.FAILED, RcaRunState.SUPERSEDED,
                RcaRunState.PARTIAL}) {
            assertThat(ExecutionControl.stopKindOf(terminal))
                    .as("终态 %s 归 RUN_TERMINAL", terminal)
                    .isEqualTo(ExecutionControl.STOP_RUN_TERMINAL);
        }
    }

    @Test
    @DisplayName("wc3_t12b controlSignal：心跳先行+活跃放行；终态抛类型化停止（含 kind）")
    void controlSignalStopsOnTerminalRun() {
        AtomicInteger beats = new AtomicInteger();
        Runnable signal = ExecutionControl.controlSignal(beats::incrementAndGet,
                id -> RcaRunState.RUNNING, RUN);
        assertThatCode(signal::run).doesNotThrowAnyException();
        assertThat(beats.get()).isEqualTo(1);

        Runnable cancelled = ExecutionControl.controlSignal(() -> { },
                id -> RcaRunState.CANCELLED, RUN);
        assertThatThrownBy(cancelled::run)
                .isInstanceOfSatisfying(ExecutionControl.StoppedException.class,
                        e -> assertThat(e.kind())
                                .isEqualTo(ExecutionControl.STOP_RUN_CANCELLED));

        Runnable expired = ExecutionControl.controlSignal(() -> { },
                id -> RcaRunState.EXPIRED, RUN);
        assertThatThrownBy(expired::run)
                .isInstanceOfSatisfying(ExecutionControl.StoppedException.class,
                        e -> assertThat(e.kind())
                                .isEqualTo(ExecutionControl.STOP_RUN_DEADLINE_EXCEEDED));
    }

    @Test
    @DisplayName("wc3_t12c controlSignal：心跳 LEASE_LOST 契约（IllegalStateException）转类型化停止")
    void controlSignalConvertsLeaseLostIllegalState() {
        Runnable signal = ExecutionControl.controlSignal(
                () -> {
                    throw new IllegalStateException(
                            "LEASE_LOST: task 租约续期失败（易主或过期），停止后续动作");
                },
                id -> RcaRunState.RUNNING, RUN);
        assertThatThrownBy(signal::run)
                .isInstanceOfSatisfying(ExecutionControl.StoppedException.class,
                        e -> {
                            assertThat(e.kind()).isEqualTo(ExecutionControl.STOP_LEASE_LOST);
                            assertThat(e).hasMessageContaining("LEASE_LOST");
                        });
    }

    @Test
    @DisplayName("wc3_t12d controlSignal：Run 状态读失败异常穿透（fail-stop，不按缓存放行）")
    void controlSignalDbFailureFailsStop() {
        RuntimeException boom = new RuntimeException("DB 不可读");
        Runnable signal = ExecutionControl.controlSignal(() -> { }, id -> {
            throw boom;
        }, RUN);
        assertThatThrownBy(signal::run).isSameAs(boom);
    }

    @Test
    @DisplayName("wc3_t12e aliveHeartbeat：活着 true 且探针已跑；停止异常原样穿透（不折 false）")
    void aliveHeartbeatAdapter() {
        AtomicInteger probes = new AtomicInteger();
        java.util.function.BooleanSupplier alive =
                ExecutionControl.aliveHeartbeat(probes::incrementAndGet);
        assertThat(alive.getAsBoolean()).isTrue();
        assertThat(probes.get()).isEqualTo(1);

        java.util.function.BooleanSupplier stopped = ExecutionControl.aliveHeartbeat(
                () -> {
                    throw new ExecutionControl.StoppedException(
                            ExecutionControl.STOP_RUN_CANCELLED, "run 已取消");
                });
        assertThatThrownBy(stopped::getAsBoolean)
                .isInstanceOfSatisfying(ExecutionControl.StoppedException.class,
                        e -> assertThat(e.kind())
                                .isEqualTo(ExecutionControl.STOP_RUN_CANCELLED));
    }
}
