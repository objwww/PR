package com.objwww.pr.publisher.fakes;

import com.objwww.pr.publisher.domain.model.ClaimedCommand;
import com.objwww.pr.publisher.domain.model.DependencyRow;
import com.objwww.pr.publisher.domain.model.SubjectCursor;
import com.objwww.pr.publisher.domain.port.PublicationStore;
import com.objwww.pr.publisher.domain.port.StaleLeaseException;
import com.objwww.pr.publisher.domain.service.T3AContext;
import com.objwww.pr.publisher.domain.service.T3ADecision;
import com.objwww.pr.shared.ExecutionEvent;
import com.objwww.pr.shared.OutboxState;
import com.objwww.pr.shared.OutboxStateMachine;
import com.objwww.pr.shared.PublicationResourceType;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
    public boolean toReconciling(UUID operationId, Instant now, Instant reconcileAfter) {
        transition(commands.get(operationId), OutboxState.RECONCILING, null);
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
}
