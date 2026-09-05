package com.objwww.pr.arena.domain.repository;

import com.objwww.pr.arena.domain.model.ResourceLedgerEntry;

import java.util.List;
import java.util.UUID;

/** 资源台账仓储端口（逐笔短事务写入；回补幂等靠 DB 唯一锚）。 */
public interface ResourceLedgerRepository {

    /** 扣减一行（创单第二步逐资源短事务调用） */
    void insertDeduct(ResourceLedgerEntry entry);

    /** 回补一行；唯一锚冲突返回 false（= 已回补过，幂等跳过，§6.3） */
    boolean insertRefundIfAbsent(ResourceLedgerEntry refundEntry);

    /** 订单的全部 DEDUCT 行（按 seq 升序；补偿计划与反向顺序的依据） */
    List<ResourceLedgerEntry> listDeductions(UUID orderId);

    boolean hasRefund(UUID orderId, String resourceType, int deductionSeq);
}
