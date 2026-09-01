package com.objwww.pr.control.application;

import com.objwww.pr.control.domain.model.RepairCandidate;
import com.objwww.pr.control.domain.model.RepairRunOutcome;
import com.objwww.pr.control.domain.model.ReviewRun;
import com.objwww.pr.control.domain.model.RunMode;
import com.objwww.pr.control.domain.repository.RepairRequestRepository;
import com.objwww.pr.control.domain.repository.ReviewRunRepository;
import com.objwww.pr.control.domain.service.ExecutionLedger;
import com.objwww.pr.control.domain.service.RepairCommandFactory;
import com.objwww.pr.shared.DependencyMode;
import com.objwww.pr.shared.Digest;
import com.objwww.pr.shared.ExecutionEventType;
import com.objwww.pr.shared.RunState;
import com.objwww.pr.shared.RepairRequestState;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** request 行锁下完成世代 gate → REPAIR Run → outbox → DISPATCHED 的单短事务。 */
public class RepairDispatchService {

    private static final String PRODUCER = "control-app";
    private final RepairRequestRepository requests;
    private final ReviewRunRepository runs;
    private final OutboxWriter outbox;
    private final ExecutionLedger ledger;

    public RepairDispatchService(RepairRequestRepository requests, ReviewRunRepository runs,
                                 OutboxWriter outbox, ExecutionLedger ledger) {
        this.requests = Objects.requireNonNull(requests); this.runs = Objects.requireNonNull(runs);
        this.outbox = Objects.requireNonNull(outbox); this.ledger = Objects.requireNonNull(ledger);
    }

    @Transactional
    public boolean dispatch(UUID requestId, RepairCommandFactory.Prepared prepared) {
        var locked = requests.lockReady(requestId);
        if (locked.isEmpty()) return false;
        RepairCandidate candidate = locked.get();
        if (!candidate.generationCurrent()) {
            requests.markExpired(requestId, "STALE_GENERATION");
            ledger.append(event(candidate, ExecutionEventType.REPAIR_EXPIRED,
                    Map.of("repair_request_id", requestId.toString(), "reason", "STALE_GENERATION")));
            return true;
        }
        Instant now = Instant.now();
        UUID runId = UUID.randomUUID();
        String repairPolicy = candidate.policyVersion() + "/repair/" + requestId;
        // RM2-10：REPAIR Run 的存在意义即铸出并发布 repair 命令，publisherDisabled=false
        // （V4 ck_replay_publisher_disabled 已修订为回放/重建类才禁发布）。
        ReviewRun run = new ReviewRun(runId, candidate.prRevisionId(), candidate.originalRunId(),
                candidate.originalRootRunId(), Digest.sha256Of("repair:" + prepared.operationId()),
                "repair:" + requestId, RunMode.REPAIR, repairPolicy, "repair-v1", "repair-v1",
                null, RunState.CREATED, false, null, null, null, 0, now, now, null);
        runs.save(run);
        outbox.requestPublication(new PublicationRequest(prepared.operationId(), candidate.prSubjectId(),
                runId, candidate.prRevisionId(), candidate.aggregateKey(), prepared.commandType(),
                repairPolicy, prepared.payload(), List.of(new PublicationRequest.DependencyEdge(
                        new com.objwww.pr.shared.OperationId(candidate.originalOperationId()),
                        DependencyMode.REQUIRE_CONFIRMED))));
        if (!requests.markDispatched(requestId, runId, prepared.operationId().value())) {
            throw new IllegalStateException("repair_request 并发状态改变: " + requestId);
        }
        ledger.append(ledger.newEvent(runId, candidate.prRevisionId(), null, null,
                ExecutionEventType.REPAIR_DISPATCHED, null, runId, PRODUCER, Map.of(
                        "repair_request_id", requestId.toString(),
                        "repair_operation_id", prepared.operationId().toString(),
                        "resource_id", candidate.resourceId().toString())));
        return true;
    }

    @Transactional
    public void fail(RepairCandidate candidate, boolean retryable, String error) {
        if (retryable && candidate.attemptCount() + 1 >= candidate.maxAttempts()) {
            // 预算耗尽的 retryable 终态（评审裁定：attempt_count 打满 5/5）——走
            // markRetryWait 的预算翻转分支（FAILED_TERMINAL + 计数 +1 + 退避清空），同事务
            requests.markRetryWait(candidate.requestId(), Duration.ZERO, error);
            ledger.append(event(candidate, ExecutionEventType.REPAIR_FAILED, Map.of(
                    "repair_request_id", candidate.requestId().toString(), "reason", error)));
            return;
        }
        if (retryable) {
            long seconds = Math.min(30L << Math.min(candidate.attemptCount(), 5), 600L);
            requests.markRetryWait(candidate.requestId(), Duration.ofSeconds(seconds), error);
        } else {
            // 非 retryable（坏 payload/CAS 缺失）：不烧重试预算，attempt_count 保留原值（CT-27 已钉）
            requests.markFailedTerminal(candidate.requestId(), error);
            ledger.append(event(candidate, ExecutionEventType.REPAIR_FAILED, Map.of(
                    "repair_request_id", candidate.requestId().toString(), "reason", error)));
        }
    }

    /** publisher 已收口 request 后，由 control 在短事务内收口其零 Step REPAIR Run。 */
    @Transactional
    public boolean projectRunOutcome(UUID requestId) {
        RepairRunOutcome outcome = requests.lockTerminalRunOutcome(requestId).orElse(null);
        if (outcome == null) return false;
        ReviewRun run = runs.findById(outcome.runId())
                .orElseThrow(() -> new IllegalStateException("REPAIR Run 不存在: " + outcome.runId()));
        RunState target = outcome.requestState() == RepairRequestState.REPAIRED
                ? RunState.COMPLETED : RunState.FAILED;
        Instant now = Instant.now();
        run.finishRepair(target, now);
        runs.save(run);
        ledger.append(ledger.newEvent(run.getId(), outcome.revisionId(), null, null,
                ExecutionEventType.RUN_STATE_CHANGED, null, run.getId(), PRODUCER,
                Map.of("run_state", target.name(), "run_mode", RunMode.REPAIR.name(),
                        "repair_request_id", requestId.toString())));
        return true;
    }

    private com.objwww.pr.shared.ExecutionEvent event(RepairCandidate c, ExecutionEventType type,
                                                       Map<String, Object> payload) {
        return ledger.newEvent(c.originalRunId(), c.prRevisionId(), null, null, type,
                null, c.originalRunId(), PRODUCER, payload);
    }
}
