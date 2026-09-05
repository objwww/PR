package com.objwww.pr.notify.domain.port;

/**
 * 租约栅栏未中（B-2 同源）：写回时行的 lease_epoch 已被他人推进（租约过期重领/
 * 僵尸 worker），落账权不在本 worker——调用方按 DEFERRED 处理，绝不覆盖新租约。
 */
public class StaleClaimException extends RuntimeException {

    public StaleClaimException(java.util.UUID id) {
        super("租约栅栏未中（行已被重领或已终态）: " + id);
    }
}
