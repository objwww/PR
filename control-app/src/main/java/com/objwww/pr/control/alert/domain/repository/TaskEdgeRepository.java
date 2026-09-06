package com.objwww.pr.control.alert.domain.repository;

import com.objwww.pr.control.alert.domain.dag.DependencyType;
import com.objwww.pr.control.alert.domain.dag.TaskEdge;

import java.util.List;
import java.util.UUID;

/**
 * 任务 DAG 边仓储（M4-04）：边只在本 run 内连（同 run 归属由 V18 组合外键 DB 强制），
 * 自环/重复边/依赖类型由 V8 约束强制——违规写入抛 DataIntegrityViolationException，
 * 仓储不做旁路校验（单一权威在 DB）。
 *
 * <p>读取返回纯 {@link TaskEdge}（推进器 DagPromoter 的输入形态，
 * 任务 id 以 UUID 字符串呈现）。
 */
public interface TaskEdgeRepository {

    /** 新边落账 */
    void insert(UUID runId, UUID fromTaskId, UUID toTaskId, DependencyType dependencyType);

    /** run 的全部边（from_task_id, to_task_id 升序，可复现） */
    List<TaskEdge> findByRunId(UUID runId);
}
