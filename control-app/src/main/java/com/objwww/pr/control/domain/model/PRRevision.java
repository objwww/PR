package com.objwww.pr.control.domain.model;

import com.objwww.pr.shared.Digest;
import com.objwww.pr.shared.RevisionFingerprint;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * 不可变代码身份（与 V1 pr_revision 对齐）。创建后不可改（DB trigger 兜底，I9/I12）。
 * 评审修正 #3：构造时 diffDigest 与 revisionFingerprint 必须已就绪——
 * 不存在"先插行后补 digest"的路径。
 */
public final class PRRevision {

    private final UUID id;
    private final UUID prSubjectId;
    private final String headSha;
    private final String baseRef;
    private final String baseSha;
    private final String mergeBaseSha;
    private final Digest diffDigest;
    private final Digest sourceSnapshotDigest;
    private final RevisionFingerprint revisionFingerprint;
    private final Instant observedAt;
    private final Instant createdAt;

    public PRRevision(UUID id, UUID prSubjectId,
                      String headSha, String baseRef, String baseSha, String mergeBaseSha,
                      Digest diffDigest, Digest sourceSnapshotDigest,
                      RevisionFingerprint revisionFingerprint,
                      Instant observedAt, Instant createdAt) {
        this.id = Objects.requireNonNull(id);
        this.prSubjectId = Objects.requireNonNull(prSubjectId);
        this.headSha = Objects.requireNonNull(headSha);
        this.baseRef = Objects.requireNonNull(baseRef);
        this.baseSha = Objects.requireNonNull(baseSha);
        this.mergeBaseSha = mergeBaseSha;
        // 评审修正 #3：digest 未就绪禁止构造
        this.diffDigest = Objects.requireNonNull(diffDigest, "diffDigest 必须在构造前已算好");
        this.sourceSnapshotDigest = sourceSnapshotDigest;
        this.revisionFingerprint = Objects.requireNonNull(revisionFingerprint, "revisionFingerprint 必须在构造前已算好");
        this.observedAt = Objects.requireNonNull(observedAt);
        this.createdAt = Objects.requireNonNull(createdAt);
    }

    public UUID getId() { return id; }
    public UUID getPrSubjectId() { return prSubjectId; }
    public String getHeadSha() { return headSha; }
    public String getBaseRef() { return baseRef; }
    public String getBaseSha() { return baseSha; }
    public String getMergeBaseSha() { return mergeBaseSha; }
    public Digest getDiffDigest() { return diffDigest; }
    public Digest getSourceSnapshotDigest() { return sourceSnapshotDigest; }
    public RevisionFingerprint getRevisionFingerprint() { return revisionFingerprint; }
    public Instant getObservedAt() { return observedAt; }
    public Instant getCreatedAt() { return createdAt; }
}
