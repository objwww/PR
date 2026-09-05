package com.objwww.pr.arena.domain.model;

/**
 * 交易单 booking 维度（C-1 冻结）：CREATED 不可见 → ENABLED 生效 → DISCARDED 废单终态。
 * 迁移合法性由 BookingStateMachine 把守；CREATED 对查询 API 不可见（M2-09）。
 */
public enum BookingStatus {
    CREATED,
    ENABLED,
    DISCARDED
}
