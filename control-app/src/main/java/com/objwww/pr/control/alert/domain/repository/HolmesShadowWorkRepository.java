package com.objwww.pr.control.alert.domain.repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Holmes Shadow 持久工作面端口（M6-05，V34 holmes_shadow_work；C-65）。
 *
 * <p>语义契约：
 * <ul>
 *   <li>{@link #enqueue} 撞确定性 shadow_key 唯一约束返回 false（重放入队幂等），
 *       不抛异常；</li>
 *   <li>{@link #claimBatch} SKIP LOCKED 批量认领：QUEUED/FAILED（attempts&lt;max）或
 *       租约过期的 LEASED（进程崩溃恢复）才可领；认领即租约续身 + attempts+1
 *       + lease_epoch+1（CAS 基准）；</li>
 *   <li>{@link #complete}/{@link #markFailed} 以 (owner, leaseEpoch, LEASED) 三元 CAS
 *       收口——败者 0 行不改写（过期持有者回写无效）；</li>
 *   <li>工作历史不可抹：端口无 delete 面（V34 revoke delete 同律）。</li>
 * </ul>
 */
public interface HolmesShadowWorkRepository {

    /** 工作行读取形状（V34 列全集） */
    record ShadowWorkRow(long id, String shadowKey, String kind, UUID nativeRunId,
                         UUID incidentId, int generation, String snapshotDigest,
                         String state, int attempts, int maxAttempts, String leaseOwner,
                         Instant leaseUntil, int leaseEpoch, Integer tokensSpent,
                         String lastError, Instant createdAt, Instant updatedAt) {

        /** 入队形状（id/租约/终态列未定） */
        public static ShadowWorkRow forEnqueue(String shadowKey, String kind, UUID nativeRunId,
                UUID incidentId, int generation, String snapshotDigest, int maxAttempts) {
            return new ShadowWorkRow(0, shadowKey, kind, nativeRunId, incidentId, generation,
                    snapshotDigest, "QUEUED", 0, maxAttempts, null, null, 0, null, null,
                    null, null);
        }

        public boolean comparison() {
            return "COMPARISON".equals(kind);
        }
    }

    /** 撞 shadow_key = false（重放幂等，不抛） */
    boolean enqueue(ShadowWorkRow row);

    /** 预算/观测：窗口内入队总数 */
    long countCreatedSince(Instant after);

    Optional<ShadowWorkRow> findByShadowKey(String shadowKey);

    /**
     * SKIP LOCKED 批量认领：QUEUED/FAILED（attempts &lt; max）或租约过期 LEASED；
     * 认领即 state=LEASED + owner/lease_until 写入 + attempts/epoch 双 +1。
     */
    List<ShadowWorkRow> claimBatch(String owner, Instant now, java.time.Duration lease,
            int limit);

    /** 成功收口 CAS：state=LEASED 且 (owner,epoch) 匹配 → SUCCEEDED + tokens；0 行=败者 */
    int complete(long id, String owner, int leaseEpoch, Integer tokensSpent, Instant now);

    /**
     * 失败收口 CAS：attempts ≥ max → EXHAUSTED（有界重试耗尽），否则 FAILED
     * （可再认领）；0 行=败者（过期持有者）。
     */
    int markFailed(long id, String owner, int leaseEpoch, String error, Instant now);
}
