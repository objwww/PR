package com.objwww.pr.publisher.domain.model;

import com.objwww.pr.shared.OutboxState;

import java.util.UUID;

public record RepairOutcomeTarget(UUID requestId, UUID operationId, OutboxState commandState,
                                  UUID repairRunId, UUID prRevisionId, UUID resourceId) {
}
