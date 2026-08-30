package com.objwww.pr.shared;

/**
 * epoch fence 模式（v2.2 §3；与 V1 ck_outbox_fence_mode 一致）。
 */
public enum FenceMode {

    /** 命令 publication_epoch 必须等于 pr_subject.publication_epoch，否则 fence 拒绝 */
    CURRENT_EPOCH,
    /** 放行旧世代收尾：只操作它所属世代已创建的远端对象 */
    OWNED_GENERATION
}
