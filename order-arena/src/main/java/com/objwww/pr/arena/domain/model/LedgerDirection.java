package com.objwww.pr.arena.domain.model;

/** 台账方向：DEDUCT 扣减（创单）/ REFUND 回补（补偿 worker，幂等锚 = 同 deduction_seq 唯一）。 */
public enum LedgerDirection {
    DEDUCT,
    REFUND
}
