package com.objwww.pr.arena.domain.repository;

import com.objwww.pr.arena.domain.model.RefundOrder;
import com.objwww.pr.arena.domain.model.RefundState;

import java.util.Optional;
import java.util.UUID;

/** 退款单仓储端口（M2-11）。 */
public interface RefundOrderRepository {

    void insert(RefundOrder refund);

    boolean casState(UUID id, RefundState from, RefundState to);

    Optional<RefundOrder> findById(UUID id);
}
