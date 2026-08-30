package com.objwww.pr.shared;

/**
 * publication_resource 状态（v2.2 §1；与 V1 ck_pub_resource_state 一致）。
 * 资源视角的"现在时"，与 Outbox 命令历史分离；DRIFTED 不进入 Outbox。
 */
public enum PublicationResourceState {

    ACTIVE,
    DRIFTED,
    REPAIRED,
    /** 旧世代自身对象被正常关闭（如旧 Check 标 cancelled） */
    RETIRED
}
