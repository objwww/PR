package com.objwww.pr.control.domain.repository;

import com.objwww.pr.control.domain.model.RepairCandidate;
import com.objwww.pr.control.domain.model.RepairRunOutcome;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface RepairRequestRepository {
    List<RepairCandidate> findReady(int limit);
    Optional<RepairCandidate> lockReady(UUID requestId);
    boolean markDispatched(UUID requestId, UUID runId, UUID operationId);
    boolean markExpired(UUID requestId, String reason);
    boolean markRetryWait(UUID requestId, Duration backoff, String error);
    boolean markFailedTerminal(UUID requestId, String error);
    List<RepairRunOutcome> findTerminalRunOutcomes(int limit);
    Optional<RepairRunOutcome> lockTerminalRunOutcome(UUID requestId);
}
