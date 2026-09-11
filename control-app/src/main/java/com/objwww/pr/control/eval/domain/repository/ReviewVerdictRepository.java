package com.objwww.pr.control.eval.domain.repository;

import com.objwww.pr.control.eval.domain.model.ReviewVerdict;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * EV-08 评审结果端口（review_verdict；insert-only——V87 只授 control_app
 * select,insert，本接口无 update/delete 方法面，V10/V85 同律）。
 * 更正与重评分 = 新 review_assignment + 新行，旧行永不覆盖（审计闭环）。
 */
public interface ReviewVerdictRepository {

    /** 落档评审结论；uq(assignment_id) 违约 = 同任务重复提交（DuplicateKeyException） */
    void insert(ReviewVerdict verdict);

    /** 任务的结论行（一任务至多一行）；无 → empty */
    Optional<ReviewVerdict> findByAssignmentId(UUID assignmentId);

    /** 案例的全部结论行（工作区历史面/审计闭环；created_at ASC 时间序） */
    List<ReviewVerdict> listByCase(UUID runId, UUID caseExecutionId);

    /** run 的全部结论行（分歧检测与进度分桶输入；created_at ASC） */
    List<ReviewVerdict> listByRun(UUID runId);
}
