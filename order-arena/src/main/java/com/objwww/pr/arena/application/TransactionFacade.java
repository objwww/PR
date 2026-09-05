package com.objwww.pr.arena.application;

/**
 * 最小事务面（M2-11 退款链 / F1 恢复共用）：每个 CAS/插入步骤一个独立短事务，
 * 编排层不包大事务。装配期绑定 TransactionTemplate（REQUIRES_NEW 由步骤层决定）。
 */
@FunctionalInterface
public interface TransactionFacade {

    void inTx(Runnable action);
}
