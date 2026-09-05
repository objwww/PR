package com.objwww.pr.control.alert.domain.dag;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * 纯函数推进器（AM4 M4-06）：task 状态集合 × 边 → 哪些 BLOCKED 任务可转 READY。
 *
 * <p>推进规则（全部确定性，模型无调度权，INV-AM4-2）：
 * <ul>
 *   <li>全部 REQUIRED 前置必须 SUCCEEDED（FAILED_TERMINAL/DEAD/SKIPPED 均不推进）；</li>
 *   <li>全部 OPTIONAL 前置必须已终止（SUCCEEDED/SKIPPED/FAILED_TERMINAL/DEAD 任一）；</li>
 *   <li>零前置的 BLOCKED 任务立即可 READY（空集 vacuous truth）。</li>
 * </ul>
 * 不写库；返回集合按字典序（可复现）。边引用了状态集合中不存在的任务 = 输入契约违约，抛异常。
 */
public final class DagPromoter {

    private DagPromoter() {
    }

    public static Set<String> promotableToReady(Map<String, DagTaskState> states,
            Collection<TaskEdge> edges) {
        for (TaskEdge e : edges) {
            if (!states.containsKey(e.fromTaskId()) || !states.containsKey(e.toTaskId())) {
                throw new IllegalArgumentException(
                        "边引用了状态集合外的任务: " + e.fromTaskId() + " -> " + e.toTaskId());
            }
        }

        Map<String, List<TaskEdge>> inbound = new HashMap<>();
        for (TaskEdge e : edges) {
            inbound.computeIfAbsent(e.toTaskId(), k -> new ArrayList<>()).add(e);
        }

        Set<String> promotable = new TreeSet<>();
        for (Map.Entry<String, DagTaskState> entry : states.entrySet()) {
            if (entry.getValue() != DagTaskState.BLOCKED) {
                continue;
            }
            if (canPromote(inbound.getOrDefault(entry.getKey(), List.of()), states)) {
                promotable.add(entry.getKey());
            }
        }
        return promotable;
    }

    private static boolean canPromote(List<TaskEdge> predecessors,
            Map<String, DagTaskState> states) {
        Set<String> seen = new LinkedHashSet<>();
        for (TaskEdge e : predecessors) {
            if (!seen.add(e.fromTaskId())) {
                continue; // 重复边不重复判定
            }
            DagTaskState pred = states.get(e.fromTaskId());
            if (e.dependencyType() == DependencyType.REQUIRED) {
                if (pred != DagTaskState.SUCCEEDED) {
                    return false;
                }
            } else {
                if (!pred.isTerminal()) {
                    return false;
                }
            }
        }
        return true;
    }
}
