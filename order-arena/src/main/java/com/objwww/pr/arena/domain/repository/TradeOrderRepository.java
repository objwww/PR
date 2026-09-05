package com.objwww.pr.arena.domain.repository;

import com.objwww.pr.arena.domain.model.BookingStatus;
import com.objwww.pr.arena.domain.model.OrderSnapshot;
import com.objwww.pr.arena.domain.model.PayStatus;
import com.objwww.pr.arena.domain.model.TradeOrder;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 交易单/履约单仓储端口。状态迁移一律 CAS（from → to，影响行数 = 成功与否），
 * 前置的矩阵校验在领域对象完成，双保险防并发穿插。
 */
public interface TradeOrderRepository {

    /** M2-09 第一步：交易单(CREATED)+履约单(CONFIRMING) 单事务落库，返回成对快照 */
    OrderSnapshot insertCreatedSnapshot(TradeOrder tradeOrder);

    Optional<TradeOrder> findById(UUID id);

    /** 查询 API 视角：CREATED 不可见（M2-09） */
    Optional<TradeOrder> findVisibleById(UUID id);

    /** CAS booking 迁移；reason 仅 DISCARDED 使用 */
    boolean casBookingStatus(UUID id, BookingStatus from, BookingStatus to, String reason);

    /** CAS pay 迁移（PAID 仅当 booking=ENABLED 的守卫在领域对象，DB 层 WHERE 双兜底） */
    boolean casPayStatus(UUID id, PayStatus from, PayStatus to);

    /** 同 intent 的非废单数（重复单判定面；F1 的探测依据，INV-AM2-5） */
    List<TradeOrder> findByIntentId(String intentId);
}
