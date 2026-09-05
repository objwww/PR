package com.objwww.pr.arena.domain.model;

import java.util.UUID;

/**
 * 幂等 claim 的确定性结果（C-2 语义冻结，封闭类型）：
 * CLAIMED 拿到处理权（携带租约代数，complete/fail 须回带做栅栏）；
 * REPLAY 同 key 同 digest 且 CONSUMED → 重放原结果；
 * IN_PROGRESS 同 key 同 digest 且 PROCESSING（租约未过期）→ 202 处理中；
 * CONFLICT 同 key 不同 digest → 409。
 */
public sealed interface IdempotencyClaim {

    record Claimed(long leaseEpoch) implements IdempotencyClaim {
    }

    record Replay(UUID resultOrderId, String responseDigest) implements IdempotencyClaim {
    }

    record InProgress() implements IdempotencyClaim {
    }

    record Conflict() implements IdempotencyClaim {
    }
}
