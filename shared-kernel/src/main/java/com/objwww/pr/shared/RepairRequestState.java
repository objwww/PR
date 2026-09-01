package com.objwww.pr.shared;

public enum RepairRequestState {
    PENDING,
    APPROVED,
    DISPATCHED,
    RETRY_WAIT,
    REPAIRED,
    FAILED_TERMINAL,
    EXPIRED
}
