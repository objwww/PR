package com.objwww.pr.arena.domain.repository;

import com.objwww.pr.arena.domain.model.IdempotencyClaim;

import java.time.Duration;
import java.util.UUID;

/**
 * 幂等记录端口（C-2 全语义：claim/replay/conflict + 租约栅栏）。
 * 实现为单条 SQL 的原子 check-and-mark（Stripe 式），崩溃回收靠租约过期重领。
 */
public interface IdempotencyRepository {

    /**
     * 原子认领：同 key 同 digest 未消费或租约过期 → CLAIMED（过期重领 epoch+1）；
     * 同 key 同 digest CONSUMED → REPLAY；同 key 同 digest 租约内 PROCESSING → IN_PROGRESS；
     * 同 key 不同 digest → CONFLICT。
     *
     * @param intentId      幂等键
     * @param requestDigest sha256(规范化请求体)
     * @param owner         认领者标识（进程+线程）
     * @param leaseDuration 租约时长
     * @param ttl           记录保活时长（expires_at）
     */
    IdempotencyClaim claim(String intentId, String requestDigest, String owner,
                           Duration leaseDuration, Duration ttl);

    /** 消费完成：PROCESSING→CONSUMED，带 epoch 栅栏；0 行 = 已被回收/他人持有，返回 false。 */
    boolean complete(String intentId, long leaseEpoch, UUID resultOrderId, String responseDigest);

    /** 处理失败释放：PROCESSING→NEW（清租约、保留 epoch 累计），供后续重试重新认领。 */
    void release(String intentId, long leaseEpoch);
}
