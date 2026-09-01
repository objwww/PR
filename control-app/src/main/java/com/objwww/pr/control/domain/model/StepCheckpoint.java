package com.objwww.pr.control.domain.model;

import com.objwww.pr.shared.Digest;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** REVIEW Step 的可覆盖恢复点；租约世代用于拒绝旧 attempt 晚到写。 */
public record StepCheckpoint(UUID id, UUID stepId, String checkpointKey,
                             Digest outputArtifactDigest, Digest modelResponseDigest,
                             Digest checkpointContractDigest,
                             String promptTemplateVersion, String findingSchemaVersion,
                             String mapperContractVersion, String contextBuilderVersion,
                             String modelIdentity, long leaseEpoch,
                             int attemptNo, Instant createdAt) {

    public static final String REVIEW_OUTCOME = "REVIEW_OUTCOME";

    public StepCheckpoint {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(stepId, "stepId");
        Objects.requireNonNull(checkpointKey, "checkpointKey");
        Objects.requireNonNull(outputArtifactDigest, "outputArtifactDigest");
        Objects.requireNonNull(modelResponseDigest, "modelResponseDigest");
        Objects.requireNonNull(checkpointContractDigest, "checkpointContractDigest");
        Objects.requireNonNull(promptTemplateVersion, "promptTemplateVersion");
        Objects.requireNonNull(findingSchemaVersion, "findingSchemaVersion");
        Objects.requireNonNull(mapperContractVersion, "mapperContractVersion");
        Objects.requireNonNull(contextBuilderVersion, "contextBuilderVersion");
        Objects.requireNonNull(modelIdentity, "modelIdentity");
        Objects.requireNonNull(createdAt, "createdAt");
        if (leaseEpoch < 0 || attemptNo < 1) {
            throw new IllegalArgumentException("checkpoint 租约/attempt 非法");
        }
    }
}
