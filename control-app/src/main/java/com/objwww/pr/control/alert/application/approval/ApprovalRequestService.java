package com.objwww.pr.control.alert.application.approval;

import com.objwww.pr.control.alert.application.mutation.ActionIntentStore;
import com.objwww.pr.control.alert.domain.event.RcaEventAppender;
import org.springframework.transaction.support.TransactionOperations;

import java.time.Clock;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * 审批请求面（PC-C1，设计基线 §2.4）：从已解析意图铸 ApprovalRequest——绑定
 * <b>稳定事实</b>（digest + observed_generation 快照 + scope_snapshot_hash +
 * policy_version），零瞬时执行身份（owner/epoch/attempt 不出现——Approval 验
 * Action，LeaseFence 验 Executor，二者不混）。R2 单批、R3 双人（two distinct
 * principals，§2.6）。expires 300s 独立时钟（fail-closed 兜底）。
 */
public class ApprovalRequestService {

    public static final Duration REQUEST_TTL = Duration.ofSeconds(300);

    private final ActionIntentStore intents;
    private final ApprovalStore store;
    private final RcaEventAppender events;
    private final TransactionOperations tx;
    private final String policyVersion;
    private final Clock clock;

    public ApprovalRequestService(ActionIntentStore intents, ApprovalStore store,
            RcaEventAppender events, TransactionOperations tx, String policyVersion,
            Clock clock) {
        this.intents = Objects.requireNonNull(intents);
        this.store = Objects.requireNonNull(store);
        this.events = Objects.requireNonNull(events);
        this.tx = Objects.requireNonNull(tx);
        this.policyVersion = Objects.requireNonNull(policyVersion);
        this.clock = Objects.requireNonNull(clock);
    }

    public UUID request(UUID intentId) {
        return tx.execute(status -> {
            ActionIntentStore.IntentView intent = intents.findById(intentId)
                    .orElseThrow(() -> new IllegalArgumentException("意图不存在: " + intentId));
            if (intent.resolvedResourceUid() == null) {
                throw new IllegalStateException("未解析意图不得进入审批面（fail-closed）");
            }
            int required = requiredApprovers(intent.risk());
            int generation = store.generationOfRun(intent.runId());
            UUID requestId = UUID.randomUUID();
            store.insertRequest(new ApprovalStore.RequestView(requestId, intentId,
                    intent.runId(), intent.toolName(), intent.actionDigest(), generation,
                    required, intent.scopeSnapshotHash(), policyVersion, "PENDING",
                    clock.instant().plus(REQUEST_TTL)));
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("kind", "APPROVAL_REQUESTED");
            payload.put("request_id", requestId.toString());
            payload.put("intent_id", intentId.toString());
            payload.put("action_digest", intent.actionDigest());
            payload.put("observed_generation", generation);
            payload.put("required_approvers", required);
            events.append(intent.runId(), new RcaEventAppender.EventDraft(UUID.randomUUID(),
                    "APPROVAL_REQUESTED",
                    com.objwww.pr.control.alert.application.mutation.OperationPlanner
                            .CanonicalEventJson.canonicalize(payload)));
            return requestId;
        });
    }

    /** 批准人数策略（§2.0 四级裁决）：R3/高危=2（two distinct principals）；R2=1 */
    static int requiredApprovers(String risk) {
        return "R3".equals(risk) ? 2 : 1;
    }
}
