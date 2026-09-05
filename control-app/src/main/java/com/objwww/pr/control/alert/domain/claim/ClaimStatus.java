package com.objwww.pr.control.alert.domain.claim;

/**
 * Claim 三态（抄 K8s Condition status；AM4 M4-21）：
 * TRUE = 断言成立；FALSE = 断言被证伪；UNKNOWN = 无法判定。
 * 无置信度数值——三态即全部语义，禁投票。
 */
public enum ClaimStatus {
    TRUE, FALSE, UNKNOWN
}
