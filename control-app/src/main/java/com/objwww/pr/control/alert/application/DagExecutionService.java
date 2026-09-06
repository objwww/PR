package com.objwww.pr.control.alert.application;

import com.objwww.pr.control.alert.domain.dag.DagCycleDetector;
import com.objwww.pr.control.alert.domain.dag.DependencyType;
import com.objwww.pr.control.alert.domain.dag.TaskEdge;
import com.objwww.pr.control.alert.domain.repository.TaskEdgeRepository;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * DAG 执行服务（AM4 M4-05/06 接线层）：备料纯函数（DagCycleDetector/DagPromoter）
 * 与持久化端口之间的应用层组合，模型无调度权（INV-AM4-2）——所有图变更都过这里的
 * 确定性裁决。生产调用方 = M4-25 Planner 提案落库 / M4-26 Supervisor 终态回执。
 */
@Service
public class DagExecutionService {

    private final TaskEdgeRepository edges;

    public DagExecutionService(TaskEdgeRepository edges) {
        this.edges = Objects.requireNonNull(edges);
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
}
