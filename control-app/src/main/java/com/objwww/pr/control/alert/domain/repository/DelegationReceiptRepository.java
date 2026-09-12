package com.objwww.pr.control.alert.domain.repository;

import com.objwww.pr.control.alert.domain.agent.DelegationReceipt;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 子任务回执台账端口（MC21~23，V96）。append-only：insert 唯一键冲突显式抛
 * （message_id 幂等面的调用方竞态短路依据，与 DelegationDecisionRepository 同律）。
 */
public interface DelegationReceiptRepository {

    void insert(DelegationReceipt receipt);

    Optional<DelegationReceipt> findByMessageId(UUID messageId);

    /** 合并面：当前轮的 ACCEPTED 回执（主任务信封 child_receipts 槽唯一来源） */
    List<DelegationReceipt> findAcceptedByRunAndRound(UUID runId, UUID primaryTaskId,
            int roundId);

    /** 迟到对账面：某子任务已准入的回执（幂等复核/审计） */
    List<DelegationReceipt> findByChildTaskId(UUID childTaskId);
}
