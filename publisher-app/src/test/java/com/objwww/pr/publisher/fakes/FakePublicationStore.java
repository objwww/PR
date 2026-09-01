package com.objwww.pr.publisher.fakes;

import com.objwww.pr.publisher.domain.model.ClaimedCommand;
import com.objwww.pr.publisher.domain.model.DependencyRow;
import com.objwww.pr.publisher.domain.model.DriftCheckTarget;
import com.objwww.pr.publisher.domain.model.RepairRequestDraft;
import com.objwww.pr.publisher.domain.model.RepairOutcomeTarget;
import com.objwww.pr.publisher.domain.model.SubjectCursor;
import com.objwww.pr.publisher.domain.port.PublicationStore;
import com.objwww.pr.publisher.domain.port.StaleLeaseException;
import com.objwww.pr.publisher.domain.service.T3AContext;
import com.objwww.pr.publisher.domain.service.T3ADecision;
import com.objwww.pr.shared.ExecutionEvent;
import com.objwww.pr.shared.OutboxState;
import com.objwww.pr.shared.OutboxStateMachine;
import com.objwww.pr.shared.PublicationResourceState;
import com.objwww.pr.shared.PublicationResourceType;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;

/**
 * PublicationStore 内存假端口：状态迁移过真实 {@link OutboxStateMachine}（非法迁移直接炸），
 * 游标推进遵守"连续才推进"规则；扫描队列由测试预置。
 */
public class FakePublicationStore implements PublicationStore {

    public final Map<UUID, ClaimedCommand> commands = new LinkedHashMap<>();
    public final Map<UUID, List<DependencyRow>> dependencies = new HashMap<>();
    public final List<ExecutionEvent> events = new ArrayList<>();
    public final Map<UUID, String> confirmedRemoteIds = new HashMap<>();
    public final Map<UUID, PublicationResourceType> resources = new HashMap<>();
    public final Map<UUID, String> errorCodes = new HashMap<>();
    public final Map<UUID, Instant> retryAt = new HashMap<>();
    public final Map<UUID, Instant> reconcileAt = new HashMap<>();
    public final Map<UUID, Duration> checkBackoffs = new HashMap<>();
    public final Map<UUID, RepairRequestDraft> repairRequests = new HashMap<>();
    public final Map<UUID, ClaimedCommand> repairOrigins = new HashMap<>();
    public final Map<UUID, UUID> replacementLinks = new HashMap<>();
    public final Map<UUID, UUID> repairOpResources = new HashMap<>();
    public final List<RepairOutcomeTarget> repairOutcomes = new ArrayList<>();
    public final Map<UUID, String> projectedRepairStates = new HashMap<>();
    public final Map<UUID, com.objwww.pr.shared.Digest> contentDriftDigests = new HashMap<>();

    public final List<ClaimedCommand> claimQueue = new ArrayList<>();
    public final List<ClaimedCommand> expiredInFlight = new ArrayList<>();
    public final List<ClaimedCommand> dueReconciling = new ArrayList<>();
    public final List<ClaimedCommand> staleEpoch = new ArrayList<>();

    public SubjectCursor cursor = new SubjectCursor(1, 0);
    public boolean staleLeaseOnWrite = false;

    public ClaimedCommand stateOf(ClaimedCommand command) {
        return commands.get(command.operationId().value());
    }

    public void put(ClaimedCommand command) {
        commands.put(command.operationId().value(), command);
    }

    private void transition(ClaimedCommand command, OutboxState to, String errorCode) {
        if (staleLeaseOnWrite) {
            throw new StaleLeaseException("fake: 模拟租约失效");
        }
        OutboxStateMachine.transition(command.state(), to); // 假端口也过状态机
        commands.put(command.operationId().value(), TestFixtures.withState(command, to));
        if (errorCode != null) {
            errorCodes.put(command.operationId().value(), errorCode);
        }
    }

    /** 连续才推进（对齐 PG 实现的 I8 纪律） */
    private void advanceCursor(ClaimedCommand command) {
        if (command.aggregateSequence() == cursor.lastResolvedSequence() + 1) {
            cursor = new SubjectCursor(cursor.publicationEpoch(), command.aggregateSequence());
        }
    }

    @Override
    public List<ClaimedCommand> claim(String leaseOwner, Duration leaseDuration, int batchSize) {
        List<ClaimedCommand> batch = new ArrayList<>(claimQueue);
        claimQueue.clear();
        return batch;
    }

    @Override
    public T3ADecision prepare(UUID operationId, long leaseEpoch,
                               Function<T3AContext, T3ADecision> decider) {
        if (staleLeaseOnWrite) {
            throw new StaleLeaseException("fake: 模拟租约失效");
        }
        ClaimedCommand command = commands.get(operationId);
        T3ADecision decision = decider.apply(new T3AContext(command,
                dependencies.getOrDefault(operationId, List.of()), cursor));
        switch (decision.action()) {
            case PROCEED -> transition(command, OutboxState.IN_FLIGHT, null);
            case MARK_SUPERSEDED -> {
                transition(command, OutboxState.SUPERSEDED, decision.errorCode());
                advanceCursor(command);
            }
            case MARK_FAILED_TERMINAL -> {
                transition(command, OutboxState.FAILED_TERMINAL, decision.errorCode());
                advanceCursor(command);
                appendDecisionEvent(command, decision);
            }
            case DEFER -> {
            }
            case RECORD_GAP -> appendDecisionEvent(command, decision);
        }
        return decision;
    }

    private void appendDecisionEvent(ClaimedCommand command, T3ADecision decision) {
        if (decision.eventType() != null) {
            events.add(new ExecutionEvent(UUID.randomUUID(), command.reviewRunId(),
                    command.prRevisionId(), null, null, decision.eventType(), 1, null,
                    command.reviewRunId(), "publisher-app", decision.eventPayload(), Instant.now()));
        }
    }

    @Override
    public void confirm(UUID operationId, long leaseEpoch, String remoteId, String remoteUrl,
                        PublicationResourceType resourceType, String marker, ExecutionEvent event) {
        ClaimedCommand command = commands.get(operationId);
        transition(command, OutboxState.CONFIRMED, null);
        advanceCursor(command);
        confirmedRemoteIds.put(operationId, remoteId);
        resources.put(operationId, resourceType);
        events.add(event);
    }

    @Override
    public void confirmRepairReplacement(UUID operationId, long leaseEpoch, UUID oldResourceId,
                                         String remoteId, String remoteUrl,
                                         PublicationResourceType resourceType, String marker,
                                         ExecutionEvent event) {
        confirm(operationId, leaseEpoch, remoteId, remoteUrl, resourceType, marker, event);
        resourceStates.put(oldResourceId, PublicationResourceState.REPAIRED);
        replacementLinks.put(operationId, oldResourceId);
    }

    @Override
    public void confirmRepairNoop(UUID operationId, long leaseEpoch, UUID oldResourceId,
                                  String remoteId, String remoteUrl, ExecutionEvent event) {
        ClaimedCommand command = commands.get(operationId);
        transition(command, OutboxState.CONFIRMED, null);
        advanceCursor(command);
        confirmedRemoteIds.put(operationId, remoteId);
        resourceStates.put(oldResourceId, PublicationResourceState.PRESENT);
        events.add(event);
    }

    @Override
    public Optional<ClaimedCommand> findRepairOrigin(UUID oldResourceId) {
        return Optional.ofNullable(repairOrigins.get(oldResourceId));
    }

    @Override
    public Optional<UUID> findRepairResourceByOperation(UUID operationId) {
        return Optional.ofNullable(repairOpResources.get(operationId));
    }

    @Override
    public void reconcileConfirmRepairReplacement(UUID operationId, UUID oldResourceId,
                                                   String remoteId, String remoteUrl,
                                                   PublicationResourceType resourceType, String marker,
                                                   ExecutionEvent event) {
        ClaimedCommand command = commands.get(operationId);
        transition(command, OutboxState.CONFIRMED, null);
        advanceCursor(command);
        confirmedRemoteIds.put(operationId, remoteId);
        resourceStates.put(oldResourceId, PublicationResourceState.REPAIRED);
        replacementLinks.put(operationId, oldResourceId);
        events.add(event);
    }

    @Override
    public List<RepairOutcomeTarget> findRepairOutcomes(int limit) {
        return repairOutcomes.stream().limit(limit).toList();
    }

    @Override
    public boolean projectRepairOutcome(UUID requestId, String targetState, String error,
                                        ExecutionEvent event) {
        if (projectedRepairStates.putIfAbsent(requestId, targetState) != null) return false;
        if (event != null) events.add(event);
        return true;
    }

    @Override
    public void markReconciling(UUID operationId, long leaseEpoch, Instant reconcileAfter,
                                ExecutionEvent event) {
        transition(commands.get(operationId), OutboxState.RECONCILING, null);
        events.add(event);
    }

    @Override
    public void markRetryWait(UUID operationId, long leaseEpoch, Instant nextAttemptAt, String errorCode) {
        ClaimedCommand command = commands.get(operationId);
        transition(command, OutboxState.RETRY_WAIT, errorCode);
        commands.put(operationId, TestFixtures.withAttempts(commands.get(operationId),
                command.attemptCount() + 1));
        retryAt.put(operationId, nextAttemptAt);
    }

    @Override
    public void markSuperseded(UUID operationId, long leaseEpoch, String errorCode) {
        ClaimedCommand command = commands.get(operationId);
        transition(command, OutboxState.SUPERSEDED, errorCode);
        advanceCursor(command);
    }

    @Override
    public void markFailedTerminal(UUID operationId, long leaseEpoch, String errorCode,
                                   ExecutionEvent event) {
        ClaimedCommand command = commands.get(operationId);
        transition(command, OutboxState.FAILED_TERMINAL, errorCode);
        advanceCursor(command);
        if (event != null) {
            events.add(event);
        }
    }

    @Override
    public void markManual(UUID operationId, long leaseEpoch, String errorCode) {
        transition(commands.get(operationId), OutboxState.MANUAL, errorCode);
        // 不推进游标
    }

    @Override
    public List<ClaimedCommand> findExpiredInFlight(Instant now, int limit) {
        return new ArrayList<>(expiredInFlight);
    }

    @Override
    public boolean toReconciling(UUID operationId, Instant now, Instant reconcileAfter,
                                 ExecutionEvent event) {
        transition(commands.get(operationId), OutboxState.RECONCILING, null);
        events.add(event);
        return true;
    }

    @Override
    public List<ClaimedCommand> findDueReconciling(Instant now, int limit) {
        return new ArrayList<>(dueReconciling);
    }

    @Override
    public void reconcileConfirm(UUID operationId, String remoteId, String remoteUrl,
                                 PublicationResourceType resourceType, String marker,
                                 ExecutionEvent event) {
        ClaimedCommand command = commands.get(operationId);
        transition(command, OutboxState.CONFIRMED, null);
        advanceCursor(command);
        confirmedRemoteIds.put(operationId, remoteId);
        resources.put(operationId, resourceType);
        events.add(event);
    }

    @Override
    public void reconcileRetryWait(UUID operationId, Instant nextAttemptAt) {
        transition(commands.get(operationId), OutboxState.RETRY_WAIT, null);
    }

    @Override
    public int reconcileUnknown(UUID operationId, Instant nextReconcileAfter) {
        ClaimedCommand command = commands.get(operationId);
        int notFound = command.reconcileNotFoundCount() + 1;
        commands.put(operationId, new ClaimedCommand(command.operationId(), command.prSubjectId(),
                command.reviewRunId(), command.prRevisionId(), command.aggregateKey(),
                command.aggregateSequence(), command.publicationEpoch(), command.fenceMode(),
                command.commandType(), command.state(), command.policyVersion(),
                command.payloadArtifactDigest(), command.payloadHash(), command.remoteIdentityType(),
                command.leaseEpoch(), command.attemptCount(), command.maxAttempts(), notFound));
        reconcileAt.put(operationId, nextReconcileAfter);
        return notFound;
    }

    @Override
    public void reconcileManual(UUID operationId, String errorCode) {
        transition(commands.get(operationId), OutboxState.MANUAL, errorCode);
    }

    @Override
    public List<ClaimedCommand> findStaleEpoch(int limit) {
        return new ArrayList<>(staleEpoch);
    }

    @Override
    public void supersedeStaleEpoch(UUID operationId) {
        ClaimedCommand command = commands.get(operationId);
        transition(command, OutboxState.SUPERSEDED, "STALE_EPOCH");
        advanceCursor(command);
    }

    // ---------- Drift 巡检（M1-T08；内存版保持与 PG 实现相同的守卫语义） ----------

    /** 扫描队列：测试预置到期资源 */
    public final List<DriftCheckTarget> dueDriftChecks = new ArrayList<>();
    /** resourceId → 观测态（默认视为 PRESENT） */
    public final Map<UUID, PublicationResourceState> resourceStates = new HashMap<>();
    /** resourceId → check_error_count */
    public final Map<UUID, Integer> checkErrorCounts = new HashMap<>();
    /** resourceId → 最近一次归位/刷新巡检携带的 interval（断言 next_check_at 重排用） */
    public final Map<UUID, Duration> checkedPresentIntervals = new HashMap<>();

    @Override
    public List<DriftCheckTarget> findDueForDriftCheck(int limit) {
        List<DriftCheckTarget> batch = dueDriftChecks.stream().limit(limit).toList();
        dueDriftChecks.removeAll(batch);
        return batch;
    }

    @Override
    public void markCheckedPresent(UUID resourceId, Duration interval) {
        PublicationResourceState old = resourceStates.getOrDefault(resourceId,
                PublicationResourceState.PRESENT);
        if (old != PublicationResourceState.PRESENT && old != PublicationResourceState.MISSING) {
            return; // 守卫同 PG：state IN ('PRESENT','MISSING')
        }
        resourceStates.put(resourceId, PublicationResourceState.PRESENT);
        checkErrorCounts.put(resourceId, 0);
        checkedPresentIntervals.put(resourceId, interval);
    }

    @Override
    public boolean markContentDrift(UUID resourceId, com.objwww.pr.shared.Digest observedDigest,
                                    Duration interval, ExecutionEvent event) {
        PublicationResourceState old = resourceStates.getOrDefault(resourceId,
                PublicationResourceState.PRESENT);
        if (old != PublicationResourceState.PRESENT && old != PublicationResourceState.MISSING) {
            return false; // 守卫同 PG：state IN ('PRESENT','MISSING')，0 更新即无 episode
        }
        markCheckedPresent(resourceId, interval); // MISSING 复核找回先归位（RM2-04）
        var oldDigest = contentDriftDigests.put(resourceId, observedDigest);
        if (!observedDigest.equals(oldDigest) && event != null) { events.add(event); return true; }
        return false;
    }

    @Override
    public void clearContentDrift(UUID resourceId, Duration interval) {
        PublicationResourceState old = resourceStates.getOrDefault(resourceId,
                PublicationResourceState.PRESENT);
        if (old != PublicationResourceState.PRESENT && old != PublicationResourceState.MISSING) {
            return; // 守卫同 PG：state IN ('PRESENT','MISSING')
        }
        contentDriftDigests.remove(resourceId);
        markCheckedPresent(resourceId, interval);
    }

    @Override
    public boolean markMissing(UUID resourceId, Duration recheckInterval, ExecutionEvent event) {
        PublicationResourceState old = resourceStates.getOrDefault(resourceId,
                PublicationResourceState.PRESENT);
        if (old == PublicationResourceState.RETIRED || old == PublicationResourceState.REPAIRED) {
            return false; // 状态守卫：不参与巡检
        }
        resourceStates.put(resourceId, PublicationResourceState.MISSING);
        checkErrorCounts.put(resourceId, 0);
        if (old != PublicationResourceState.MISSING && event != null) {
            events.add(event); // 恰好一次（ST-22）
            return true;
        }
        return false;
    }

    @Override
    public boolean markMissingWithRepair(UUID resourceId, Duration recheckInterval,
                                         ExecutionEvent driftEvent, RepairRequestDraft repair,
                                         ExecutionEvent repairEvent) {
        boolean newly = markMissing(resourceId, recheckInterval, driftEvent);
        if (newly && repairRequests.putIfAbsent(resourceId, repair) == null && repairEvent != null) {
            events.add(repairEvent);
        }
        return newly;
    }

    @Override
    public void markUnknown(UUID resourceId, ExecutionEvent event) {
        PublicationResourceState old = resourceStates.getOrDefault(resourceId,
                PublicationResourceState.PRESENT);
        if (old == PublicationResourceState.PRESENT) {
            resourceStates.put(resourceId, PublicationResourceState.UNKNOWN);
            if (event != null) {
                events.add(event);
            }
        }
    }

    @Override
    public int markCheckError(UUID resourceId, Duration backoff) {
        PublicationResourceState old = resourceStates.getOrDefault(resourceId,
                PublicationResourceState.PRESENT);
        if (old != PublicationResourceState.PRESENT && old != PublicationResourceState.MISSING) {
            return 0; // 守卫未命中
        }
        int count = checkErrorCounts.getOrDefault(resourceId, 0) + 1;
        checkErrorCounts.put(resourceId, count);
        checkBackoffs.put(resourceId, backoff);
        return count;
    }
}
