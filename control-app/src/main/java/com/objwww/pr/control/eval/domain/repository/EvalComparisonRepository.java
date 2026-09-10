package com.objwww.pr.control.eval.domain.repository;

import com.objwww.pr.control.eval.domain.model.EvalComparisonRecord;

import java.util.Optional;
import java.util.UUID;

/**
 * EV-07 对比结论仓储（V85 eval_comparison；insert-only，无 UPDATE/DELETE 面——
 * 重复落档 = 换新 id 一行，同对"生效面" = 最新落档）。
 *
 * <p>授权面（V85）：control_app select,insert；eval_app 与生产角色显式 revoke。
 * 对比计算与落档都在 control-app 读面内完成（输入只读投影表），无 worker 消费面。
 */
public interface EvalComparisonRepository {

    /** 落档一行（insert-only；同 id 二次插入违约 = 禁覆盖） */
    void insert(EvalComparisonRecord record);

    /** 同 (baseline, candidate) 对的最新落档（created_at DESC, id DESC）；无 → empty */
    Optional<EvalComparisonRecord> findLatestByPair(UUID baselineRunId, UUID candidateRunId);
}
