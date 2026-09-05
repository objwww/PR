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
 * 纯函数推进器（AM4 M4-06）：task 状态集合 × 边 → BLOCKED 任务的确定性收敛。
 *
 * <p>裁决规则（全部确定性，模型无调度权，INV-AM4-2）：
 * <ul>
 *   <li><b>可 READY</b>：全部 REQUIRED 前置 SUCCEEDED 且全部 OPTIONAL 前置已终止
 *       （SUCCEEDED/SKIPPED/FAILED_TERMINAL/DEAD 任一）；零前置的 BLOCKED 立即可 READY；</li>
 *   <li><b>收敛 SKIPPED（终局收敛）</b>：任一 REQUIRED 前置终态未成功
 *       （FAILED_TERMINAL/DEAD/SKIPPED）→ 后继不得永远 BLOCKED，确定性地收敛为 SKIPPED，
 *       原因恒为 SKIP_REASON。OPTIONAL 前置的状态不影响该收敛（REQUIRED 已死，结局已定，
 *       不必等 OPTIONAL 了断）；SKIPPED 前驱的级联跳过保证被剪枝的链也能收敛；</li>
 *   <li>其余（REQUIRED 在途、或 OPTIONAL 未了断）→ 保持 BLOCKED，不在任一输出集。</li>
 * </ul>
 *
 * <p>输入校验：边引用状态集合外的任务、或同一对节点同时存在 REQUIRED 与 OPTIONAL 边
 * （语义冲突）→ 抛 IllegalArgumentException，结果不由输入顺序决定。
 * 不写库；输出集合按字典序（可复现）。
 */
public final class DagPromoter {

    /** 收敛 SKIPPED 的唯一原因（终局收敛只由 REQUIRED 前驱终态未成功触发） */
    public static final String SKIP_REASON = "REQUIRED_PREDECESSOR_NOT_SUCCEEDED";

    private DagPromoter() {
    }

    /** 全量裁决：可 READY 集 + 收敛 SKIPPED 集（不相交） */
    public static DagPromotion evaluate(Map<String, DagTaskState> states,
            Collection<TaskEdge> edges) {
        validate(states, edges);

        Map<String, List<TaskEdge>> inbound = new HashMap<>();
        for (TaskEdge e : edges) {
            inbound.computeIfAbsent(e.toTaskId(), k -> new ArrayList<>()).add(e);
        }

        Set<String> ready = new TreeSet<>();
        Set<String> skipped = new TreeSet<>();
        for (Map.Entry<String, DagTaskState> entry : states.entrySet()) {
            if (entry.getValue() != DagTaskState.BLOCKED) {
                continue;
            }
            List<TaskEdge> predecessors = dedupByFrom(inbound.getOrDefault(entry.getKey(), List.of()));
            if (hasFailedRequired(predecessors, states)) {
                skipped.add(entry.getKey());
            } else if (canPromote(predecessors, states)) {
                ready.add(entry.getKey());
            }
        }
        return new DagPromotion(ready, skipped);
    }

    /** 便捷方法：只取可 READY 集（等价于 evaluate(states, edges).ready()） */
    public static Set<String> promotableToReady(Map<String, DagTaskState> states,
            Collection<TaskEdge> edges) {
        return evaluate(states, edges).ready();
    }

    private static void validate(Map<String, DagTaskState> states, Collection<TaskEdge> edges) {
        Map<List<String>, DependencyType> pairTypes = new HashMap<>();
        for (TaskEdge e : edges) {
            if (!states.containsKey(e.fromTaskId()) || !states.containsKey(e.toTaskId())) {
                throw new IllegalArgumentException(
                        "边引用了状态集合外的任务: " + e.fromTaskId() + " -> " + e.toTaskId());
            }
            List<String> pair = List.of(e.fromTaskId(), e.toTaskId());
            DependencyType prev = pairTypes.putIfAbsent(pair, e.dependencyType());
            if (prev != null && prev != e.dependencyType()) {
                throw new IllegalArgumentException("同一对节点存在冲突边（REQUIRED 与 OPTIONAL 并存）: "
                        + e.fromTaskId() + " -> " + e.toTaskId());
            }
        }
    }

    /** 任一 REQUIRED 前置终态未成功（FAILED_TERMINAL/DEAD/SKIPPED） */
    private static boolean hasFailedRequired(List<TaskEdge> predecessors,
            Map<String, DagTaskState> states) {
        for (TaskEdge e : predecessors) {
            if (e.dependencyType() == DependencyType.REQUIRED) {
                DagTaskState pred = states.get(e.fromTaskId());
                if (pred.isTerminal() && pred != DagTaskState.SUCCEEDED) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean canPromote(List<TaskEdge> predecessors,
            Map<String, DagTaskState> states) {
        for (TaskEdge e : predecessors) {
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

    /** 重复边（同 from 同 type）只判定一次 */
    private static List<TaskEdge> dedupByFrom(List<TaskEdge> edges) {
        Set<String> seen = new LinkedHashSet<>();
        List<TaskEdge> deduped = new ArrayList<>();
        for (TaskEdge e : edges) {
            if (seen.add(e.fromTaskId())) {
                deduped.add(e);
            }
        }
        return deduped;
    }
}
