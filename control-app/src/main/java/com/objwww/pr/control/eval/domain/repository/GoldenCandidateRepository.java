package com.objwww.pr.control.eval.domain.repository;

import com.objwww.pr.control.eval.domain.model.GoldenCandidate;
import com.objwww.pr.control.eval.domain.model.GoldenCandidateState;
import com.objwww.pr.control.eval.domain.model.GoldenReviewEvent;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * golden_candidate / golden_review_event 端口（M5-03；INV-AM5-2）。
 *
 * <p>契约：insertDraft 原子落 DRAFT 行 + PROPOSED 事件；casTransition 以
 * (id, expectedState, revision) 三重 CAS 迁移并在同一事务追加事件——返回
 * false = 旁路或并发修改（调用方不得重试同参数）；findEvent 供幂等重放判定
 * （同 idempotencyKey = 已应用，空操作）。事件表 append-only，无任何
 * UPDATE/DELETE 路径。
 */
public interface GoldenCandidateRepository {

    /** 原子插入 DRAFT 候选 + PROPOSED 事件 */
    void insertDraft(GoldenCandidate candidate, GoldenReviewEvent event);

    Optional<GoldenCandidate> find(UUID id);

    /** 幂等重放判定：同 idempotencyKey 的事件已存在 = true */
    boolean eventExists(String idempotencyKey);

    /**
     * CAS 迁移 + 事件同事务：UPDATE ... WHERE id AND state=expectedState AND
     * revision=expectedRevision，命中才 INSERT 事件；0 行 = false（不改任何状态）。
     */
    boolean casTransition(GoldenCandidate next,
                          GoldenCandidateState expectedState,
                          GoldenReviewEvent event);

    /** 复核事件时间线（append-only 读面） */
    List<GoldenReviewEvent> events(UUID candidateId);
}
