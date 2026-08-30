package com.objwww.pr.shared;

import java.util.Objects;

/**
 * epoch fence 判定（v2.2 §3，F9）。纯函数；真实 fence 在存储端与状态推进同事务完成（E1），
 * 本类是判定规则的唯一实现，供 Control 侧（T2 铸造校验）与后续 Publisher（T3 fence）复用。
 */
public final class RevisionFence {

    /**
     * @param commandEpoch 命令铸造时锁定的 publication_epoch
     * @param currentEpoch pr_subject 当前 publication_epoch
     */
    public FenceVerdict check(FenceMode fenceMode, long commandEpoch, long currentEpoch) {
        Objects.requireNonNull(fenceMode, "fenceMode");
        if (commandEpoch < 0 || currentEpoch < 0) {
            throw new IllegalArgumentException("epoch 必须 >= 0");
        }
        // OWNED_GENERATION：终结旧世代自身对象的写，绑定其所属 epoch 放行收尾（v2.2 §3-5）。
        // "确实只操作所属世代对象"在命令铸造与 Handler 白名单处校验，不在本函数。
        if (fenceMode == FenceMode.OWNED_GENERATION) {
            return FenceVerdict.ALLOW;
        }
        if (commandEpoch == currentEpoch) {
            return FenceVerdict.ALLOW;
        }
        return commandEpoch < currentEpoch ? FenceVerdict.REJECT_SUPERSEDE : FenceVerdict.RETRYABLE;
    }
}
