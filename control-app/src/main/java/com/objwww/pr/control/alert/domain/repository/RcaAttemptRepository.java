package com.objwww.pr.control.alert.domain.repository;

import com.objwww.pr.control.alert.domain.model.RcaAttempt;

import java.util.List;
import java.util.UUID;

/**
 * rca_attempt 端口（V1 step_attempt 同构：uq(task_id, attempt_no)）。
 */
public interface RcaAttemptRepository {

    /** STARTED 行（领取后、触网前） */
    void insert(RcaAttempt attempt);

    /** 终态回写（调用方已过 epoch 栅栏） */
    boolean update(RcaAttempt attempt);

    List<RcaAttempt> findByTaskId(UUID taskId);
}
