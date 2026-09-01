package com.objwww.pr.control.domain.model;

import com.objwww.pr.shared.CommandType;
import com.objwww.pr.shared.Digest;
import com.objwww.pr.shared.PublicationResourceType;
import com.objwww.pr.shared.RepairPolicyTier;
import com.objwww.pr.shared.RepairRequestState;

import java.util.Objects;
import java.util.UUID;

/** Planner 所需的修复单 + 资源/原命令/当前世代只读投影。 */
public record RepairCandidate(UUID requestId, RepairPolicyTier policyTier,
                              RepairRequestState state, int attemptCount, int maxAttempts,
                              UUID resourceId, PublicationResourceType resourceType,
                              UUID prSubjectId, UUID prRevisionId, UUID currentRevisionId,
                              UUID originalRunId, UUID originalRootRunId,
                              UUID originalOperationId, CommandType commandType,
                              String aggregateKey, String policyVersion,
                              Digest payloadHash, Digest basePayloadHash) {
    public RepairCandidate {
        Objects.requireNonNull(requestId); Objects.requireNonNull(policyTier); Objects.requireNonNull(state);
        Objects.requireNonNull(resourceId); Objects.requireNonNull(resourceType);
        Objects.requireNonNull(prSubjectId); Objects.requireNonNull(prRevisionId);
        Objects.requireNonNull(originalRunId); Objects.requireNonNull(originalOperationId);
        Objects.requireNonNull(commandType); Objects.requireNonNull(aggregateKey);
        Objects.requireNonNull(policyVersion); Objects.requireNonNull(payloadHash);
        Objects.requireNonNull(basePayloadHash);
    }

    public boolean generationCurrent() {
        return prRevisionId.equals(currentRevisionId);
    }
}
