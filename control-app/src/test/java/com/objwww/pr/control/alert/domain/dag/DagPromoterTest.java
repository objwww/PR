package com.objwww.pr.control.alert.domain.dag;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * UT-AM4-06：DagPromoter——并发前驱完成 / 可选前驱失败 / REQUIRED 失败收敛 SKIPPED /
 * 冲突边拒绝 / 混合矩阵穷举。输出 = DagPromotion{ready, skipped} 两个不相交集。
 */
class DagPromoterTest {

    private static TaskEdge required(String from, String to) {
        return new TaskEdge(from, to, DependencyType.REQUIRED);
    }

    private static TaskEdge optional(String from, String to) {
        return new TaskEdge(from, to, DependencyType.OPTIONAL);
    }

    @Test
    void zeroPredecessorBlockedTaskIsPromoted() {
        Map<String, DagTaskState> states = Map.of("t", DagTaskState.BLOCKED);
        DagPromotion result = DagPromoter.evaluate(states, List.of());
        assertThat(result.ready()).containsExactly("t");
        assertThat(result.skipped()).isEmpty();
    }

    @Test
    void concurrentPredecessorsAllSucceededPromotes() {
        Map<String, DagTaskState> states = Map.of(
                "a", DagTaskState.SUCCEEDED,
                "b", DagTaskState.SUCCEEDED,
                "c", DagTaskState.SUCCEEDED,
                "t", DagTaskState.BLOCKED);
        List<TaskEdge> edges = List.of(
                required("a", "t"), required("b", "t"), required("c", "t"));
        assertThat(DagPromoter.evaluate(states, edges).ready()).containsExactly("t");
    }

    @Test
    void concurrentPredecessorsPartiallyDoneStaysBlocked() {
        // REQUIRED 在途：既不 READY 也不 SKIPPED——保持 BLOCKED（不在任一输出集）
        Map<String, DagTaskState> states = Map.of(
                "a", DagTaskState.SUCCEEDED,
                "b", DagTaskState.RUNNING,
                "t", DagTaskState.BLOCKED);
        DagPromotion result = DagPromoter.evaluate(states,
                List.of(required("a", "t"), required("b", "t")));
        assertThat(result.ready()).isEmpty();
        assertThat(result.skipped()).isEmpty();
    }

    @Test
    void optionalPredecessorTerminalStatesAllPromote() {
        for (DagTaskState optionalEnd : DagTaskState.TERMINAL) {
            Map<String, DagTaskState> states = Map.of(
                    "req", DagTaskState.SUCCEEDED,
                    "opt", optionalEnd,
                    "t", DagTaskState.BLOCKED);
            DagPromotion result = DagPromoter.evaluate(states,
                    List.of(required("req", "t"), optional("opt", "t")));
            assertThat(result.ready())
                    .as("OPTIONAL 前置 %s 应放行", optionalEnd)
                    .containsExactly("t");
            assertThat(result.skipped()).isEmpty();
        }
    }

    /** REQUIRED 前驱终态未成功（FAILED_TERMINAL/DEAD/SKIPPED）→ 后继收敛 SKIPPED，不永远 BLOCKED */
    @Test
    void requiredTerminalFailureConvergesToSkipped() {
        for (DagTaskState failure : new DagTaskState[]{
                DagTaskState.FAILED_TERMINAL, DagTaskState.DEAD, DagTaskState.SKIPPED}) {
            Map<String, DagTaskState> states = Map.of(
                    "req", failure, "t", DagTaskState.BLOCKED);
            DagPromotion result = DagPromoter.evaluate(states, List.of(required("req", "t")));
            assertThat(result.skipped())
                    .as("REQUIRED 前置 %s → 后继收敛 SKIPPED", failure)
                    .containsExactly("t");
            assertThat(result.ready()).isEmpty();
        }
        assertThat(DagPromoter.SKIP_REASON).isEqualTo("REQUIRED_PREDECESSOR_NOT_SUCCEEDED");
    }

    @Test
    void requiredFailureSkipsEvenWhenOptionalStillRunning() {
        // REQUIRED 已死结局已定，不等 OPTIONAL 了断
        Map<String, DagTaskState> states = Map.of(
                "req", DagTaskState.FAILED_TERMINAL,
                "opt", DagTaskState.RUNNING,
                "t", DagTaskState.BLOCKED);
        DagPromotion result = DagPromoter.evaluate(states,
                List.of(required("req", "t"), optional("opt", "t")));
        assertThat(result.skipped()).containsExactly("t");
    }

    @Test
    void skipCascadesThroughSkippedPredecessor() {
        // a 失败 → b 收敛 SKIPPED；下一轮 b 已是 SKIPPED → c 也收敛 SKIPPED（链式终局收敛）
        Map<String, DagTaskState> round1 = Map.of(
                "a", DagTaskState.FAILED_TERMINAL,
                "b", DagTaskState.BLOCKED,
                "c", DagTaskState.BLOCKED);
        List<TaskEdge> edges = List.of(required("a", "b"), required("b", "c"));
        assertThat(DagPromoter.evaluate(round1, edges).skipped()).containsExactly("b");

        Map<String, DagTaskState> round2 = Map.of(
                "a", DagTaskState.FAILED_TERMINAL,
                "b", DagTaskState.SKIPPED,
                "c", DagTaskState.BLOCKED);
        assertThat(DagPromoter.evaluate(round2, edges).skipped()).containsExactly("c");
    }

    /** 混合矩阵穷举：REQUIRED 前置 × OPTIONAL 前置全 7×7 状态组合，三路结果精确判定 */
    @Test
    void mixedRequiredOptionalMatrixExhaustive() {
        for (DagTaskState req : DagTaskState.values()) {
            for (DagTaskState opt : DagTaskState.values()) {
                Map<String, DagTaskState> states = Map.of(
                        "req", req, "opt", opt, "t", DagTaskState.BLOCKED);
                DagPromotion result = DagPromoter.evaluate(states,
                        List.of(required("req", "t"), optional("opt", "t")));

                boolean expectReady = req == DagTaskState.SUCCEEDED && opt.isTerminal();
                boolean expectSkipped = req.isTerminal() && req != DagTaskState.SUCCEEDED;
                assertThat(result.ready().contains("t"))
                        .as("REQUIRED=%s OPTIONAL=%s ready=%s", req, opt, expectReady)
                        .isEqualTo(expectReady);
                assertThat(result.skipped().contains("t"))
                        .as("REQUIRED=%s OPTIONAL=%s skipped=%s", req, opt, expectSkipped)
                        .isEqualTo(expectSkipped);
            }
        }
    }

    /** 冲突边拒绝：同一对节点 REQUIRED+OPTIONAL 并存，两种输入顺序都抛 IAE */
    @Test
    void conflictingEdgeTypesAreRejectedRegardlessOfOrder() {
        Map<String, DagTaskState> states = Map.of(
                "a", DagTaskState.SUCCEEDED, "t", DagTaskState.BLOCKED);
        assertThatThrownBy(() -> DagPromoter.evaluate(states,
                List.of(required("a", "t"), optional("a", "t"))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("冲突边");
        assertThatThrownBy(() -> DagPromoter.evaluate(states,
                List.of(optional("a", "t"), required("a", "t"))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("冲突边");
    }

    @Test
    void nonBlockedTasksAreNeverReturned() {
        Map<String, DagTaskState> states = new HashMap<>();
        for (DagTaskState s : DagTaskState.values()) {
            if (s != DagTaskState.BLOCKED) {
                states.put("t-" + s, s);
            }
        }
        DagPromotion result = DagPromoter.evaluate(states, List.of());
        assertThat(result.ready()).isEmpty();
        assertThat(result.skipped()).isEmpty();
    }

    @Test
    void multiTargetPromotionIsIndependent() {
        Map<String, DagTaskState> states = Map.of(
                "a", DagTaskState.SUCCEEDED,
                "b", DagTaskState.BLOCKED,
                "c", DagTaskState.BLOCKED,
                "d", DagTaskState.BLOCKED,
                "e", DagTaskState.RUNNING,
                "f", DagTaskState.BLOCKED,
                "g", DagTaskState.DEAD);
        List<TaskEdge> edges = List.of(
                required("a", "b"), required("a", "c"),
                required("e", "d"), required("g", "f"));
        DagPromotion result = DagPromoter.evaluate(states, edges);
        assertThat(result.ready()).containsExactlyInAnyOrder("b", "c");
        assertThat(result.skipped()).containsExactly("f");
    }

    @Test
    void edgeReferencingUnknownTaskIsRejected() {
        Map<String, DagTaskState> states = Map.of("t", DagTaskState.BLOCKED);
        assertThatThrownBy(() -> DagPromoter.evaluate(states, List.of(required("ghost", "t"))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("ghost");
    }

    @Test
    void duplicateSameTypeEdgesDoNotChangeResult() {
        Map<String, DagTaskState> states = Map.of(
                "a", DagTaskState.SUCCEEDED, "t", DagTaskState.BLOCKED);
        List<TaskEdge> edges = List.of(required("a", "t"), required("a", "t"));
        assertThat(DagPromoter.evaluate(states, edges).ready()).containsExactly("t");
    }

    @Test
    void promotableToReadyDelegatesToEvaluate() {
        Map<String, DagTaskState> states = Map.of(
                "a", DagTaskState.SUCCEEDED, "t", DagTaskState.BLOCKED,
                "x", DagTaskState.DEAD, "y", DagTaskState.BLOCKED);
        List<TaskEdge> edges = List.of(required("a", "t"), required("x", "y"));
        assertThat(DagPromoter.promotableToReady(states, edges))
                .isEqualTo(DagPromoter.evaluate(states, edges).ready());
    }

    @Test
    void dagPromotionRejectsOverlappingSets() {
        assertThatThrownBy(() -> new DagPromotion(Set.of("t"), Set.of("t")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void terminalStatesAreCompleteAndDisjointFromInFlight() {
        assertThat(DagTaskState.TERMINAL).containsExactlyInAnyOrder(
                DagTaskState.SUCCEEDED, DagTaskState.SKIPPED,
                DagTaskState.FAILED_TERMINAL, DagTaskState.DEAD);
        for (DagTaskState s : DagTaskState.values()) {
            assertThat(s.isTerminal()).isEqualTo(DagTaskState.TERMINAL.contains(s));
        }
    }
}
