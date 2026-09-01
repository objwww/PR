package com.objwww.pr.publisher.domain.model;

import com.objwww.pr.shared.PublicationResourceType;
import com.objwww.pr.shared.RepairPolicyTier;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record RepairRequestDraft(UUID id, UUID publicationResourceId,
                                 PublicationResourceType resourceType,
                                 RepairPolicyTier policyTier, int maxAttempts,
                                 Instant createdAt) {
    public RepairRequestDraft {
        Objects.requireNonNull(id);
        Objects.requireNonNull(publicationResourceId);
        Objects.requireNonNull(resourceType);
        Objects.requireNonNull(policyTier);
        Objects.requireNonNull(createdAt);
        if (maxAttempts < 1) throw new IllegalArgumentException("maxAttempts 必须 > 0");
    }
}
