package com.objwww.pr.arena.domain.model;

import java.time.Instant;
import java.util.UUID;

/**
 * 资源台账流水。DEDUCT 扣减（创单逐资源短事务）；REFUND 回补（补偿 worker）。
 * 幂等锚：REFUND 行的 (orderId, resourceType, deductionSeq) 唯一（DB 强制）。
 */
public record ResourceLedgerEntry(
        UUID id,
        UUID orderId,
        ResourceType resourceType,
        LedgerDirection direction,
        int deductionSeq,
        int quantity,
        Instant createdAt) {

    public static ResourceLedgerEntry deduct(UUID orderId, ResourceType type, int seq, int qty) {
        return new ResourceLedgerEntry(UUID.randomUUID(), orderId, type, LedgerDirection.DEDUCT,
                seq, qty, Instant.now());
    }

    /** 回补行：引用其对应 DEDUCT 行的序号（幂等锚） */
    public ResourceLedgerEntry asRefund() {
        return new ResourceLedgerEntry(UUID.randomUUID(), orderId, resourceType,
                LedgerDirection.REFUND, deductionSeq, quantity, Instant.now());
    }
}
