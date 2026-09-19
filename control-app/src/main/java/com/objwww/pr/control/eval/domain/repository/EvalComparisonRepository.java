package com.objwww.pr.control.eval.domain.repository;

import com.objwww.pr.control.eval.domain.model.EvalComparisonRecord;

import java.util.Optional;
import java.util.UUID;

/**
 * EV-07 对比结论仓储（V85 eval_comparison；insert-only，无 UPDATE/DELETE 面——
 * 重复落档 = 换新 id 一行，同对"生效面" = 最新落档）。
 *
 * <p>授权面（V85 + V149 扩展）：control_app select,insert（读面手工落档）；
 * eval_app select,insert（V149——EV-07 终态钩子自动落档，eval-runner 自此是
 * 该表生产者之一）；其余生产角色显式 revoke 不变。
 */
public interface EvalComparisonRepository {

    /** 落档一行（insert-only；同 id 二次插入违约 = 禁覆盖） */
    void insert(EvalComparisonRecord record);

    /** 同 (baseline, candidate) 对的最新落档（created_at DESC, id DESC）；无 → empty */
    Optional<EvalComparisonRecord> findLatestByPair(UUID baselineRunId, UUID candidateRunId);

    /** 某 run 作为候选的最新落档（EV-07 自动落档幂等判据：已有candidate行即不重复落档） */
    Optional<EvalComparisonRecord> findLatestByCandidate(UUID candidateRunId);
}
