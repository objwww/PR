package com.objwww.pr.control.support;

import com.objwww.pr.control.domain.model.PRRevision;
import com.objwww.pr.control.domain.model.PRSubject;
import com.objwww.pr.control.domain.model.ReviewFinding;
import com.objwww.pr.control.domain.model.ReviewRun;
import com.objwww.pr.control.domain.model.RunStep;
import com.objwww.pr.control.domain.model.StepAttempt;
import com.objwww.pr.control.domain.model.WorkItem;
import com.objwww.pr.control.domain.port.ArtifactStore;
import com.objwww.pr.control.domain.repository.ArtifactRepository;
import com.objwww.pr.control.domain.repository.OutboxCommandRepository;
import com.objwww.pr.control.domain.repository.PRRevisionRepository;
import com.objwww.pr.control.domain.repository.PRSubjectRepository;
import com.objwww.pr.control.domain.repository.ReviewFindingRepository;
import com.objwww.pr.control.domain.repository.ReviewRunRepository;
import com.objwww.pr.control.domain.repository.RunStepRepository;
import com.objwww.pr.control.domain.repository.StepAttemptRepository;
import com.objwww.pr.control.domain.repository.WorkItemRepository;
import com.objwww.pr.control.domain.model.ArtifactRecord;
import com.objwww.pr.control.domain.service.ExecutionEventRepository;
import com.objwww.pr.control.domain.service.SequenceAllocator;
import com.objwww.pr.control.domain.service.SequenceLease;
import com.objwww.pr.shared.DependencyMode;
import com.objwww.pr.shared.Digest;
import com.objwww.pr.shared.ExecutionEvent;
import com.objwww.pr.shared.OperationId;
import com.objwww.pr.shared.OutboxCommand;
import com.objwww.pr.shared.RevisionFingerprint;
import com.objwww.pr.shared.WorkItemState;
import org.springframework.dao.DuplicateKeyException;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * 事务脚本单测用的内存假实现集（application 层 T1/T2 逻辑测试，不碰真实 PG）。
 * 关键约束在假实现里模拟：run_key / (subject,fingerprint) / (run,fingerprint) 唯一冲突
 * 抛 {@link DuplicateKeyException}，与 PG 唯一约束行为对齐（B-3 幂等捕获路径可测）。
 */
public final class InMemoryStores {

    private InMemoryStores() {
    }

    public static final class Subjects implements PRSubjectRepository {
        private final Map<UUID, PRSubject> byId = new LinkedHashMap<>();

        @Override
        public void save(PRSubject subject) {
            byId.put(subject.getId(), subject);
        }

        @Override
        public void switchRevisionAndBumpEpoch(UUID id, UUID revisionId, String policyVersion, Instant now) {
            PRSubject s = byId.get(id);
            if (s == null) {
                throw new IllegalStateException("pr_subject 不存在: " + id);
            }
            s.switchRevision(revisionId, policyVersion, now);
            // publicationEpoch 是 final——换届语义在内存假实现里用新实例重建（epoch+1）
            PRSubject bumped = new PRSubject(s.getId(), s.getGithubInstallationId(), s.getGithubRepositoryId(),
                    s.getRepositoryFullName(), s.getPrNumber(), s.getState(), s.isDraft(), s.isMerged(),
                    s.getCurrentRevisionId(), s.getCurrentPolicyVersion(),
                    s.getPublicationEpoch() + 1, s.getNextOutboxSequence(), s.getLastResolvedSequence(),
                    s.getVersion() + 1, s.getCreatedAt(), s.getUpdatedAt());
            byId.put(id, bumped);
        }

        @Override
        public Optional<PRSubject> findById(UUID id) {
            return Optional.ofNullable(byId.get(id));
        }

        @Override
        public Optional<PRSubject> findByRepositoryAndPrNumber(long githubRepositoryId, int prNumber) {
            return byId.values().stream()
                    .filter(s -> s.getGithubRepositoryId() == githubRepositoryId && s.getPrNumber() == prNumber)
                    .findFirst();
        }
    }

    public static final class Revisions implements PRRevisionRepository {
        private final Map<UUID, PRRevision> byId = new LinkedHashMap<>();

        @Override
        public void insert(PRRevision revision) {
            findByFingerprint(revision.getPrSubjectId(), revision.getRevisionFingerprint())
                    .ifPresent(existing -> {
                        throw new DuplicateKeyException("uq_pr_revision_fingerprint 冲突");
                    });
            byId.put(revision.getId(), revision);
        }

        @Override
        public Optional<PRRevision> findById(UUID id) {
            return Optional.ofNullable(byId.get(id));
        }

        @Override
        public Optional<PRRevision> findByFingerprint(UUID prSubjectId, RevisionFingerprint fingerprint) {
            return byId.values().stream()
                    .filter(r -> r.getPrSubjectId().equals(prSubjectId)
                            && r.getRevisionFingerprint().equals(fingerprint))
                    .findFirst();
        }
    }

    public static final class Runs implements ReviewRunRepository {
        private final Map<UUID, ReviewRun> byId = new LinkedHashMap<>();
        private final PRRevisionRepository revisions;

        public Runs(PRRevisionRepository revisions) {
            this.revisions = revisions;
        }

        @Override
        public void save(ReviewRun run) {
            ReviewRun existing = byId.get(run.getId());
            if (existing == null) {
                findByRunKey(run.getRunKey()).ifPresent(dup -> {
                    throw new DuplicateKeyException("uq_review_run_key 冲突: " + run.getRunKey());
                });
            }
            byId.put(run.getId(), run);
        }

        @Override
        public Optional<ReviewRun> findById(UUID id) {
            return Optional.ofNullable(byId.get(id));
        }

        @Override
        public Optional<ReviewRun> findByRunKey(Digest runKey) {
            return byId.values().stream().filter(r -> r.getRunKey().equals(runKey)).findFirst();
        }

        @Override
        public List<ReviewRun> findActiveByPrSubjectId(UUID prSubjectId) {
            return byId.values().stream()
                    .filter(r -> revisions.findById(r.getPrRevisionId())
                            .map(rev -> rev.getPrSubjectId().equals(prSubjectId)).orElse(false))
                    .filter(r -> !com.objwww.pr.control.domain.statemachine.RunStateMachine
                            .isTerminal(r.getState()))
                    .toList();
        }
    }

    public static final class Steps implements RunStepRepository {
        private final Map<UUID, RunStep> byId = new LinkedHashMap<>();

        @Override
        public void save(RunStep step) {
            byId.put(step.getId(), step);
        }

        @Override
        public Optional<RunStep> findById(UUID id) {
            return Optional.ofNullable(byId.get(id));
        }

        @Override
        public List<RunStep> findByRunId(UUID reviewRunId) {
            return byId.values().stream().filter(s -> s.getReviewRunId().equals(reviewRunId)).toList();
        }
    }

    public static final class WorkItems implements WorkItemRepository {
        private final Map<UUID, WorkItem> byId = new LinkedHashMap<>();
        private final RunStepRepository steps; // 可为 null：lease 上限退化为 maxLeaseSeconds

        public WorkItems() {
            this(null);
        }

        public WorkItems(RunStepRepository steps) {
            this.steps = steps;
        }

        @Override
        public void save(WorkItem workItem) {
            byId.put(workItem.getId(), workItem);
        }

        @Override
        public Optional<WorkItem> findById(UUID id) {
            return Optional.ofNullable(byId.get(id));
        }

        @Override
        public Optional<WorkItem> findByStepId(UUID stepId) {
            return byId.values().stream().filter(w -> w.getStepId().equals(stepId)).findFirst();
        }

        @Override
        public Optional<WorkItem> claimNext(String owner, Instant now, int maxLeaseSeconds) {
            return byId.values().stream()
                    .filter(w -> (w.getState() == WorkItemState.READY || w.getState() == WorkItemState.RETRY_WAIT)
                            && !w.getAvailableAt().isAfter(now)
                            && w.getAttemptCount() < w.getMaxAttempts())
                    .min(java.util.Comparator.comparingInt(WorkItem::getPriority).reversed()
                            .thenComparing(WorkItem::getAvailableAt)
                            .thenComparing(WorkItem::getCreatedAt))
                    .map(w -> {
                        int timeoutSeconds = steps == null ? maxLeaseSeconds
                                : steps.findById(w.getStepId())
                                        .map(RunStep::getTimeoutSeconds).orElse(maxLeaseSeconds);
                        w.leaseTo(owner, now.plusSeconds(Math.min(timeoutSeconds, maxLeaseSeconds)), now);
                        return w;
                    });
        }

        @Override
        public boolean heartbeat(UUID id, String leaseOwner, long leaseEpoch,
                                 Instant newLeaseUntil, Instant now) {
            WorkItem w = byId.get(id);
            if (w == null || w.getState() != WorkItemState.LEASED
                    || !leaseOwner.equals(w.getLeaseOwner()) || leaseEpoch != w.getLeaseEpoch()) {
                return false; // 已被判死/重领：0 行语义
            }
            w.renewLease(newLeaseUntil, now);
            return true;
        }

        @Override
        public List<WorkItem> findExpiredLeases(Instant now, int limit) {
            return byId.values().stream()
                    .filter(w -> w.getState() == WorkItemState.LEASED
                            && w.getLeaseUntil() != null && w.getLeaseUntil().isBefore(now))
                    .sorted(java.util.Comparator.comparing(WorkItem::getLeaseUntil))
                    .limit(limit)
                    .toList();
        }

        @Override
        public boolean reclaimExpiredLease(UUID id, long leaseEpoch, Instant now, WorkItemState target) {
            WorkItem w = byId.get(id);
            if (w == null || w.getState() != WorkItemState.LEASED || w.getLeaseEpoch() != leaseEpoch
                    || w.getLeaseUntil() == null || !w.getLeaseUntil().isBefore(now)) {
                return false;
            }
            w.reclaim(target, now);
            return true;
        }

        @Override
        public boolean transitionIfLeaseCurrent(UUID id, String leaseOwner, long leaseEpoch,
                                                WorkItemState to, Instant availableAt, Instant now) {
            WorkItem w = byId.get(id);
            if (w == null || !leaseOwner.equals(w.getLeaseOwner()) || leaseEpoch != w.getLeaseEpoch()) {
                return false; // I11：租约已易主，晚到结果 0 行
            }
            if (to == WorkItemState.RETRY_WAIT) {
                w.retryLater(Objects.requireNonNull(availableAt, "RETRY_WAIT 需要退避时间"), now);
            } else {
                w.transitionTo(to, now);
            }
            return true;
        }

        @Override
        public int cancelActiveByRunId(UUID reviewRunId, Instant now) {
            int[] count = {0};
            byId.values().forEach(w -> {
                if (w.getReviewRunId().equals(reviewRunId)
                        && (w.getState() == WorkItemState.READY
                        || w.getState() == WorkItemState.LEASED
                        || w.getState() == WorkItemState.RETRY_WAIT)) {
                    w.transitionTo(WorkItemState.CANCELLED, now);
                    count[0]++;
                }
            });
            return count[0];
        }
    }

    public static final class Attempts implements StepAttemptRepository {
        private final Map<UUID, StepAttempt> byId = new LinkedHashMap<>();

        @Override
        public void save(StepAttempt attempt) {
            byId.put(attempt.getId(), attempt);
        }

        @Override
        public Optional<StepAttempt> findById(UUID id) {
            return Optional.ofNullable(byId.get(id));
        }

        @Override
        public List<StepAttempt> findByStepId(UUID stepId) {
            return byId.values().stream().filter(a -> a.getStepId().equals(stepId)).toList();
        }
    }

    public static final class Findings implements ReviewFindingRepository {
        private final List<ReviewFinding> all = new ArrayList<>();

        @Override
        public void insert(ReviewFinding finding) {
            boolean dup = all.stream().anyMatch(f -> f.getReviewRunId().equals(finding.getReviewRunId())
                    && f.getFingerprint().equals(finding.getFingerprint()));
            if (!dup) {
                all.add(finding); // ON CONFLICT DO NOTHING 语义
            }
        }

        @Override
        public List<ReviewFinding> findByRunId(UUID reviewRunId) {
            return all.stream().filter(f -> f.getReviewRunId().equals(reviewRunId)).toList();
        }
    }

    public static final class OutboxCommands implements OutboxCommandRepository {
        public record DependencyRow(OperationId operationId, OperationId dependsOn,
                                    DependencyMode mode, Instant createdAt) {
        }

        private final Map<UUID, OutboxCommand> byId = new LinkedHashMap<>();
        private final List<DependencyRow> dependencies = new ArrayList<>();

        @Override
        public void insert(OutboxCommand command) {
            if (byId.putIfAbsent(command.operationId().value(), command) != null) {
                throw new DuplicateKeyException("outbox_command.operation_id 主键冲突");
            }
        }

        @Override
        public void insertDependency(OperationId operationId, OperationId dependsOnOperationId,
                                     DependencyMode mode, Instant createdAt) {
            dependencies.add(new DependencyRow(operationId, dependsOnOperationId, mode, createdAt));
        }

        public List<OutboxCommand> all() {
            return List.copyOf(byId.values());
        }

        public List<DependencyRow> dependencies() {
            return List.copyOf(dependencies);
        }
    }

    /** sequence 按 subject 递增、epoch 从 Subjects 读当前值（与 UPDATE...RETURNING 语义对齐） */
    public static final class Sequences implements SequenceAllocator {
        private final Subjects subjects;
        private final Map<UUID, Long> nextBySubject = new HashMap<>();

        public Sequences(Subjects subjects) {
            this.subjects = subjects;
        }

        @Override
        public SequenceLease allocate(UUID prSubjectId) {
            PRSubject subject = subjects.findById(prSubjectId)
                    .orElseThrow(() -> new IllegalStateException("pr_subject 不存在: " + prSubjectId));
            long seq = nextBySubject.merge(prSubjectId, 1L, (a, b) -> a + 1);
            return new SequenceLease(seq, subject.getPublicationEpoch());
        }
    }

    public static final class Events implements ExecutionEventRepository {
        private final List<ExecutionEvent> all = new ArrayList<>();

        @Override
        public void append(ExecutionEvent event) {
            all.add(event);
        }

        @Override
        public List<ExecutionEvent> findByRunIdOrdered(UUID reviewRunId) {
            return all.stream().filter(e -> e.reviewRunId().equals(reviewRunId)).toList();
        }

        public List<ExecutionEvent> all() {
            return List.copyOf(all);
        }
    }

    public static final class Artifacts implements ArtifactRepository {
        private final List<ArtifactRecord> all = new ArrayList<>();

        @Override
        public void register(ArtifactRecord record) {
            if (all.stream().noneMatch(a -> a.digest().equals(record.digest()))) {
                all.add(record); // ON CONFLICT DO NOTHING 语义
            }
        }

        public List<ArtifactRecord> all() {
            return List.copyOf(all);
        }
    }

    public static final class Cas implements ArtifactStore {
        private final Map<Digest, byte[]> blobs = new HashMap<>();

        @Override
        public String putIfAbsent(Digest digest, byte[] content) {
            blobs.putIfAbsent(digest, content.clone());
            return "mem/" + digest.value();
        }

        @Override
        public boolean exists(Digest digest) {
            return blobs.containsKey(digest);
        }

        @Override
        public Optional<byte[]> get(Digest digest) {
            return Optional.ofNullable(blobs.get(digest));
        }
    }
}
