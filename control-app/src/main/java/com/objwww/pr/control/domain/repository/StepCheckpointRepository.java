package com.objwww.pr.control.domain.repository;

import com.objwww.pr.control.domain.model.StepCheckpoint;

import java.util.Optional;
import java.util.UUID;

public interface StepCheckpointRepository {

    Optional<StepCheckpoint> find(UUID stepId, String checkpointKey);

    /** 与 work_item 当前 owner/epoch/lease_until 在存储端原子比较；晚到写返回 false。 */
    boolean upsertIfLeaseCurrent(StepCheckpoint checkpoint, UUID workItemId, String leaseOwner);
}
