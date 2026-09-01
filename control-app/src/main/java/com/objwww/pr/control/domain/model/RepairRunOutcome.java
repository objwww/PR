package com.objwww.pr.control.domain.model;

import com.objwww.pr.shared.RepairRequestState;

import java.util.Objects;
import java.util.UUID;

/** 已终态修复单对应的零 Step REPAIR Run 收口投影。 */
public record RepairRunOutcome(UUID requestId, UUID runId, UUID revisionId,
                               RepairRequestState requestState) {
    public RepairRunOutcome {
        Objects.requireNonNull(requestId);
        Objects.requireNonNull(runId);
        Objects.requireNonNull(revisionId);
        Objects.requireNonNull(requestState);
    }
}
