package com.objwww.pr.shared;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * UT-03：epoch fence 判定（v2.2 §3）。
 */
class RevisionFenceTest {

    private final RevisionFence fence = new RevisionFence();

    @Test
    void equalEpochAllowed() {
        assertEquals(FenceVerdict.ALLOW, fence.check(FenceMode.CURRENT_EPOCH, 7, 7));
    }

    @Test
    void staleEpochRejectedForSupersede() {
        // command < current：旧世代命令绝不能抵达 GitHub 写（I6），走 supersede 路径
        assertEquals(FenceVerdict.REJECT_SUPERSEDE, fence.check(FenceMode.CURRENT_EPOCH, 6, 7));
    }

    @Test
    void aheadEpochIsRetryableNotRejected() {
        // command > current：读取陈旧（KIP-320 先例），可重试而非 fence 误杀（EX-05）
        assertEquals(FenceVerdict.RETRYABLE, fence.check(FenceMode.CURRENT_EPOCH, 8, 7));
    }

    @Test
    void ownedGenerationAlwaysAllowed() {
        // 旧世代收尾命令绑定所属 epoch 放行（v2.2 §3-5）
        assertEquals(FenceVerdict.ALLOW, fence.check(FenceMode.OWNED_GENERATION, 6, 7));
        assertEquals(FenceVerdict.ALLOW, fence.check(FenceMode.OWNED_GENERATION, 7, 7));
        assertEquals(FenceVerdict.ALLOW, fence.check(FenceMode.OWNED_GENERATION, 8, 7));
    }

    @Test
    void negativeEpochRejected() {
        assertThrows(IllegalArgumentException.class, () -> fence.check(FenceMode.CURRENT_EPOCH, -1, 0));
        assertThrows(IllegalArgumentException.class, () -> fence.check(FenceMode.CURRENT_EPOCH, 0, -1));
    }
}
