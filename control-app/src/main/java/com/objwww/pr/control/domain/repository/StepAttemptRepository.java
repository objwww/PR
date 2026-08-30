package com.objwww.pr.control.domain.repository;

import com.objwww.pr.control.domain.model.StepAttempt;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface StepAttemptRepository {

    /**
     * 新建插入（worker 领租约时记 STARTED）或按 id 更新终态（T2）。
     * (step_id, attempt_no) 唯一约束是重试计数兜底层，不靠读改写。
     */
    void save(StepAttempt attempt);

    Optional<StepAttempt> findById(UUID id);

    List<StepAttempt> findByStepId(UUID stepId);
}
