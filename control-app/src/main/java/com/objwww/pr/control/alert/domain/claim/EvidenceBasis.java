package com.objwww.pr.control.alert.domain.claim;

/**
 * 证据基础（AM4 M4-21 v1.3 统一状态模型三正交字段之二）：
 * SINGLE_SOURCE = 胜出断言只来自一个独立来源（含权威源裁决——权威定状态，非佐证计数）；
 * MULTI_SOURCE_CONSISTENT = ≥2 个独立来源断言一致（确认级别，corroborated）；
 * MULTI_SOURCE_CONFLICT = 来源互相冲突、无法裁决（命题状态恒 UNKNOWN，升级人工）。
 * <b>不存在独立 verdict 出口枚举</b>（v1.2 CONFIRMED/SUPPORTED 与 basis 重叠矛盾，作废）。
 */
public enum EvidenceBasis {
    SINGLE_SOURCE, MULTI_SOURCE_CONSISTENT, MULTI_SOURCE_CONFLICT
}
