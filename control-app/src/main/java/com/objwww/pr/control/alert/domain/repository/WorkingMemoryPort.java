package com.objwww.pr.control.alert.domain.repository;

import com.objwww.pr.control.alert.domain.agent.WorkingMemory;

import java.util.Optional;
import java.util.UUID;

/**
 * R10 工作记忆快照端口（V91 rca_working_memory，append-only 深冻结）：
 * 模型可提更新、宿主校验后条件提交的持久层（R7 方案 §19.2）。
 */
public interface WorkingMemoryPort {

    /**
     * append-only 深冻结提交：同 (run, task, checkpoint_revision) 已有快照 →
     * 返回既有行（候选丢弃，已提交面不漂移——MC06/MC08 同律）；否则落新行。
     */
    WorkingMemory append(WorkingMemory candidate);

    /** 最近快照（checkpoint_revision 最大）；无 → empty（宿主走确定性重建） */
    Optional<WorkingMemory> latestByTask(UUID runId, UUID taskId);

    /**
     * 精确读上一版（CL-06 §5.2）：从 checkpoint.memory_id 指针取累计链父版，
     * 不以 latestByTask 替代（latest 可能属于并发修订或重驱路径）。
     */
    Optional<WorkingMemory> findById(UUID id);
}
