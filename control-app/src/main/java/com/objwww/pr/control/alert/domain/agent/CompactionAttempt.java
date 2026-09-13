package com.objwww.pr.control.alert.domain.agent;

import java.time.Instant;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * 压缩尝试控制记录（CL-07，V102 rca_compaction_attempt）：§6.4"短事务预留→
 * 外部调用→围栏终态"的台账行。预留时冻结来源（sourceContextDigest）、策略
 * （policyDigest）与四类执行身份（owner/leaseEpoch/configEpoch/expectedRevision
 * ——与检查点提交围栏同律，不可互代）；终态封闭且不删除——拒绝/失败/被超越
 * 全部留痕，预算拒绝也不静默消单。
 *
 * <p>唯一键 (task, source, policy, configEpoch)：同逻辑动作并发预留一胜一拒，
 * 败者读胜者行收敛。首期一逻辑动作一物理尝试（有界重试不自动放大）。
 */
public record CompactionAttempt(
        UUID id,
        UUID runId,
        UUID taskId,
        String sourceContextDigest,
        String policyDigest,
        String owner,
        Long leaseEpoch,
        Long configEpoch,
        long expectedRevision,
        String state,
        String logicalActionKey,
        String errorCode,
        UUID summaryId,
        Instant createdAt,
        Instant settledAt) {

    /** 终态封闭集（状态机：RESERVED→IN_FLIGHT→终态；UNKNOWN=崩溃对账面） */
    public static final Set<String> STATES = Set.of(
            "RESERVED", "IN_FLIGHT", "COMMITTED", "REJECTED",
            "FAILED", "UNKNOWN", "SUPERSEDED");

    public static final String RESERVED = "RESERVED";
    public static final String IN_FLIGHT = "IN_FLIGHT";
    public static final String COMMITTED = "COMMITTED";
    public static final String REJECTED = "REJECTED";
    public static final String FAILED = "FAILED";
    public static final String UNKNOWN = "UNKNOWN";
    public static final String SUPERSEDED = "SUPERSEDED";

    /** 可迁移表：非终态 → 合法后继（终态行不可再改） */
    private static final java.util.Map<String, Set<String>> TRANSITIONS = java.util.Map.of(
            RESERVED, Set.of(IN_FLIGHT, REJECTED, FAILED, UNKNOWN),
            IN_FLIGHT, Set.of(COMMITTED, REJECTED, FAILED, UNKNOWN, SUPERSEDED));

    public CompactionAttempt {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(runId, "runId");
        Objects.requireNonNull(taskId, "taskId");
        Objects.requireNonNull(sourceContextDigest, "sourceContextDigest");
        Objects.requireNonNull(policyDigest, "policyDigest");
        Objects.requireNonNull(state, "state");
        Objects.requireNonNull(logicalActionKey, "logicalActionKey");
        Objects.requireNonNull(createdAt, "createdAt");
        if (!STATES.contains(state)) {
            throw new IllegalArgumentException("attempt state 不在封闭集: " + state);
        }
        boolean terminal = !TRANSITIONS.containsKey(state);
        if (terminal != (settledAt != null)) {
            throw new IllegalArgumentException(
                    "终态必须携带 settled_at（非终态必须为空）: state=" + state);
        }
        if (COMMITTED.equals(state) && summaryId == null) {
            throw new IllegalArgumentException("COMMITTED 必须携带 summary_id");
        }
    }

    /** 预留行（RESERVED）：调用方先 insertIfAbsent，胜者再推进 IN_FLIGHT */
    public static CompactionAttempt reserve(UUID id, UUID runId, UUID taskId,
            String sourceContextDigest, String policyDigest, String owner,
            Long leaseEpoch, Long configEpoch, long expectedRevision,
            String logicalActionKey, Instant createdAt) {
        return new CompactionAttempt(id, runId, taskId, sourceContextDigest,
                policyDigest, owner, leaseEpoch, configEpoch, expectedRevision,
                RESERVED, logicalActionKey, null, null, createdAt, null);
    }

    /** 状态 CAS 合法性（并发败者读到最新终态后不得再迁移） */
    public boolean canTransitionTo(String toState) {
        return TRANSITIONS.getOrDefault(state, Set.of()).contains(toState);
    }

    public boolean terminal() {
        return !TRANSITIONS.containsKey(state);
    }
}
