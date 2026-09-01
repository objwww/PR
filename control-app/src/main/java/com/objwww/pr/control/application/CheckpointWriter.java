package com.objwww.pr.control.application;

import com.objwww.pr.control.domain.model.ArtifactRecord;
import com.objwww.pr.control.domain.model.StepCheckpoint;
import com.objwww.pr.control.domain.repository.ArtifactRepository;
import com.objwww.pr.control.domain.repository.StepCheckpointRepository;
import com.objwww.pr.control.domain.service.ExecutionLedger;
import com.objwww.pr.shared.ExecutionEvent;
import org.springframework.transaction.annotation.Transactional;

import java.util.Objects;
import java.util.UUID;

/** 双 artifact 登记、租约条件 checkpoint upsert、stored 事件的唯一短事务。 */
public class CheckpointWriter {

    private final ArtifactRepository artifacts;
    private final StepCheckpointRepository checkpoints;
    private final ExecutionLedger ledger;

    public CheckpointWriter(ArtifactRepository artifacts, StepCheckpointRepository checkpoints,
                            ExecutionLedger ledger) {
        this.artifacts = Objects.requireNonNull(artifacts);
        this.checkpoints = Objects.requireNonNull(checkpoints);
        this.ledger = Objects.requireNonNull(ledger);
    }

    @Transactional
    public boolean store(ArtifactRecord output, ArtifactRecord model, StepCheckpoint checkpoint,
                         UUID workItemId, String leaseOwner, ExecutionEvent storedEvent) {
        artifacts.register(output);
        artifacts.register(model);
        boolean stored = checkpoints.upsertIfLeaseCurrent(checkpoint, workItemId, leaseOwner);
        if (stored) {
            ledger.append(storedEvent);
        }
        return stored;
    }
}
