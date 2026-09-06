package com.objwww.pr.control.alert.domain.statemachine;

import com.objwww.pr.control.alert.domain.model.RcaRunState;
import com.objwww.pr.control.alert.domain.model.RcaTaskState;
import org.junit.jupiter.api.Test;

import java.util.EnumSet;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * UT-M4-01b：RcaStateContract 双读契约（M4-01 验收"新旧状态可双读 + 旧 fixture 回放"）。
 *
 * <p>契约集与枚举全集精确互斥完备；解析对契约外取值 fail-closed
 * （含 AM5 才引入的 WAITING_APPROVAL 与一切未知串）；V7 字面量旧 fixture 恒等回放。
 */
class RcaStateContractTest {

    // ---------------- 契约集互斥完备（穷举锚定，新增枚举不同步契约集必红） ----------------

    @Test
    void taskContractSetsAreDisjointAndComplete() {
        var legacy = EnumSet.copyOf(RcaStateContract.LEGACY_TASK_STATES);
        var am4 = EnumSet.copyOf(RcaStateContract.AM4_TASK_STATES);

        assertThat(legacy).doesNotContainAnyElementsOf(am4);
        var union = EnumSet.copyOf(legacy);
        union.addAll(am4);
        assertThat(union).containsExactlyInAnyOrder(RcaTaskState.values());
        assertThat(legacy).hasSize(6);
        assertThat(am4).hasSize(5);
    }

    @Test
    void runContractSetsAreDisjointAndComplete() {
        var legacy = EnumSet.copyOf(RcaStateContract.LEGACY_RUN_STATES);
        var am4 = EnumSet.copyOf(RcaStateContract.AM4_RUN_STATES);

        assertThat(legacy).doesNotContainAnyElementsOf(am4);
        var union = EnumSet.copyOf(legacy);
        union.addAll(am4);
        assertThat(union).containsExactlyInAnyOrder(RcaRunState.values());
        assertThat(legacy).hasSize(6);
        assertThat(am4).hasSize(3);
    }

    // ---------------- V7 字面量旧 fixture 恒等回放（AM1 行零迁移可读） ----------------

    @Test
    void legacyTaskFixtureLiteralsReplayIdentically() {
        assertThat(RcaStateContract.parseTaskState("READY")).isEqualTo(RcaTaskState.READY);
        assertThat(RcaStateContract.parseTaskState("LEASED")).isEqualTo(RcaTaskState.LEASED);
        assertThat(RcaStateContract.parseTaskState("RETRY_WAIT")).isEqualTo(RcaTaskState.RETRY_WAIT);
        assertThat(RcaStateContract.parseTaskState("DONE")).isEqualTo(RcaTaskState.DONE);
        assertThat(RcaStateContract.parseTaskState("CANCELLED")).isEqualTo(RcaTaskState.CANCELLED);
        assertThat(RcaStateContract.parseTaskState("DEAD")).isEqualTo(RcaTaskState.DEAD);
    }

    @Test
    void legacyRunFixtureLiteralsReplayIdentically() {
        assertThat(RcaStateContract.parseRunState("QUEUED")).isEqualTo(RcaRunState.QUEUED);
        assertThat(RcaStateContract.parseRunState("RUNNING")).isEqualTo(RcaRunState.RUNNING);
        assertThat(RcaStateContract.parseRunState("SUCCEEDED")).isEqualTo(RcaRunState.SUCCEEDED);
        assertThat(RcaStateContract.parseRunState("FAILED")).isEqualTo(RcaRunState.FAILED);
        assertThat(RcaStateContract.parseRunState("CANCELLED")).isEqualTo(RcaRunState.CANCELLED);
        assertThat(RcaStateContract.parseRunState("SUPERSEDED")).isEqualTo(RcaRunState.SUPERSEDED);
    }

    // ---------------- AM4 新值直读 + 全集 roundtrip（新增枚举不同步契约必红） ----------------

    @Test
    void am4NewValuesParseDirectly() {
        assertThat(RcaStateContract.parseTaskState("BLOCKED")).isEqualTo(RcaTaskState.BLOCKED);
        assertThat(RcaStateContract.parseTaskState("RUNNING")).isEqualTo(RcaTaskState.RUNNING);
        assertThat(RcaStateContract.parseTaskState("SKIPPED")).isEqualTo(RcaTaskState.SKIPPED);
        assertThat(RcaStateContract.parseTaskState("FAILED_TERMINAL"))
                .isEqualTo(RcaTaskState.FAILED_TERMINAL);
        assertThat(RcaStateContract.parseTaskState("STALE")).isEqualTo(RcaTaskState.STALE);

        assertThat(RcaStateContract.parseRunState("REPORTING")).isEqualTo(RcaRunState.REPORTING);
        assertThat(RcaStateContract.parseRunState("PARTIAL")).isEqualTo(RcaRunState.PARTIAL);
        assertThat(RcaStateContract.parseRunState("EXPIRED")).isEqualTo(RcaRunState.EXPIRED);
    }

    @Test
    void everyEnumValueRoundtripsThroughContract() {
        for (RcaTaskState state : RcaTaskState.values()) {
            assertThat(RcaStateContract.parseTaskState(state.name())).isEqualTo(state);
        }
        for (RcaRunState state : RcaRunState.values()) {
            assertThat(RcaStateContract.parseRunState(state.name())).isEqualTo(state);
        }
    }

    // ---------------- fail-closed：AM5 值 / 未知串 / 空值全拒绝 ----------------

    @Test
    void waitingApprovalRejectedOnBothSides() {
        assertThatThrownBy(() -> RcaStateContract.parseTaskState("WAITING_APPROVAL"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("WAITING_APPROVAL");
        assertThatThrownBy(() -> RcaStateContract.parseRunState("WAITING_APPROVAL"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("WAITING_APPROVAL");
    }

    @Test
    void unknownAndBlankRejected() {
        assertThatThrownBy(() -> RcaStateContract.parseTaskState("ready"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> RcaStateContract.parseTaskState("DONE "))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> RcaStateContract.parseTaskState("WAITING"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> RcaStateContract.parseRunState("FINISHED"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> RcaStateContract.parseTaskState(null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> RcaStateContract.parseTaskState("  "))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> RcaStateContract.parseRunState(null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> RcaStateContract.parseRunState(""))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
