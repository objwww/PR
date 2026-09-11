package com.objwww.pr.control.drill.domain.statemachine;

import com.objwww.pr.control.drill.domain.model.DrillJob;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * DR-02 状态机（§7.4）：合法/非法迁移全枚举核对——CLOSED 唯一来源 = VERIFYING；
 * 注入可能发生起停止/失败必先进恢复路径；RECOVERY_FAILED 保留处理入口。
 */
class DrillLifecycleTest {

    private static final Map<DrillJob.State, Set<DrillJob.State>> EXPECTED = Map.of(
            DrillJob.State.QUEUED, Set.of(DrillJob.State.PRECHECK,
                    DrillJob.State.CANCELLED),
            DrillJob.State.PRECHECK, Set.of(DrillJob.State.INJECTING,
                    DrillJob.State.FAILED, DrillJob.State.CANCELLED),
            DrillJob.State.INJECTING, Set.of(DrillJob.State.OBSERVING,
                    DrillJob.State.FAILED, DrillJob.State.RECOVERING),
            DrillJob.State.OBSERVING, Set.of(DrillJob.State.RECOVERING),
            DrillJob.State.RECOVERING, Set.of(DrillJob.State.VERIFYING,
                    DrillJob.State.RECOVERY_FAILED),
            DrillJob.State.RECOVERY_FAILED, Set.of(DrillJob.State.RECOVERING),
            DrillJob.State.VERIFYING, Set.of(DrillJob.State.CLOSED,
                    DrillJob.State.RECOVERY_FAILED));

    @Test
    @DisplayName("合法迁移表全枚举：表外一律非法（终态零出口）")
    void transitionTableExhaustive() {
        for (DrillJob.State from : DrillJob.State.values()) {
            Set<DrillJob.State> expected = EXPECTED.getOrDefault(from, Set.of());
            for (DrillJob.State to : DrillJob.State.values()) {
                assertThat(DrillLifecycle.transitionLegal(from, to))
                        .as("%s→%s", from, to)
                        .isEqualTo(expected.contains(to));
            }
        }
    }

    @Test
    @DisplayName("CLOSED 唯一来源 = VERIFYING（恢复核验完成才 CLOSED，DU12）")
    void closedOnlyFromVerifying() {
        for (DrillJob.State from : DrillJob.State.values()) {
            assertThat(DrillLifecycle.transitionLegal(from, DrillJob.State.CLOSED))
                    .as("%s→CLOSED", from)
                    .isEqualTo(from == DrillJob.State.VERIFYING);
        }
    }

    @Test
    @DisplayName("停止路径：注入前 CANCELLED；注入可能发生起必先进 RECOVERING")
    void stopPath() {
        assertThat(DrillLifecycle.stopPath(DrillJob.State.QUEUED))
                .isEqualTo(DrillJob.State.CANCELLED);
        assertThat(DrillLifecycle.stopPath(DrillJob.State.PRECHECK))
                .isEqualTo(DrillJob.State.CANCELLED);
        assertThat(DrillLifecycle.stopPath(DrillJob.State.INJECTING))
                .isEqualTo(DrillJob.State.RECOVERING);
        assertThat(DrillLifecycle.stopPath(DrillJob.State.OBSERVING))
                .isEqualTo(DrillJob.State.RECOVERING);
        assertThat(DrillLifecycle.stopPath(DrillJob.State.RECOVERING))
                .isEqualTo(DrillJob.State.RECOVERING);
        assertThat(DrillLifecycle.stopPath(DrillJob.State.VERIFYING))
                .isEqualTo(DrillJob.State.RECOVERING);
        assertThatThrownBy(() -> DrillLifecycle.stopPath(DrillJob.State.CLOSED))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> DrillLifecycle.stopPath(DrillJob.State.RECOVERY_FAILED))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("停止受理面：活动中合法；终态与恢复异常占位非法")
    void stopLegal() {
        assertThat(DrillLifecycle.stopLegal(DrillJob.State.QUEUED)).isTrue();
        assertThat(DrillLifecycle.stopLegal(DrillJob.State.VERIFYING)).isTrue();
        assertThat(DrillLifecycle.stopLegal(DrillJob.State.CLOSED)).isFalse();
        assertThat(DrillLifecycle.stopLegal(DrillJob.State.CANCELLED)).isFalse();
        assertThat(DrillLifecycle.stopLegal(DrillJob.State.FAILED)).isFalse();
        assertThat(DrillLifecycle.stopLegal(DrillJob.State.RECOVERY_FAILED)).isFalse();
    }

    @Test
    @DisplayName("占位语义：RECOVERY_FAILED 非终态且持有靶场占位（DU15）")
    void placeholderSemantics() {
        assertThat(DrillJob.State.RECOVERY_FAILED.isTerminal()).isFalse();
        assertThat(DrillJob.State.RECOVERY_FAILED.holdsEnvPlaceholder()).isTrue();
        assertThat(DrillJob.State.FAILED.isTerminal()).isTrue();
        assertThat(DrillJob.State.FAILED.holdsEnvPlaceholder()).isFalse();
    }
}
