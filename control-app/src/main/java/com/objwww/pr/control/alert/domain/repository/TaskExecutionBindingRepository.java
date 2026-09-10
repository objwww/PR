package com.objwww.pr.control.alert.domain.repository;

import com.objwww.pr.control.alert.domain.model.TaskExecutionBinding;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 任务→角色冻结绑定端口（R7-X1，V46 rca_task_execution_binding）。
 * 绑定=冻结事实：只增不改（insert 幂等键 uq(run_id, round_id, task_key) 冲突显式失败）；
 * 恢复面只读。实现方每方法自含短事务。
 */
public interface TaskExecutionBindingRepository {

    /** 编译事务内写入（同事务性由调用方事务边界保证；键冲突=重复绑定，显式抛） */
    void insert(TaskExecutionBinding binding);

    /** 恢复/分派读：按任务精确解析角色绑定（缺席 = 旧 Run 兼容面） */
    Optional<TaskExecutionBinding> findByTask(UUID taskId);

    /** run 内全量绑定（round 序稳定；委派子批次恢复读） */
    List<TaskExecutionBinding> findByRun(UUID runId);
}
