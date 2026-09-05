package com.objwww.pr.control.alert.domain.dag;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * UT-AM4-06：DagPromoter——并发前驱完成 / 可选前驱失败 / REQUIRED 失败不推进 / 混合矩阵穷举。
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
        assertThat(DagPromoter.promotableToReady(states, List.of())).containsExactly("t");
    }

    @Test
    void concurrentPredecessorsAllSucceededPromotes() {
        // 并发前驱 a/b/c 全部 SUCCEEDED → t 推进
        Map<String, DagTaskState> states = Map.of(
                "a", DagTaskState.SUCCEEDED,
                "b", DagTaskState.SUCCEEDED,
                "c", DagTaskState.SUCCEEDED,
                "t", DagTaskState.BLOCKED);
        List<TaskEdge> edges = List.of(
                required("a", "t"), required("b", "t"), required("c", "t"));
        assertThat(DagPromoter.promotableToReady(states, edges)).containsExactly("t");
    }

    @Test
    void concurrentPredecessorsPartiallyDoneDoesNotPromote() {
        Map<String, DagTaskState> states = Map.of(
                "a", DagTaskState.SUCCEEDED,
                "b", DagTaskState.RUNNING,
                "t", DagTaskState.BLOCKED);
        List<TaskEdge> edges = List.of(required("a", "t"), required("b", "t"));
        assertThat(DagPromoter.promotableToReady(states, edges)).isEmpty();
    }

    @Test
    void optionalPredecessorFailedTerminalStillPromotes() {
        // OPTIONAL 前置 FAILED_TERMINAL/DEAD/SKIPPED 均视为"已了断"
        for (DagTaskState optionalEnd : DagTaskState.TERMINAL) {
            Map<String, DagTaskState> states = Map.of(
                    "req", DagTaskState.SUCCEEDED,
                    "opt", optionalEnd,
                    "t", DagTaskState.BLOCKED);
            List<TaskEdge> edges = List.of(required("req", "t"), optional("opt", "t"));
            assertThat(DagPromoter.promotableToReady(states, edges))
                    .as("OPTIONAL 前置 %s 应放行", optionalEnd)
                    .containsExactly("t");
        }
    }

    @Test
    void requiredFailureNeverPromotes() {
        // REQUIRED 前置是 SUCCEEDED 以外的任何状态（含 FAILED_TERMINAL/DEAD）都不推进
        for (DagTaskState reqState : DagTaskState.values()) {
            Map<String, DagTaskState> states = Map.of(
                    "req", reqState,
                    "t", DagTaskState.BLOCKED);
            List<TaskEdge> edges = List.of(required("req", "t"));
            boolean expect = reqState == DagTaskState.SUCCEEDED;
            assertThat(DagPromoter.promotableToReady(states, edges).contains("t"))
                    .as("REQUIRED 前置 %s 推进=%s", reqState, expect)
                    .isEqualTo(expect);
        }
    }

    /** 混合矩阵穷举：REQUIRED 前置 × OPTIONAL 前置全 7×7 状态组合 */
    @Test
    void mixedRequiredOptionalMatrixExhaustive() {
        for (DagTaskState req : DagTaskState.values()) {
            for (DagTaskState opt : DagTaskState.values()) {
                Map<String, DagTaskState> states = Map.of(
                        "req", req, "opt", opt, "t", DagTaskState.BLOCKED);
                List<TaskEdge> edges = List.of(required("req", "t"), optional("opt", "t"));
                boolean expect = req == DagTaskState.SUCCEEDED && opt.isTerminal();
                assertThat(DagPromoter.promotableToReady(states, edges).contains("t"))
                        .as("REQUIRED=%s OPTIONAL=%s 推进=%s", req, opt, expect)
                        .isEqualTo(expect);
            }
        }
    }

    @Test
    void nonBlockedTasksAreNeverReturned() {
        Map<String, DagTaskState> states = new HashMap<>();
        for (DagTaskState s : DagTaskState.values()) {
            if (s != DagTaskState.BLOCKED) {
                states.put("t-" + s, s);
            }
        }
        assertThat(DagPromoter.promotableToReady(states, List.of())).isEmpty();
    }

    @Test
    void multiTargetPromotionIsIndependent() {
        // a 成功 → b/c 都可推进；d 的前置未了断不推进
        Map<String, DagTaskState> states = Map.of(
                "a", DagTaskState.SUCCEEDED,
                "b", DagTaskState.BLOCKED,
                "c", DagTaskState.BLOCKED,
                "d", DagTaskState.BLOCKED,
                "e", DagTaskState.RUNNING);
        List<TaskEdge> edges = List.of(
                required("a", "b"), required("a", "c"), required("e", "d"));
        assertThat(DagPromoter.promotableToReady(states, edges))
                .containsExactlyInAnyOrder("b", "c");
    }

    @Test
    void edgeReferencingUnknownTaskIsRejected() {
        Map<String, DagTaskState> states = Map.of("t", DagTaskState.BLOCKED);
        List<TaskEdge> edges = List.of(required("ghost", "t"));
        assertThatThrownBy(() -> DagPromoter.promotableToReady(states, edges))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("ghost");
    }

    @Test
    void duplicateEdgesDoNotChangeResult() {
        Map<String, DagTaskState> states = Map.of(
                "a", DagTaskState.SUCCEEDED, "t", DagTaskState.BLOCKED);
        List<TaskEdge> edges = List.of(required("a", "t"), required("a", "t"));
        assertThat(DagPromoter.promotableToReady(states, edges)).containsExactly("t");
    }

    @Test
    void terminalStatesAreCompleteAndDisjointFromInFlight() {
        // 终态集契约穷举：SUCCEEDED/SKIPPED/FAILED_TERMINAL/DEAD 且仅此四个
        assertThat(DagTaskState.TERMINAL).containsExactlyInAnyOrder(
                DagTaskState.SUCCEEDED, DagTaskState.SKIPPED,
                DagTaskState.FAILED_TERMINAL, DagTaskState.DEAD);
        for (DagTaskState s : DagTaskState.values()) {
            assertThat(s.isTerminal()).isEqualTo(DagTaskState.TERMINAL.contains(s));
        }
    }
}
