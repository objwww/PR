package com.objwww.pr.control.domain.repository;

import com.objwww.pr.control.domain.model.ReviewRun;
import com.objwww.pr.shared.Digest;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ReviewRunRepository {

    /**
     * 新建插入或按 id 更新状态字段。run_key 唯一冲突（webhook 重投，B-3）
     * 必须以约束冲突异常上抛，由编排层捕获后幂等返回——不得在本方法内吞掉。
     */
    void save(ReviewRun run);

    Optional<ReviewRun> findById(UUID id);

    /** 对齐 uq_review_run_key；webhook 重投幂等兜底（B-3） */
    Optional<ReviewRun> findByRunKey(Digest runKey);

    /** T1 换届用：该 PR 所有未完成（非终态）的**评审** Run（run_mode=NORMAL），经 pr_revision
     *  关联到 pr_subject。REPAIR Run 刻意排除——其终态由 repair 收口投影器拥有（TB-10/INC-39） */
    List<ReviewRun> findActiveByPrSubjectId(UUID prSubjectId);

    /**
     * 该 PR 最近一个 Run（任意状态，created_at 最新；M1-T07）：
     * ReconcilerDegraded 账本事件的兜底挂载点——无 active Run 时挂最近 Run
     * （execution_event.review_run_id/pr_revision_id 为 NOT NULL + FK，必须有 Run 才能落账）。
     */
    Optional<ReviewRun> findLatestByPrSubjectId(UUID prSubjectId);
}
