package com.objwww.pr.control.alert.domain.dag;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

/**
 * 纯函数环检测（AM4 M4-05）：DFS 三色标记。
 * 输入 = 任务节点集合 + 边集合（from→to 表示 from 是 to 的前置）；
 * 输出 = 任一环路径（含首尾重复节点），无环则 empty。
 *
 * <p>零 DB 副作用；节点序按字典序遍历，同一张图检测结果确定（可复现）。
 * 断点图（不连通子图）逐分量扫描，任一分量有环即报。
 */
public final class DagCycleDetector {

    private DagCycleDetector() {
    }

    /**
     * 找环。返回的环路径如 [a, b, c, a]（首尾同节点闭合）。
     * 自环（a→a）返回 [a, a]。
     */
    public static Optional<List<String>> findCycle(Collection<String> taskIds,
            Collection<TaskEdge> edges) {
        Map<String, List<String>> adjacency = new HashMap<>();
        Set<String> nodes = new LinkedHashSet<>(taskIds);
        for (TaskEdge e : edges) {
            nodes.add(e.fromTaskId());
            nodes.add(e.toTaskId());
            adjacency.computeIfAbsent(e.fromTaskId(), k -> new ArrayList<>()).add(e.toTaskId());
        }
        // 邻接表排序，保证遍历序确定
        adjacency.values().forEach(list -> list.sort(String::compareTo));

        Map<String, Integer> color = new HashMap<>(); // 0=白(未访问，缺省) 1=灰(在栈) 2=黑(完成)
        Deque<String> stack = new ArrayDeque<>();
        for (String start : new TreeSet<>(nodes)) {
            if (color.getOrDefault(start, 0) != 0) {
                continue;
            }
            Optional<List<String>> cycle = dfs(start, adjacency, color, stack);
            if (cycle.isPresent()) {
                return cycle;
            }
        }
        return Optional.empty();
    }

    private static Optional<List<String>> dfs(String node, Map<String, List<String>> adjacency,
            Map<String, Integer> color, Deque<String> stack) {
        color.put(node, 1);
        stack.addLast(node);
        for (String next : adjacency.getOrDefault(node, List.of())) {
            int c = color.getOrDefault(next, 0);
            if (c == 1) {
                // 回边：从栈中截取 next..node 再加 next 闭合
                List<String> cycle = new ArrayList<>();
                boolean inCycle = false;
                for (String s : stack) {
                    if (s.equals(next)) {
                        inCycle = true;
                    }
                    if (inCycle) {
                        cycle.add(s);
                    }
                }
                cycle.add(next);
                return Optional.of(cycle);
            }
            if (c == 0) {
                Optional<List<String>> cycle = dfs(next, adjacency, color, stack);
                if (cycle.isPresent()) {
                    return cycle;
                }
            }
        }
        stack.removeLast();
        color.put(node, 2);
        return Optional.empty();
    }
}
