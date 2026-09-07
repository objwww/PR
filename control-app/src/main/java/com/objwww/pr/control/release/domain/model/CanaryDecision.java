package com.objwww.pr.control.release.domain.model;

/**
 * Canary 路由决策冻结值域（M5-10；canary_route_decision.decision 的 check 约束同源）。
 * 机器码英文（决策可回溯口径，SafetyFace 同族）；每个值一条中文口径注释。
 */
public enum CanaryDecision {

    /** 无 active bundle：全量主路径（bundle 未发布/未激活） */
    NO_ACTIVE_BUNDLE,

    /** bundle 无 canary 段或 percent 未配置：放量未启用，全量主路径 */
    CANARY_DISABLED,

    /** 缺 stickiness key：拒绝放量（修 Unleash random 回退坑），run 照常主路径 */
    NO_STICKINESS_KEY,

    /** 白名单直进：id 命中 canary.whitelist，无视比例直接 NATIVE */
    WHITELISTED,

    /** 分桶命中：bucket < percent，NATIVE 候选桶 */
    BUCKETED_NATIVE,

    /** 分桶未命中：bucket ≥ percent，HOLMES 主路径（bucket 照记随审计） */
    BUCKETED_HOLMES,

    /** NATIVE 执行面未就绪（O-3/M6-01）：NATIVE 意愿降级 HOLMES——立即回退为一等操作 */
    NATIVE_DEFERRED,

    /** 爆炸半径超限：NATIVE 运行数达 max_native_runs 上限，自动停放量（无状态重数自愈） */
    BLAST_RADIUS_STOPPED
}
