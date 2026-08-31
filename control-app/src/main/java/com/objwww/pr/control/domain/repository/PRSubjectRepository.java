package com.objwww.pr.control.domain.repository;

import com.objwww.pr.control.domain.model.PRSubject;
import com.objwww.pr.control.domain.model.PrSubjectState;

import java.time.Instant;
import java.util.List;
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

    /**
     * LWW 水印推进（M1-T05，I10/CT-14）：last_event_updated_at = GREATEST(旧值, 新值)，
     * 单句条件 UPDATE 防并发回退（并发两 T1 水印收敛于 max）；实现须保证旧值 NULL 时
     * 直接采纳新值。调用方负责"缺 updatedAt 不覆盖"（EX-18：传 null 即不调本方法）。
     */
    void advanceWatermarkIfNewer(UUID id, Instant eventUpdatedAt, Instant now);

    /**
     * T-close / T-draft（M1-T06，I15）：投影（state/draft/merged）刷新与 publication_epoch+1
     * 同一句 UPDATE 原子完成——closed/converted_to_draft 必递增 epoch，否则同 epoch 的
     * PENDING 命令会在已关闭/转 draft 的 PR 上发出去（Publisher sweepStaleEpoch 只废弃
     * epoch 落后的命令）。
     */
    void refreshStateAndBumpEpoch(UUID id, PrSubjectState state, boolean draft, boolean merged, Instant now);

    Optional<PRSubject> findById(UUID id);

    /** 对齐 uq_pr_subject(github_repository_id, pr_number) */
    Optional<PRSubject> findByRepositoryAndPrNumber(long githubRepositoryId, int prNumber);

    /**
     * PR State Reconciler 公平扫描（M1-T07，方案 §4.5 修正 #7）：
     * {@code WHERE state='OPEN' AND next_pr_reconcile_at<=now() ORDER BY next_pr_reconcile_at LIMIT :limit}
     * ——最久未查的先查，LIMIT 不饿死尾部（E2E-14）；时间比较走 DB now()（I17）。
     */
    List<PRSubject> findDueForReconcile(int limit);

    /**
     * 对账成功（含幂等收敛）：{@code next_pr_reconcile_at = now() + interval,
     * pr_reconcile_error_count = 0}，单句 UPDATE，时刻由 DB now() 计算（I17）。
     */
    void markReconciled(UUID id, java.time.Duration interval);

    /**
     * 对账失败（429/5xx/sanity 失败/派发异常）：{@code error_count+1,
     * next_pr_reconcile_at = now() + backoff}（指数退避由调用方算好，429 的 retryAfter
     * 已与退避取大，EX-16）。
     *
     * @return 递增后的 error_count（调用方据此判定 ReconcilerDegraded 阈值，措辞修正 #3）
     */
    int markReconcileError(UUID id, java.time.Duration backoff);
}
