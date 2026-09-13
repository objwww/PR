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

    /**
     * RV04/T20：原子幂等插入（ON CONFLICT (message_id) DO NOTHING）——返回是否
     * 本副本插入成功（0=同键已存在）。PG 事务内唯一冲突会置 aborted、后续语句
     * 25P02，不能异常后同事务续操作；并发分支必须走本方法，冲突后另条查询读胜者。
     */
    int insertIfAbsent(DelegationReceipt receipt);

    Optional<DelegationReceipt> findByMessageId(UUID messageId);

    /** 合并面：当前轮的 ACCEPTED 回执（主任务信封 child_receipts 槽唯一来源） */
    List<DelegationReceipt> findAcceptedByRunAndRound(UUID runId, UUID primaryTaskId,
            int roundId);

    /** 迟到对账面：某子任务已准入的回执（幂等复核/审计） */
    List<DelegationReceipt> findByChildTaskId(UUID childTaskId);
}
