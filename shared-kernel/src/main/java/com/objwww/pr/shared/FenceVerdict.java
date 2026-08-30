package com.objwww.pr.shared;

/**
 * epoch fence 判定结果（v2.2 §3）。
 */
public enum FenceVerdict {

    /** 放行 */
    ALLOW,
    /** command epoch < current：旧世代，拒绝并走 supersede 路径（v2.1 修订三收口 Publisher） */
    REJECT_SUPERSEDE,
    /** command epoch > current：读取陈旧而非 fence，可重试（KIP-320 先例，EX-05） */
    RETRYABLE
}
