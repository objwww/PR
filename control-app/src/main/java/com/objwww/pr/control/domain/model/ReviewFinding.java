package com.objwww.pr.control.domain.model;

import com.objwww.pr.shared.Digest;
import com.objwww.pr.shared.FindingState;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * 评审发现（与 V1 review_finding 对齐）。
 * fingerprint = head_sha/file/range/rule/message_hash（v2.2 §5），M5 行级评论幂等的挂载点；
 * M0 批量 Review 时 finding 与 PUBLISH_REVIEW 命令 N:1，只登记不逐条驱动发布。
 */
public final class ReviewFinding {

    private final UUID id;
    private final UUID reviewRunId;
    private final UUID prRevisionId;

    private final Digest fingerprint;
    private final String ruleId;
    private final String severity;
    private final String filePath;
    private final Integer lineStart;
    private final Integer lineEnd;
    private final Digest bodyArtifactDigest;

    private final FindingState state;
    private final Instant createdAt;

    public ReviewFinding(UUID id, UUID reviewRunId, UUID prRevisionId,
                         Digest fingerprint, String ruleId, String severity,
                         String filePath, Integer lineStart, Integer lineEnd,
                         Digest bodyArtifactDigest, FindingState state, Instant createdAt) {
        this.id = Objects.requireNonNull(id);
        this.reviewRunId = Objects.requireNonNull(reviewRunId);
        this.prRevisionId = Objects.requireNonNull(prRevisionId);
        this.fingerprint = Objects.requireNonNull(fingerprint);
        this.ruleId = Objects.requireNonNull(ruleId);
        this.severity = Objects.requireNonNull(severity);
        this.filePath = Objects.requireNonNull(filePath);
        this.lineStart = lineStart;
        this.lineEnd = lineEnd;
        this.bodyArtifactDigest = bodyArtifactDigest;
        this.state = Objects.requireNonNull(state);
        this.createdAt = Objects.requireNonNull(createdAt);
    }

    public UUID getId() { return id; }
    public UUID getReviewRunId() { return reviewRunId; }
    public UUID getPrRevisionId() { return prRevisionId; }
    public Digest getFingerprint() { return fingerprint; }
    public String getRuleId() { return ruleId; }
    public String getSeverity() { return severity; }
    public String getFilePath() { return filePath; }
    public Integer getLineStart() { return lineStart; }
    public Integer getLineEnd() { return lineEnd; }
    public Digest getBodyArtifactDigest() { return bodyArtifactDigest; }
    public FindingState getState() { return state; }
    public Instant getCreatedAt() { return createdAt; }
}
