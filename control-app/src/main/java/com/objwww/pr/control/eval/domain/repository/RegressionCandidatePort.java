package com.objwww.pr.control.eval.domain.repository;

import com.objwww.pr.control.eval.domain.model.RegressionCandidate;
import com.objwww.pr.control.eval.domain.model.RegressionReview;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 回归候选/审核台账端口（OP-01，V104）：候选 insert-if-absent（(source_digest,
 * case_key) 唯一——同源同 case 幂等不堆重复候选）；状态推进条件写（CAS from
 * state）；审核意见 append-only 且 (candidate, reviewer) 幂等。
 */
public interface RegressionCandidatePort {

    /** 无冲突=入库返回入参；唯一键冲突=返回既有行（幂等锚=source_digest+case_key） */
    RegressionCandidate insertIfAbsent(RegressionCandidate candidate);

    /** 状态 CAS（fromState 不匹配返回 empty——并发审核/裁决已推进） */
    Optional<RegressionCandidate> casState(UUID id, String fromState,
            RegressionCandidate next);

    Optional<RegressionCandidate> findById(UUID id);

    List<RegressionCandidate> findByState(String state);

    /** 审核意见：同 (candidate, reviewer) 已存在 → 返回既有行（幂等重投） */
    RegressionReview insertReview(RegressionReview review);

    List<RegressionReview> reviewsOf(UUID candidateId);
}
