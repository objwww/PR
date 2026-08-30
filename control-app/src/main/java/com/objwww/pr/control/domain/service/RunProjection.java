package com.objwww.pr.control.domain.service;

import com.objwww.pr.shared.RunState;
import com.objwww.pr.shared.StepState;

import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 内存投影（M0-T04）。V1 已冻结、不新建投影表：可变实体行即持久投影，
 * 本 record 只作为 fold 一致性断言与只读查询的载体。
 */
public record RunProjection(
        RunState runState,
        Map<UUID, StepState> stepStates,
        Set<UUID> publishedOperationIds) {

    public RunProjection {
        stepStates = Map.copyOf(stepStates);
        publishedOperationIds = Set.copyOf(publishedOperationIds);
    }
}
