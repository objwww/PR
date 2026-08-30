package com.objwww.pr.control.domain.model;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * PR 本体投影 + epoch/sequence 唯一账户行（与 V1 pr_subject 对齐）。
 * publication_epoch / next_outbox_sequence / last_resolved_sequence 只能经行锁原子推进，
 * 应用侧不得读改写的字段由 SequenceAllocator（UPDATE ... RETURNING）承载。
 */
public class PRSubject {

    private final UUID id;
    private final long githubInstallationId;
    private final long githubRepositoryId;
    private final String repositoryFullName;
    private final int prNumber;

    private PrSubjectState state;
    private boolean draft;
    private boolean merged;

    private UUID currentRevisionId;
    private String currentPolicyVersion;

    private final long publicationEpoch;
    private final long nextOutboxSequence;
    private final long lastResolvedSequence;

    private long version;
    private final Instant createdAt;
    private Instant updatedAt;

    public PRSubject(UUID id, long githubInstallationId, long githubRepositoryId,
                     String repositoryFullName, int prNumber,
                     PrSubjectState state, boolean draft, boolean merged,
                     UUID currentRevisionId, String currentPolicyVersion,
                     long publicationEpoch, long nextOutboxSequence, long lastResolvedSequence,
                     long version, Instant createdAt, Instant updatedAt) {
        this.id = Objects.requireNonNull(id);
        this.githubInstallationId = githubInstallationId;
        this.githubRepositoryId = githubRepositoryId;
        this.repositoryFullName = Objects.requireNonNull(repositoryFullName);
        this.prNumber = prNumber;
        this.state = Objects.requireNonNull(state);
        this.draft = draft;
        this.merged = merged;
        this.currentRevisionId = currentRevisionId;
        this.currentPolicyVersion = Objects.requireNonNull(currentPolicyVersion);
        this.publicationEpoch = publicationEpoch;
        this.nextOutboxSequence = nextOutboxSequence;
        this.lastResolvedSequence = lastResolvedSequence;
        this.version = version;
        this.createdAt = Objects.requireNonNull(createdAt);
        this.updatedAt = Objects.requireNonNull(updatedAt);
    }

    public UUID getId() { return id; }
    public long getGithubInstallationId() { return githubInstallationId; }
    public long getGithubRepositoryId() { return githubRepositoryId; }
    public String getRepositoryFullName() { return repositoryFullName; }
    public int getPrNumber() { return prNumber; }
    public PrSubjectState getState() { return state; }
    public boolean isDraft() { return draft; }
    public boolean isMerged() { return merged; }
    public UUID getCurrentRevisionId() { return currentRevisionId; }
    public String getCurrentPolicyVersion() { return currentPolicyVersion; }
    public long getPublicationEpoch() { return publicationEpoch; }
    public long getNextOutboxSequence() { return nextOutboxSequence; }
    public long getLastResolvedSequence() { return lastResolvedSequence; }
    public long getVersion() { return version; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }

    /** 换届（T1）：切 revision/policy 与 epoch+1 必须同事务提交（v2.2 §3-2），本方法只改内存视图 */
    public void switchRevision(UUID revisionId, String policyVersion, Instant now) {
        this.currentRevisionId = Objects.requireNonNull(revisionId);
        this.currentPolicyVersion = Objects.requireNonNull(policyVersion);
        this.updatedAt = Objects.requireNonNull(now);
    }

    public void refreshPrState(PrSubjectState state, boolean draft, boolean merged, Instant now) {
        this.state = Objects.requireNonNull(state);
        this.draft = draft;
        this.merged = merged;
        this.updatedAt = Objects.requireNonNull(now);
    }
}
