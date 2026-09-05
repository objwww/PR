package com.objwww.pr.arena.domain.repository;

import com.objwww.pr.arena.domain.model.PaymentRecord;
import com.objwww.pr.arena.domain.model.PaymentRecord.PaymentKind;
import com.objwww.pr.arena.domain.model.PaymentResult;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** 支付事实仓储端口（C-1）。result 迁移 CAS + 状态机双门。 */
public interface PaymentRecordRepository {

    /** INITIATED 出生 */
    void insertInitiated(PaymentRecord record);

    /** CAS result 迁移（INITIATED→X、UNKNOWN→RECONCILING→X 等）；false = 迁移被拒/竞态 */
    boolean casResult(UUID id, PaymentResult from, PaymentResult to);

    Optional<PaymentRecord> findById(UUID id);

    /** 订单的 AUTH 记录（F2 探测："PAID 但无支付事实" 的比对面） */
    List<PaymentRecord> findByOrder(UUID orderId);

    int nextAttemptNo(UUID orderId, PaymentKind kind);

    /** F3 对账扫描面：指定 kind 的非终态记录（UNKNOWN/RECONCILING） */
    List<PaymentRecord> findUnsettled(PaymentKind kind);

    /**
     * F3 领取（M2-20）：UNKNOWN 超龄或 RECONCILING 租约过期的记录，CAS 至 RECONCILING
     * 并打租约（FOR UPDATE SKIP LOCKED，双 reconciler 互斥；租约过期可重领 = 崩溃续跑）。
     */
    List<PaymentRecord> claimReconcileWork(String owner, java.time.Duration lease,
                                           java.time.Duration unknownOlderThan, int limit);

    /** 释放对账租约（决定收口后；不迁状态，仅清持有者痕迹） */
    void releaseReconcileLease(UUID id);
}
