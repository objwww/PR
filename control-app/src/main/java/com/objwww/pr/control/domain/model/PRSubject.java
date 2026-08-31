package com.objwww.pr.control.domain.model;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * PR 本体投影 + epoch/sequence 唯一账户行（与 V1 pr_subject 对齐；V3 增 last_event_updated_at）。
 * publication_epoch / next_outbox_sequence / last_resolved_sequence 只能经行锁原子推进，
 * 应用侧不得读改写的字段由 SequenceAllocator（UPDATE ... RETURNING）承载。
 *
 * <p>lastEventUpdatedAt（M1-T05，LWW 水印）只能经 repository 的条件 UPDATE
 * （GREATEST(旧值, 新值)）推进，防并发回退（I10/CT-14）；refreshPrState/switchRevision
 * 等内存方法刻意不碰它——水印的权威是 DB 里的 max 语义，不是应用侧读改写。
 *
 * <p>nextPrReconcileAt / prReconcileErrorCount（M1-T07，方案 §4.5 公平扫描 + 退避计数，
 * V3 增列）与水印同理：不经 save 读写（INSERT 走 DB 默认值、ON CONFLICT 不动它们），
 * 只能经 repository 的 markReconciled / markReconcileError 单句 UPDATE 推进，
 * 下一跳时刻 = DB now() + 时长（I17：过期比较一律 DB 时钟）。
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

    /** LWW 水印（V3 增列，可空）：最近一次已受理事件的远端 updated_at；只增不回退 */
    private final Instant lastEventUpdatedAt;

    /** PR State Reconciler 下一跳对账时刻（V3 增列）：扫描 WHERE/ORDER BY 的唯一依据（公平，E2E-14） */
    private final Instant nextPrReconcileAt;
    /** 连续对账失败计数（V3 增列）：成功清零；>= 阈值（默认 3）触发 ReconcilerDegraded（措辞修正 #3） */
    private final int prReconcileErrorCount;

    private long version;
    private final Instant createdAt;
    private Instant updatedAt;

    public PRSubject(UUID id, long githubInstallationId, long githubRepositoryId,
                     String repositoryFullName, int prNumber,
                     PrSubjectState state, boolean draft, boolean merged,
                     UUID currentRevisionId, String currentPolicyVersion,
                     long publicationEpoch, long nextOutboxSequence, long lastResolvedSequence,
                     Instant lastEventUpdatedAt,
                     Instant nextPrReconcileAt, int prReconcileErrorCount,
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
        this.lastEventUpdatedAt = lastEventUpdatedAt; // 可空（EX-18：远端缺 updated_at 时不造）
        this.nextPrReconcileAt = Objects.requireNonNull(nextPrReconcileAt);
        this.prReconcileErrorCount = prReconcileErrorCount;
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
    public Instant getLastEventUpdatedAt() { return lastEventUpdatedAt; }
    public Instant getNextPrReconcileAt() { return nextPrReconcileAt; }
    public int getPrReconcileErrorCount() { return prReconcileErrorCount; }
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
