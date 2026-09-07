package com.objwww.pr.control.ops.domain.repository;

import com.objwww.pr.control.ops.domain.model.LegalHold;
import com.objwww.pr.control.ops.domain.model.RetentionPolicy;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 保留策略仓储（M5-18；V29 三表）。策略链 insert-only（无 update/delete 授权面）；
 * hold 的唯一可变面 = released_at 单列（release 动作）。
 */
public interface RetentionPolicyRepository {

    void insertPolicy(RetentionPolicy policy);

    /** 生效策略 = policy_version 最大行（同版本取 created_at 最新，确定性 tie-break） */
    Optional<RetentionPolicy> latestPolicy();

    UUID insertHold(String scope, String reason, String createdBy, Instant at);

    /** 生效中 hold（released_at is null） */
    List<LegalHold> activeHolds();

    /** release：仅 released_at is null 的行可释放（二次释放返回 false） */
    boolean releaseHold(UUID holdId, Instant at);
}
