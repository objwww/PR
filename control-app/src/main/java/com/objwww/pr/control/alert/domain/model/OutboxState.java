package com.objwww.pr.control.alert.domain.model;

/**
 * outbox 投递行状态（V9 ck_notify_outbox_state；M3-20 Claimer 驱动）：
 * PENDING → CLAIMED（租约内）→ SENT / RETRY_WAIT / DEAD；SUPPRESSED 预留。
 * at-least-once：SENT 行不删不改（除终态列），重复由 operation_id 可检测。
 */
public enum OutboxState {
    PENDING,
    CLAIMED,
    SENT,
    RETRY_WAIT,
    DEAD,
    SUPPRESSED
}
