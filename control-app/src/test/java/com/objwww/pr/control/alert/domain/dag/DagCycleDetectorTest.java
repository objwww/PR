package com.objwww.pr.control.alert.domain.dag;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * UT-AM4-05：DagCycleDetector 穷举——空图/单节点/菱形无环/自环/双节点互环/深环/断点图。
 */
class DagCycleDetectorTest {

    private static TaskEdge edge(String from, String to) {
        return new TaskEdge(from, to, DependencyType.REQUIRED);
    }

    @Test
    void emptyGraphHasNoCycle() {
        assertThat(DagCycleDetector.findCycle(Set.of(), List.of())).isEmpty();
    }

    @Test
    void singleNodeHasNoCycle() {
        assertThat(DagCycleDetector.findCycle(Set.of("a"), List.of())).isEmpty();
    }

    @Test
    void diamondHasNoCycle() {
        // a→b, a→c, b→d, c→d（菱形，汇聚无环）
        List<TaskEdge> edges = List.of(
                edge("a", "b"), edge("a", "c"), edge("b", "d"), edge("c", "d"));
        assertThat(DagCycleDetector.findCycle(Set.of("a", "b", "c", "d"), edges)).isEmpty();
    }

    @Test
    void selfLoopIsCycle() {
        Optional<List<String>> cycle =
                DagCycleDetector.findCycle(Set.of("a"), List.of(edge("a", "a")));
        assertThat(cycle).contains(List.of("a", "a"));
    }

    @Test
    void twoNodeMutualCycle() {
        List<TaskEdge> edges = List.of(edge("a", "b"), edge("b", "a"));
        Optional<List<String>> cycle = DagCycleDetector.findCycle(Set.of("a", "b"), edges);
        assertThat(cycle).isPresent();
        assertThat(cycle.get()).containsExactly("a", "b", "a");
    }

    @Test
    void deepCycleIsDetected() {
        // a→b→c→d→e→c（环深埋在中段；前驱链 a→b 不在环上）
        List<TaskEdge> edges = List.of(
                edge("a", "b"), edge("b", "c"), edge("c", "d"), edge("d", "e"), edge("e", "c"));
        Optional<List<String>> cycle =
                DagCycleDetector.findCycle(Set.of("a", "b", "c", "d", "e"), edges);
        assertThat(cycle).isPresent();
        assertThat(cycle.get()).containsExactly("c", "d", "e", "c");
    }

    @Test
    void disconnectedGraphWithCycleInOneComponent() {
        // 分量 1：p→q（无环）；分量 2：x→y→z→x（有环）
        List<TaskEdge> edges = List.of(
                edge("p", "q"), edge("x", "y"), edge("y", "z"), edge("z", "x"));
        Optional<List<String>> cycle =
                DagCycleDetector.findCycle(Set.of("p", "q", "x", "y", "z"), edges);
        assertThat(cycle).isPresent();
        assertThat(cycle.get()).containsExactly("x", "y", "z", "x");
    }

    @Test
    void disconnectedAcyclicGraphHasNoCycle() {
        List<TaskEdge> edges = List.of(edge("p", "q"), edge("x", "y"));
        assertThat(DagCycleDetector.findCycle(Set.of("p", "q", "x", "y"), edges)).isEmpty();
    }

    @Test
    void longAcyclicChainHasNoCycle() {
        List<TaskEdge> edges = List.of(
                edge("a", "b"), edge("b", "c"), edge("c", "d"), edge("d", "e"));
        assertThat(DagCycleDetector.findCycle(Set.of("a", "b", "c", "d", "e"), edges)).isEmpty();
    }

    @Test
    void resultIsDeterministicAcrossInputOrders() {
        List<TaskEdge> forward = List.of(edge("a", "b"), edge("b", "c"), edge("c", "a"));
        List<TaskEdge> reversed = List.of(edge("c", "a"), edge("b", "c"), edge("a", "b"));
        assertThat(DagCycleDetector.findCycle(Set.of("c", "b", "a"), forward))
                .isEqualTo(DagCycleDetector.findCycle(Set.of("a", "b", "c"), reversed));
    }
}
