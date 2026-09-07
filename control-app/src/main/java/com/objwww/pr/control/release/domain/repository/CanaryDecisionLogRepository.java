package com.objwww.pr.control.release.domain.repository;

import com.objwww.pr.shared.Digest;

import java.time.Instant;
import java.util.UUID;

/**
 * canary_route_decision 追加面（M5-10；insert-only——路由决策/比例/bucket/digest
 * 全审计，E2E-AM5-05 断言面）。零框架（release.domain.repository 零框架规则覆盖）。
 */
public interface CanaryDecisionLogRepository {

    /**
     * NATIVE 实跑决策计数（decision ∈ {WHITELISTED, BUCKETED_NATIVE}）——爆炸半径
     * 上限的重数源（无状态重查即自愈，无开关行可被绕过）。
     */
    long countNativeDecisions();

    /** 追加一条路由决策审计行（run 铸造点每路由一次必记一行） */
    void append(DecisionRow row);

    /** 审计行投影（V25 canary_route_decision 列全集合） */
    record DecisionRow(UUID runId,
                       String stickinessKey,
                       Integer bucket,
                       int percent,
                       Digest bundleDigest,
                       String decision,
                       Instant createdAt) {
    }
}
