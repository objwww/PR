package com.objwww.pr.control.alert.application.approval;

import com.objwww.pr.control.alert.domain.event.RcaEventAppender;
import org.springframework.transaction.support.TransactionOperations;

import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * 审批挂起面（PC-C3，§2.10 durable suspension）：审批发起 → 挂起（释放 slot 的
 * Worker 检查点接线随 Phase D；本面先落台账 + 驱动对账豁免）→ 决策/过期 → 恢复。
 * 双时钟语义：human_wait = wall clock 差（永不冻结的诚实时钟，单列落账）；
 * system_active 冻结由"挂起即无 Worker 在途"承载。
 */
public class ApprovalSuspensionService {

    private final SuspensionStore store;
    private final RcaEventAppender events;
    private final TransactionOperations tx;
    private final Clock clock;

    public ApprovalSuspensionService(SuspensionStore store, RcaEventAppender events,
            TransactionOperations tx, Clock clock) {
        this.store = Objects.requireNonNull(store);
        this.events = Objects.requireNonNull(events);
        this.tx = Objects.requireNonNull(tx);
        this.clock = Objects.requireNonNull(clock);
    }

    /** 挂起（审批等待开始；同 run 重复挂起被部分唯一索引拒绝） */
    public UUID suspend(UUID runId, UUID requestId) {
        return tx.execute(status -> {
            UUID id = store.insertSuspension(runId, requestId, clock.instant());
            emit(runId, "APPROVAL_SUSPENDED", Map.of(
                    "request_id", requestId.toString(),
                    "note", "wall clock 继续；system_active 冻结；对账豁免终止"));
            return id;
        });
    }

    /** 恢复（决策/过期回调；落 human_wait 秒） */
    public boolean resume(UUID runId) {
        return Boolean.TRUE.equals(tx.execute(status -> {
            boolean resumed = store.resumeSuspension(runId, clock.instant());
            if (resumed) {
                emit(runId, "APPROVAL_RESUMED", Map.of());
            }
            return resumed;
        }));
    }

    public boolean hasActiveSuspension(UUID runId) {
        return store.hasActiveSuspension(runId);
    }

    public SuspensionStore.SuspensionStats stats() {
        return store.stats();
    }

    private void emit(UUID runId, String type, Map<String, String> extra) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("kind", type);
        payload.put("run_id", runId.toString());
        payload.putAll(extra);
        events.append(runId, new RcaEventAppender.EventDraft(UUID.randomUUID(), type,
                com.objwww.pr.control.alert.application.mutation.OperationPlanner
                        .CanonicalEventJson.canonicalize(payload)));
    }
}
