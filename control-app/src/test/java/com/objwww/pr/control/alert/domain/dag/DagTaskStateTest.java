package com.objwww.pr.control.alert.domain.dag;

import com.objwww.pr.control.alert.domain.model.RcaTaskState;
import org.junit.jupiter.api.Test;

import java.util.EnumMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * UT-M4-01c：DagTaskState 持久层投影（M4-01 接缝闭合）。
 *
 * <p>期望映射逐值显式冻结；遍历 RcaTaskState.values() 比对——
 * 枚举新增取值而映射未同步时，此测试红（配合 fromPersistent 无 default
 * switch 的编译期双保险）。
 */
class DagTaskStateTest {

    @Test
    void persistentProjectionIsExhaustiveAndFrozen() {
        Map<RcaTaskState, DagTaskState> expected = new EnumMap<>(RcaTaskState.class);
        expected.put(RcaTaskState.BLOCKED, DagTaskState.BLOCKED);
        expected.put(RcaTaskState.READY, DagTaskState.READY);
        expected.put(RcaTaskState.LEASED, DagTaskState.RUNNING);
        expected.put(RcaTaskState.RUNNING, DagTaskState.RUNNING);
        expected.put(RcaTaskState.RETRY_WAIT, DagTaskState.RUNNING);
        expected.put(RcaTaskState.DONE, DagTaskState.SUCCEEDED);
        expected.put(RcaTaskState.DEAD, DagTaskState.DEAD);
        expected.put(RcaTaskState.SKIPPED, DagTaskState.SKIPPED);
        expected.put(RcaTaskState.CANCELLED, DagTaskState.FAILED_TERMINAL);
        expected.put(RcaTaskState.FAILED_TERMINAL, DagTaskState.FAILED_TERMINAL);
        expected.put(RcaTaskState.STALE, DagTaskState.FAILED_TERMINAL);

        for (RcaTaskState state : RcaTaskState.values()) {
            assertThat(DagTaskState.fromPersistent(state))
                    .as("RcaTaskState.%s 的推进器投影", state)
                    .isEqualTo(expected.get(state));
        }
        // 期望表覆盖全集：枚举扩值而本测试未同步时在此红
        assertThat(expected).containsKeys(RcaTaskState.values());
    }

    @Test
    void projectionTerminalSemantics() {
        // 在途投影不终态：推进器须继续等待
        assertThat(DagTaskState.fromPersistent(RcaTaskState.LEASED).isTerminal()).isFalse();
        assertThat(DagTaskState.fromPersistent(RcaTaskState.RUNNING).isTerminal()).isFalse();
        assertThat(DagTaskState.fromPersistent(RcaTaskState.RETRY_WAIT).isTerminal()).isFalse();
        assertThat(DagTaskState.fromPersistent(RcaTaskState.READY).isTerminal()).isFalse();
        assertThat(DagTaskState.fromPersistent(RcaTaskState.BLOCKED).isTerminal()).isFalse();

        // 收尾投影全终态：OPTIONAL 前置到此即"已了断"
        assertThat(DagTaskState.fromPersistent(RcaTaskState.DONE).isTerminal()).isTrue();
        assertThat(DagTaskState.fromPersistent(RcaTaskState.DEAD).isTerminal()).isTrue();
        assertThat(DagTaskState.fromPersistent(RcaTaskState.SKIPPED).isTerminal()).isTrue();
        assertThat(DagTaskState.fromPersistent(RcaTaskState.CANCELLED).isTerminal()).isTrue();
        assertThat(DagTaskState.fromPersistent(RcaTaskState.FAILED_TERMINAL).isTerminal()).isTrue();
        assertThat(DagTaskState.fromPersistent(RcaTaskState.STALE).isTerminal()).isTrue();
    }

    @Test
    void projectionNeverFabricatesBlockageOrSuccess() {
        // 只有持久层明示 BLOCKED 才投影 BLOCKED——推进器的阻塞判定由边计算得出，不得伪造
        for (RcaTaskState state : RcaTaskState.values()) {
            if (state != RcaTaskState.BLOCKED) {
                assertThat(DagTaskState.fromPersistent(state))
                        .as("非 BLOCKED 持久态不得投影为 BLOCKED：%s", state)
                        .isNotEqualTo(DagTaskState.BLOCKED);
            }
        }
        // 旧六态到视图后无 SUCCEEDED 假象：只有 DONE→SUCCEEDED 一条来源
        assertThat(DagTaskState.fromPersistent(RcaTaskState.DONE))
                .isEqualTo(DagTaskState.SUCCEEDED);
    }
}
