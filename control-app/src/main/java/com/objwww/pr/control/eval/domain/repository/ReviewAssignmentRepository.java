package com.objwww.pr.control.eval.domain.repository;

import com.objwww.pr.control.eval.domain.model.ReviewAssignment;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * EV-08 评审任务端口（review_assignment；V87 授权面——control_app select,insert +
 * 列级 update 六列）。写面只有三条：生成 insert、领取 CAS、提交 CAS——
 * 状态推进全部走单行条件 UPDATE（行数 1 = 赢，0 = 冲突重读归因），
 * 无 read-modify-write 面（EU29 双人同领 DB 层只可能一人赢）。
 */
public interface ReviewAssignmentRepository {

    /** 任务列表键集游标（(created_at, id) 严格小于继续取页，沿 runs 惯例） */
    record KeysetCursor(Instant createdAt, UUID id) {
    }

    /** 一页任务；hasMore = 取到 limit+1 行（调用方据此发 nextCursor） */
    record AssignmentPage(List<ReviewAssignment> items, boolean hasMore) {
    }

    /** 生成待领取任务（PENDING/revision 0）；同 run 案例重复生成归应用层 ensure 语义 */
    void insert(ReviewAssignment assignment);

    /** 单任务；未知 id → empty（controller 404 面） */
    Optional<ReviewAssignment> findById(UUID id);

    /**
     * 领取 CAS：单行 UPDATE——谓词 status='PENDING' OR (IN_PROGRESS 且租约已超时
     * （now 判定））；置 reviewer/claimed_at/lease_expires_at，revision+1。
     * true = 领到；false = 未领到（他人持有效租约 / 已提交 / 并发撞），调用方
     * 重读归因。同一评审人对同案例的第二份领取由 uq 部分唯一索引违约兜底
     * （DuplicateKeyException 上抛，应用层归因 CONFLICT）。
     */
    boolean claim(UUID id, String reviewer, Instant claimedAt, Instant leaseExpiresAt,
                  Instant now);

    /**
     * 提交 CAS：单行 UPDATE——谓词 id + reviewer + status='IN_PROGRESS' +
     * revision=expectedRevision 四锚；置 SUBMITTED/submitted_at，revision+1。
     * false = 四锚任一不符（越权/已提交/租约被回收重领导致 revision 漂移），
     * 调用方重读归因 409。
     */
    boolean submit(UUID id, String reviewer, int expectedRevision, Instant submittedAt);

    /**
     * 任务列表页（created_at DESC, id DESC；reviewer=null 全部 / 非空 = 我的；
     * statusFilter=null 不过滤——按有效状态过滤：PENDING 含租约超时的 IN_PROGRESS
     * （惰性回收投影，now 判定）；实现方内部取 limit+1 判 hasMore）。
     */
    AssignmentPage listByRun(UUID runId, String reviewer, ReviewAssignment.Status statusFilter,
                             Instant now, KeysetCursor cursor, int limit);

    /** run 全部任务（进度分桶输入；任务数为生成次数量级，不分页） */
    List<ReviewAssignment> listAllByRun(UUID runId);

    /** 案例的开放任务数（PENDING + 租约有效的 IN_PROGRESS；生成 ensure 语义的去重锚） */
    int countOpenByCase(UUID runId, UUID caseExecutionId, Instant now);
}
