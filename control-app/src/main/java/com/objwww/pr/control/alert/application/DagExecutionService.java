package com.objwww.pr.control.alert.application;

import com.objwww.pr.control.alert.domain.dag.DagCycleDetector;
import com.objwww.pr.control.alert.domain.dag.DagPromoter;
import com.objwww.pr.control.alert.domain.dag.DagPromotion;
import com.objwww.pr.control.alert.domain.dag.DagTaskState;
import com.objwww.pr.control.alert.domain.dag.DependencyType;
import com.objwww.pr.control.alert.domain.dag.TaskEdge;
import com.objwww.pr.control.alert.domain.model.RcaTask;
import com.objwww.pr.control.alert.domain.model.RcaTaskState;
import com.objwww.pr.control.alert.domain.repository.RcaTaskRepository;
import com.objwww.pr.control.alert.domain.repository.TaskEdgeRepository;
import com.objwww.pr.control.alert.domain.statemachine.RcaTaskStateMachine;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

/**
 * DAG 执行服务（AM4 M4-05/06 接线层）：备料纯函数（DagCycleDetector/DagPromoter）
 * 与持久化端口之间的应用层组合，模型无调度权（INV-AM4-2）——所有图变更都过这里的
 * 确定性裁决。生产调用方 = M4-25 Planner 提案落库 / M4-26 Supervisor 终态回执。
 * 装配面 = AlertFlowConfig（docker profile 手工 Bean，本项目无组件扫描服务）。
 */
public class DagExecutionService {

    private final TaskEdgeRepository edges;
    private final RcaTaskRepository tasks;

    public DagExecutionService(TaskEdgeRepository edges, RcaTaskRepository tasks) {
        this.edges = Objects.requireNonNull(edges);
        this.tasks = Objects.requireNonNull(tasks);
    }

    /**
     * M4-05 建边：先环检测（现有边 + 候选边整体判定）后落库——成环边拒绝且零写入。
     * 同 run 域/存在性/自环/重复边由 V8+V18 DB 约束兜底；环检测无法用约束表达，
     * 是本方法的存在理由。跨 run/未知任务直接落库撞 V18 组合外键（不预查，DB 是权威）。
     */
    public void addEdge(UUID runId, UUID fromTaskId, UUID toTaskId, DependencyType dependencyType) {
        List<TaskEdge> candidate = new ArrayList<>(edges.findByRunId(runId));
        candidate.add(new TaskEdge(fromTaskId.toString(), toTaskId.toString(), dependencyType));
        DagCycleDetector.findCycle(List.of(), candidate).ifPresent(cycle -> {
            throw new IllegalArgumentException("建边被拒（会成环）: " + String.join(" -> ", cycle));
        });
        edges.insert(runId, fromTaskId, toTaskId, dependencyType);
    }

    /**
     * M4-06 推进器：task 终态回执后对全 run 图做确定性收敛——
     * 全 REQUIRED 前驱 SUCCEEDED 且 OPTIONAL 前驱已终态的 BLOCKED → READY；
     * 任一 REQUIRED 前驱终态未成功的 BLOCKED → SKIPPED（GX-5 终局收敛，不永久 BLOCKED）。
     *
     * <p>不动点迭代：单遍评估只收敛一层（A 失败 → B SKIPPED，C 看到的 B 仍 BLOCKED），
     * 迭代到无新迁移为止，被剪枝链在同一调用内级联收敛。每轮至少迁出一个 BLOCKED，
     * 图有限 ⇒ 必终止。逐任务过状态机 + 仓储 CAS（WHERE state='BLOCKED'）落库：
     * 两个前驱并发回执时恰一次迁移生效，败者静默（重评幂等）。
     *
     * @return 累计裁决集（可复现；落库竞态不影响返回值语义）
     */
    public DagPromotion promoteOnTerminal(UUID runId) {
        Map<String, DagTaskState> states = new LinkedHashMap<>();
        for (RcaTask task : tasks.findByRunId(runId)) {
            states.put(task.id().toString(), DagTaskState.fromPersistent(task.state()));
        }
        List<TaskEdge> runEdges = edges.findByRunId(runId);

        Set<String> ready = new TreeSet<>();
        Set<String> skipped = new TreeSet<>();
        while (true) {
            DagPromotion round = DagPromoter.evaluate(states, runEdges);
            if (round.ready().isEmpty() && round.skipped().isEmpty()) {
                return new DagPromotion(ready, skipped);
            }
            round.ready().forEach(id -> transition(states, id, RcaTaskState.READY));
            round.skipped().forEach(id -> transition(states, id, RcaTaskState.SKIPPED));
            ready.addAll(round.ready());
            skipped.addAll(round.skipped());
        }
    }

    /** 推进迁移过状态机（BA-11①纪律）+ CAS 落库；CAS 失败 = 并发已收敛——本地视图
     *  必须同步为终态（非 BLOCKED 即不再重评），否则不动点迭代会永远重评同一任务不终止 */
    private void transition(Map<String, DagTaskState> states, String taskId, RcaTaskState to) {
        RcaTaskStateMachine.requireTransition(RcaTaskState.BLOCKED, to);
        tasks.transitionState(UUID.fromString(taskId), RcaTaskState.BLOCKED, to);
        states.put(taskId, DagTaskState.fromPersistent(to));
    }
}
