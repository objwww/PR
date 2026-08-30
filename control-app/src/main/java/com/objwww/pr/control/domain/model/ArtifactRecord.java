package com.objwww.pr.control.domain.model;

import com.objwww.pr.shared.Digest;

import java.time.Instant;
import java.util.Objects;

/**
 * artifact 登记表行（v2.2 §5）：大对象在 CAS，本表只存 digest 与位置。
 */
public record ArtifactRecord(Digest digest, ArtifactType artifactType,
                             long sizeBytes, String storagePath, Instant createdAt) {

    public ArtifactRecord {
        Objects.requireNonNull(digest, "digest");
        Objects.requireNonNull(artifactType, "artifactType");
        Objects.requireNonNull(storagePath, "storagePath");
        Objects.requireNonNull(createdAt, "createdAt");
        if (sizeBytes < 0) {
            throw new IllegalArgumentException("sizeBytes 不能为负: " + sizeBytes);
        }
    }
}
