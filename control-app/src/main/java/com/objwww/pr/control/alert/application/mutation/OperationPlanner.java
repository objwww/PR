package com.objwww.pr.control.alert.application.mutation;

import com.objwww.pr.control.alert.domain.event.RcaEventAppender;
import com.objwww.pr.control.alert.domain.mutation.RcaOperation;
import org.springframework.transaction.support.TransactionOperations;

import java.time.Clock;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * 消费模板——Plan 面（PB-B4，设计基线 §2.8 R2 五步同事务的 Phase B 形态）：
 *
 * <pre>
 * BEGIN
 *   1. 验 ApprovalGrant            —— Phase C 插入（B4 以 dry-run 哨兵占位）
 *   2. OperationAuthorization CAS   —— Phase C 插入
 *   3. INSERT rca_operation(PREPARED)
 *   4. 领资源锁（BUSY = 整体回滚）+ INSERT operation_outbox
 *   5. intent OPEN→PLANNED（CAS）+ rca_event(OPERATION_PREPARED)
 * COMMIT
 * </pre>
 *
 * <p>原子性：任一步失利抛 {@link Rollback}，已写行（operation/锁）随事务回滚——
 * 「半消费」不可能。幂等：outbox 与 operation 1:1，intent PLANNED 单向 CAS。
 * COMIT 后崩溃由 outbox 存续兜底（Dispatcher 可继续）——consume→dispatch 崩溃窗
 * 在结构上不存在。
 */
public class OperationPlanner {

    /** 计划结局：planned（带 operation 锚）或 rejected（显式原因，零写入） */
    public record Outcome(Status status, UUID operationId, long resourceEpoch,
            String rejectReason) {

        public enum Status { PLANNED, REJECTED }
    }

    /** 事务回滚信号（携带显式拒绝原因；TransactionTemplate 回滚后在此还原） */
    private static final class Rollback extends RuntimeException {
        private final Outcome outcome;

        Rollback(Outcome outcome) {
            super(outcome.rejectReason());
            this.outcome = outcome;
        }
    }

    private final ActionIntentStore intents;
    private final OperationLedgerStore operations;
    private final OperationOutboxStore outbox;
    private final ResourceLockStore locks;
    private final com.objwww.pr.control.alert.application.approval.ApprovalPlannerGate
            approvalGate; // 可空=哨兵模式（Phase C 真锚前装配不变）
    private final RcaEventAppender events;
    private final TransactionOperations tx;
    private final Duration lockTtl;
    private final boolean dryRunPlanEnabled;
    private final boolean approvalEnabled;
    private final String policyVersion;
    private final UnlockScopeStore unlockScopes; // 可空=解锁面关闭（永远 dry-run）
    private final Set<String> hardlineTools; // Phase E：绝对禁止清单，先于一切
    private final Clock clock;

    public OperationPlanner(ActionIntentStore intents, OperationLedgerStore operations,
            OperationOutboxStore outbox, ResourceLockStore locks, RcaEventAppender events,
            TransactionOperations tx, Duration lockTtl, boolean dryRunPlanEnabled,
            Clock clock) {
        this(intents, operations, outbox, locks, null, events, tx, lockTtl,
                dryRunPlanEnabled, false, null, clock);
    }

    /** PC-C2 全参形态：approvalEnabled=true 时步骤 1/2 走真锚（grant→配额→single-use） */
    public OperationPlanner(ActionIntentStore intents, OperationLedgerStore operations,
            OperationOutboxStore outbox, ResourceLockStore locks,
            com.objwww.pr.control.alert.application.approval.ApprovalPlannerGate approvalGate,
            RcaEventAppender events, TransactionOperations tx, Duration lockTtl,
            boolean dryRunPlanEnabled, boolean approvalEnabled, String policyVersion,
            Clock clock) {
        this(intents, operations, outbox, locks, approvalGate, events, tx, lockTtl,
                dryRunPlanEnabled, approvalEnabled, policyVersion, null, clock);
    }

    /** PD-D1 全参形态：unlockScopes 非 null 时注册表三元匹配 → dry_run=false 真执行铸造 */
    public OperationPlanner(ActionIntentStore intents, OperationLedgerStore operations,
            OperationOutboxStore outbox, ResourceLockStore locks,
            com.objwww.pr.control.alert.application.approval.ApprovalPlannerGate approvalGate,
            RcaEventAppender events, TransactionOperations tx, Duration lockTtl,
            boolean dryRunPlanEnabled, boolean approvalEnabled, String policyVersion,
            UnlockScopeStore unlockScopes, Clock clock) {
        this(intents, operations, outbox, locks, approvalGate, events, tx, lockTtl,
                dryRunPlanEnabled, approvalEnabled, policyVersion, unlockScopes,
                java.util.Set.of(), clock);
    }

    /** PE-E1 全参形态：hardlineTools 绝对禁止清单——先于一切（注册表也解不了锁） */
    public OperationPlanner(ActionIntentStore intents, OperationLedgerStore operations,
            OperationOutboxStore outbox, ResourceLockStore locks,
            com.objwww.pr.control.alert.application.approval.ApprovalPlannerGate approvalGate,
            RcaEventAppender events, TransactionOperations tx, Duration lockTtl,
            boolean dryRunPlanEnabled, boolean approvalEnabled, String policyVersion,
            UnlockScopeStore unlockScopes, Set<String> hardlineTools, Clock clock) {
        this.intents = Objects.requireNonNull(intents);
        this.operations = Objects.requireNonNull(operations);
        this.outbox = Objects.requireNonNull(outbox);
        this.locks = Objects.requireNonNull(locks);
        this.approvalGate = approvalGate;
        this.events = Objects.requireNonNull(events);
        this.tx = Objects.requireNonNull(tx);
        if (lockTtl.isNegative() || lockTtl.isZero()) {
            throw new IllegalArgumentException("锁 TTL 必须为正");
        }
        this.lockTtl = lockTtl;
        this.dryRunPlanEnabled = dryRunPlanEnabled;
        this.approvalEnabled = approvalEnabled;
        this.policyVersion = policyVersion;
        this.unlockScopes = unlockScopes;
        this.hardlineTools = Set.copyOf(hardlineTools);
        this.clock = Objects.requireNonNull(clock);
    }

    public Outcome plan(UUID intentId) {
        if (!dryRunPlanEnabled) {
            return rejected("PLAN_DISABLED");
        }
        try {
            return tx.execute(status -> doPlan(intentId));
        } catch (Rollback rollback) {
            return rollback.outcome;
        }
    }

    private Outcome doPlan(UUID intentId) {
        ActionIntentStore.IntentView intent = intents.findById(intentId)
                .orElseThrow(() -> new Rollback(rejected("INTENT_NOT_FOUND")));
        // Hardline 先于一切（§2.0）：命中绝对禁止清单 = 阻断 + 拒绝理由留痕，
        // 解锁注册表/Guardian 都解不了锁
        if (hardlineTools.contains(intent.toolName())) {
            throw new Rollback(rejected("HARDLINE"));
        }
        if (intent.resolvedResourceUid() == null || intent.scopeSnapshotHash() == null) {
            throw new Rollback(rejected("NOT_RESOLVED")); // fail-closed：无快照锚不计划
        }
        // 步骤 1/2（PC-C2 真锚模式）：查活 grant（四元匹配=换代/撤销/过期/policy 漂移
        // 结构性作废）→ 配额 CAS → 签发 single-use 授权；任一失利整体回滚
        java.util.UUID grantId = null;
        java.util.UUID authzId = null;
        if (approvalEnabled) {
            var grant = approvalGate.findActiveGrant(intent.runId(), intent.actionDigest(),
                    intent.scopeSnapshotHash(), policyVersion);
            if (grant.isEmpty()) {
                throw new Rollback(rejected("NO_GRANT"));
            }
            grantId = grant.get().grantId();
            if (!approvalGate.tryReserveGrantQuota(grantId, clock.instant())) {
                throw new Rollback(rejected("GRANT_NOT_RESERVABLE"));
            }
            authzId = approvalGate.insertAuthorization(grantId, clock.instant());
        }
        // PD-D1：scoped unlock——注册表三元（tool × resource_uid × canonical_env）
        // 全匹配且 enabled 才真执行铸造；任一不满足 = dry-run（默认封死）
        boolean unlocked = false;
        if (unlockScopes != null) {
            var scope = unlockScopes.findByTool(intent.toolName())
                    .filter(s -> s.enabled()
                            && s.resourceUid().equals(intent.resolvedResourceUid()));
            if (scope.isPresent()) {
                unlocked = unlockScopes.envOfResource(intent.resolvedResourceUid())
                        .map(env -> env.equals(scope.get().canonicalEnv()))
                        .orElse(false);
            }
        }
        UUID operationId = UUID.randomUUID();
        RcaOperation operation = unlocked
                ? RcaOperation.prepareReal(operationId, intentId, intent.runId(),
                intent.taskId(), intent.toolName(), intent.actionDigest(),
                intent.resolvedResourceUid(), 0, intent.argsJson(), clock.instant())
                : RcaOperation.prepare(operationId, intentId, intent.runId(),
                intent.taskId(), intent.toolName(), intent.actionDigest(),
                intent.resolvedResourceUid(), 0, intent.argsJson(), clock.instant());
        operations.insert(operation); // 步骤 3
        var acquire = locks.acquire(intent.resolvedResourceUid(), operationId, intent.runId(),
                lockTtl, clock.instant()); // 步骤 4a
        if (!acquire.acquired()) {
            throw new Rollback(rejected("LOCK_BUSY"));
        }
        operations.updateResourceEpoch(operationId, acquire.resourceEpoch());
        outbox.insert(UUID.randomUUID(), operationId, clock.instant()); // 步骤 4b
        if (approvalEnabled
                && !approvalGate.consumeAuthorization(authzId, operationId, clock.instant())) {
            throw new Rollback(rejected("AUTHZ_CAS_LOST")); // 步骤 2 收口：single-use 恰一次
        }
        if (!intents.markPlanned(intentId, operationId, clock.instant())) { // 步骤 5a
            throw new Rollback(rejected("INTENT_CAS_LOST"));
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("kind", "OPERATION_PREPARED");
        payload.put("operation_id", operationId.toString());
        payload.put("intent_id", intentId.toString());
        payload.put("action_digest", intent.actionDigest());
        payload.put("resource_uid", intent.resolvedResourceUid());
        payload.put("resource_epoch", acquire.resourceEpoch());
        payload.put("snapshot_hash", intent.scopeSnapshotHash());
        payload.put("dry_run", !unlocked);
        payload.put("unlocked", unlocked);
        if (approvalEnabled) {
            payload.put("grant", authzId.toString()); // single-use 授权锚（已 CONSUMED）
            payload.put("grant_id", grantId.toString());
        } else {
            payload.put("grant", "DRY_RUN_SENTINEL"); // 审批面未启用的哨兵形态
        }
        events.append(intent.runId(), new RcaEventAppender.EventDraft(UUID.randomUUID(),
                "OPERATION_PREPARED", CanonicalEventJson.canonicalize(payload))); // 步骤 5b
        return new Outcome(Outcome.Status.PLANNED, operationId, acquire.resourceEpoch(), null);
    }

    private static Outcome rejected(String reason) {
        return new Outcome(Outcome.Status.REJECTED, null, -1, reason);
    }

    /** 事件载荷 canonical 化（固定键序；数值/布尔裸值；PC-C1 起审批面事件共用） */
    public static final class CanonicalEventJson {
        private CanonicalEventJson() {
        }

        public static String canonicalize(Map<String, Object> payload) {
            StringBuilder json = new StringBuilder("{");
            boolean first = true;
            for (Map.Entry<String, Object> entry : payload.entrySet()) {
                if (!first) {
                    json.append(',');
                }
                first = false;
                json.append('"').append(entry.getKey()).append("\":");
                Object value = entry.getValue();
                if (value instanceof String s) {
                    json.append('"').append(s.replace("\"", "\\\"")).append('"');
                } else {
                    json.append(value);
                }
            }
            return json.append('}').toString();
        }
    }
}
