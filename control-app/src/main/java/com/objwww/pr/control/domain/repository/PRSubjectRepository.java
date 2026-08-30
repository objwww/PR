package com.objwww.pr.control.domain.repository;

import com.objwww.pr.control.domain.model.PRSubject;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/** PR 账户行端口。sequence/epoch 的原子推进走 SequenceAllocator，不经本接口。 */
public interface PRSubjectRepository {

    /**
     * 新建插入或按 id 更新投影字段（state/draft/merged 等）。
     * 实现约束：publication_epoch / next_outbox_sequence / last_resolved_sequence 不得经本方法改写
     * ——epoch 只走 {@link #switchRevisionAndBumpEpoch}，sequence 只走 SequenceAllocator。
     */
    void save(PRSubject subject);

    /**
     * T1 换届：current_revision_id / current_policy_version 切换与 publication_epoch+1
     * 同一句 UPDATE 原子完成（v2.2 §3-2），不允许读改写。
     */
    void switchRevisionAndBumpEpoch(UUID id, UUID revisionId, String policyVersion, Instant now);

    Optional<PRSubject> findById(UUID id);

    /** 对齐 uq_pr_subject(github_repository_id, pr_number) */
    Optional<PRSubject> findByRepositoryAndPrNumber(long githubRepositoryId, int prNumber);
}
